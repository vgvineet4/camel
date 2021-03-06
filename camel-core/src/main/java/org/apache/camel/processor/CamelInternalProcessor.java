/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.processor;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import org.apache.camel.AsyncCallback;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.MessageHistory;
import org.apache.camel.Ordered;
import org.apache.camel.Processor;
import org.apache.camel.Route;
import org.apache.camel.StatefulService;
import org.apache.camel.StreamCache;
import org.apache.camel.model.ProcessorDefinition;
import org.apache.camel.model.ProcessorDefinitionHelper;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.processor.interceptor.BacklogDebugger;
import org.apache.camel.processor.interceptor.BacklogTracer;
import org.apache.camel.processor.interceptor.DefaultBacklogTracerEventMessage;
import org.apache.camel.spi.InflightRepository;
import org.apache.camel.spi.MessageHistoryFactory;
import org.apache.camel.spi.RouteContext;
import org.apache.camel.spi.RoutePolicy;
import org.apache.camel.spi.StreamCachingStrategy;
import org.apache.camel.spi.Transformer;
import org.apache.camel.spi.UnitOfWork;
import org.apache.camel.support.MessageHelper;
import org.apache.camel.support.OrderedComparator;
import org.apache.camel.support.ReactiveHelper;
import org.apache.camel.support.UnitOfWorkHelper;
import org.apache.camel.util.StopWatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Internal {@link Processor} that Camel routing engine used during routing for cross cutting functionality such as:
 * <ul>
 *     <li>Execute {@link UnitOfWork}</li>
 *     <li>Keeping track which route currently is being routed</li>
 *     <li>Execute {@link RoutePolicy}</li>
 *     <li>Gather JMX performance statics</li>
 *     <li>Tracing</li>
 *     <li>Debugging</li>
 *     <li>Message History</li>
 *     <li>Stream Caching</li>
 *     <li>{@link Transformer}</li>
 * </ul>
 * ... and more.
 * <p/>
 * This implementation executes this cross cutting functionality as a {@link CamelInternalProcessorAdvice} advice (before and after advice)
 * by executing the {@link CamelInternalProcessorAdvice#before(org.apache.camel.Exchange)} and
 * {@link CamelInternalProcessorAdvice#after(org.apache.camel.Exchange, Object)} callbacks in correct order during routing.
 * This reduces number of stack frames needed during routing, and reduce the number of lines in stacktraces, as well
 * makes debugging the routing engine easier for end users.
 * <p/>
 * <b>Debugging tips:</b> Camel end users whom want to debug their Camel applications with the Camel source code, then make sure to
 * read the source code of this class about the debugging tips, which you can find in the
 * {@link #process(org.apache.camel.Exchange, org.apache.camel.AsyncCallback)} method.
 * <p/>
 * The added advices can implement {@link Ordered} to control in which order the advices are executed.
 */
public class CamelInternalProcessor extends DelegateAsyncProcessor {

    private final List<CamelInternalProcessorAdvice<?>> advices = new ArrayList<>();

    public CamelInternalProcessor() {
    }

    public CamelInternalProcessor(Processor processor) {
        super(processor);
    }

    /**
     * Adds an {@link CamelInternalProcessorAdvice} advice to the list of advices to execute by this internal processor.
     *
     * @param advice  the advice to add
     */
    public void addAdvice(CamelInternalProcessorAdvice<?> advice) {
        advices.add(advice);
        // ensure advices are sorted so they are in the order we want
        advices.sort(OrderedComparator.get());
    }

    /**
     * Gets the advice with the given type.
     *
     * @param type  the type of the advice
     * @return the advice if exists, or <tt>null</tt> if no advices has been added with the given type.
     */
    public <T> T getAdvice(Class<T> type) {
        for (CamelInternalProcessorAdvice task : advices) {
            Object advice = CamelInternalProcessorAdvice.unwrap(task);
            if (type.isInstance(advice)) {
                return type.cast(advice);
            }
        }
        return null;
    }

    @Override
    public boolean process(Exchange exchange, AsyncCallback ocallback) {
        // ----------------------------------------------------------
        // CAMEL END USER - READ ME FOR DEBUGGING TIPS
        // ----------------------------------------------------------
        // If you want to debug the Camel routing engine, then there is a lot of internal functionality
        // the routing engine executes during routing messages. You can skip debugging this internal
        // functionality and instead debug where the routing engine continues routing to the next node
        // in the routes. The CamelInternalProcessor is a vital part of the routing engine, as its
        // being used in between the nodes. As an end user you can just debug the code in this class
        // in between the:
        //   CAMEL END USER - DEBUG ME HERE +++ START +++
        //   CAMEL END USER - DEBUG ME HERE +++ END +++
        // you can see in the code below.
        // ----------------------------------------------------------

        if (processor == null || !continueProcessing(exchange)) {
            // no processor or we should not continue then we are done
            ocallback.done(true);
            return true;
        }

        // optimise to use object array for states
        final Object[] states = new Object[advices.size()];
        // optimise for loop using index access to avoid creating iterator object
        for (int i = 0; i < advices.size(); i++) {
            CamelInternalProcessorAdvice task = advices.get(i);
            try {
                Object state = task.before(exchange);
                states[i] = state;
            } catch (Throwable e) {
                exchange.setException(e);
                ocallback.done(true);
                return true;
            }
        }

        // create internal callback which will execute the advices in reverse order when done
        AsyncCallback callback = doneSync -> {
            try {
                for (int i = advices.size() - 1; i >= 0; i--) {
                    CamelInternalProcessorAdvice task = advices.get(i);
                    Object state = states[i];
                    try {
                        task.after(exchange, state);
                    } catch (Throwable e) {
                        exchange.setException(e);
                        // allow all advices to complete even if there was an exception
                    }
                }
            } finally {
                // ----------------------------------------------------------
                // CAMEL END USER - DEBUG ME HERE +++ START +++
                // ----------------------------------------------------------
                // callback must be called
                ReactiveHelper.callback(ocallback);
                // ----------------------------------------------------------
                // CAMEL END USER - DEBUG ME HERE +++ END +++
                // ----------------------------------------------------------
            }
        };

        if (exchange.isTransacted()) {
            // must be synchronized for transacted exchanges
            if (log.isTraceEnabled()) {
                if (exchange.isTransacted()) {
                    log.trace("Transacted Exchange must be routed synchronously for exchangeId: {} -> {}", exchange.getExchangeId(), exchange);
                } else {
                    log.trace("Synchronous UnitOfWork Exchange must be routed synchronously for exchangeId: {} -> {}", exchange.getExchangeId(), exchange);
                }
            }
            // ----------------------------------------------------------
            // CAMEL END USER - DEBUG ME HERE +++ START +++
            // ----------------------------------------------------------
            try {
                processor.process(exchange);
            } catch (Throwable e) {
                exchange.setException(e);
            }
            // ----------------------------------------------------------
            // CAMEL END USER - DEBUG ME HERE +++ END +++
            // ----------------------------------------------------------
            callback.done(true);
            return true;
        } else {
            final UnitOfWork uow = exchange.getUnitOfWork();

            // allow unit of work to wrap callback in case it need to do some special work
            // for example the MDCUnitOfWork
            AsyncCallback async = callback;
            if (uow != null) {
                async = uow.beforeProcess(processor, exchange, callback);
            }

            // ----------------------------------------------------------
            // CAMEL END USER - DEBUG ME HERE +++ START +++
            // ----------------------------------------------------------
            if (log.isTraceEnabled()) {
                log.trace("Processing exchange for exchangeId: {} -> {}", exchange.getExchangeId(), exchange);
            }
            processor.process(exchange, async);
            // ----------------------------------------------------------
            // CAMEL END USER - DEBUG ME HERE +++ END +++
            // ----------------------------------------------------------

            ReactiveHelper.schedule(() -> {
                // execute any after processor work (in current thread, not in the callback)
                if (uow != null) {
                    uow.afterProcess(processor, exchange, callback, false);
                }

                if (log.isTraceEnabled()) {
                    log.trace("Exchange processed and is continued routed asynchronously for exchangeId: {} -> {}",
                             exchange.getExchangeId(), exchange);
                }
            }, "CamelInternalProcessor - UnitOfWork - afterProcess - " + processor + " - " + exchange.getExchangeId());
            return false;
        }
    }

    @Override
    public String toString() {
        return processor != null ? processor.toString() : super.toString();
    }

    /**
     * Strategy to determine if we should continue processing the {@link Exchange}.
     */
    protected boolean continueProcessing(Exchange exchange) {
        Object stop = exchange.getProperty(Exchange.ROUTE_STOP);
        if (stop != null) {
            boolean doStop = exchange.getContext().getTypeConverter().convertTo(Boolean.class, stop);
            if (doStop) {
                log.debug("Exchange is marked to stop routing: {}", exchange);
                return false;
            }
        }

        // determine if we can still run, or the camel context is forcing a shutdown
        boolean forceShutdown = exchange.getContext().getShutdownStrategy().forceShutdown(this);
        if (forceShutdown) {
            String msg = "Run not allowed as ShutdownStrategy is forcing shutting down, will reject executing exchange: " + exchange;
            log.debug(msg);
            if (exchange.getException() == null) {
                exchange.setException(new RejectedExecutionException(msg));
            }
            return false;
        }

        // yes we can continue
        return true;
    }

    /**
     * Advice to invoke callbacks for before and after routing.
     */
    public static class RouteLifecycleAdvice implements CamelInternalProcessorAdvice<Object> {

        private Route route;

        public void setRoute(Route route) {
            this.route = route;
        }

        @Override
        public Object before(Exchange exchange) throws Exception {
            UnitOfWork uow = exchange.getUnitOfWork();
            if (uow != null) {
                uow.beforeRoute(exchange, route);
            }
            return null;
        }

        @Override
        public void after(Exchange exchange, Object object) throws Exception {
            UnitOfWork uow = exchange.getUnitOfWork();
            if (uow != null) {
                uow.afterRoute(exchange, route);
            }
        }
    }

    /**
     * Advice to keep the {@link InflightRepository} up to date.
     */
    public static class RouteInflightRepositoryAdvice implements CamelInternalProcessorAdvice {

        private final InflightRepository inflightRepository;
        private final String id;

        public RouteInflightRepositoryAdvice(InflightRepository inflightRepository, String id) {
            this.inflightRepository = inflightRepository;
            this.id = id;
        }

        @Override
        public Object before(Exchange exchange) throws Exception {
            inflightRepository.add(exchange, id);
            return null;
        }

        @Override
        public void after(Exchange exchange, Object state) throws Exception {
            inflightRepository.remove(exchange, id);
        }
    }

    /**
     * Advice to execute any {@link RoutePolicy} a route may have been configured with.
     */
    public static class RoutePolicyAdvice implements CamelInternalProcessorAdvice {

        private final Logger log = LoggerFactory.getLogger(getClass());
        private final List<RoutePolicy> routePolicies;
        private Route route;

        public RoutePolicyAdvice(List<RoutePolicy> routePolicies) {
            this.routePolicies = routePolicies;
        }

        public void setRoute(Route route) {
            this.route = route;
        }

        /**
         * Strategy to determine if this policy is allowed to run
         *
         * @param policy the policy
         * @return <tt>true</tt> to run
         */
        protected boolean isRoutePolicyRunAllowed(RoutePolicy policy) {
            if (policy instanceof StatefulService) {
                StatefulService ss = (StatefulService) policy;
                return ss.isRunAllowed();
            }
            return true;
        }

        @Override
        public Object before(Exchange exchange) throws Exception {
            // invoke begin
            for (RoutePolicy policy : routePolicies) {
                try {
                    if (isRoutePolicyRunAllowed(policy)) {
                        policy.onExchangeBegin(route, exchange);
                    }
                } catch (Exception e) {
                    log.warn("Error occurred during onExchangeBegin on RoutePolicy: " + policy
                            + ". This exception will be ignored", e);
                }
            }
            return null;
        }

        @Override
        public void after(Exchange exchange, Object data) throws Exception {
            // do not invoke it if Camel is stopping as we don't want
            // the policy to start a consumer during Camel is stopping
            if (isCamelStopping(exchange.getContext())) {
                return;
            }

            for (RoutePolicy policy : routePolicies) {
                try {
                    if (isRoutePolicyRunAllowed(policy)) {
                        policy.onExchangeDone(route, exchange);
                    }
                } catch (Exception e) {
                    log.warn("Error occurred during onExchangeDone on RoutePolicy: " + policy
                            + ". This exception will be ignored", e);
                }
            }
        }

        private static boolean isCamelStopping(CamelContext context) {
            if (context instanceof StatefulService) {
                StatefulService ss = (StatefulService) context;
                return ss.isStopping() || ss.isStopped();
            }
            return false;
        }
    }

    /**
     * Advice to execute the {@link BacklogTracer} if enabled.
     */
    public static final class BacklogTracerAdvice implements CamelInternalProcessorAdvice, Ordered {

        private final BacklogTracer backlogTracer;
        private final ProcessorDefinition<?> processorDefinition;
        private final ProcessorDefinition<?> routeDefinition;
        private final boolean first;

        public BacklogTracerAdvice(BacklogTracer backlogTracer, ProcessorDefinition<?> processorDefinition,
                                   ProcessorDefinition<?> routeDefinition, boolean first) {
            this.backlogTracer = backlogTracer;
            this.processorDefinition = processorDefinition;
            this.routeDefinition = routeDefinition;
            this.first = first;
        }

        @Override
        public Object before(Exchange exchange) throws Exception {
            if (backlogTracer.shouldTrace(processorDefinition, exchange)) {
                Date timestamp = new Date();
                String toNode = processorDefinition.getId();
                String exchangeId = exchange.getExchangeId();
                String messageAsXml = MessageHelper.dumpAsXml(exchange.getIn(), true, 4,
                        backlogTracer.isBodyIncludeStreams(), backlogTracer.isBodyIncludeFiles(), backlogTracer.getBodyMaxChars());

                // if first we should add a pseudo trace message as well, so we have a starting message (eg from the route)
                String routeId = routeDefinition != null ? routeDefinition.getId() : null;
                if (first) {
                    Date created = exchange.getProperty(Exchange.CREATED_TIMESTAMP, timestamp, Date.class);
                    DefaultBacklogTracerEventMessage pseudo = new DefaultBacklogTracerEventMessage(backlogTracer.incrementTraceCounter(), created, routeId, null, exchangeId, messageAsXml);
                    backlogTracer.traceEvent(pseudo);
                }
                DefaultBacklogTracerEventMessage event = new DefaultBacklogTracerEventMessage(backlogTracer.incrementTraceCounter(), timestamp, routeId, toNode, exchangeId, messageAsXml);
                backlogTracer.traceEvent(event);
            }

            return null;
        }

        @Override
        public void after(Exchange exchange, Object data) throws Exception {
            // noop
        }

        @Override
        public int getOrder() {
            // we want tracer just before calling the processor
            return Ordered.LOWEST - 1;
        }

    }

    /**
     * Advice to execute the {@link org.apache.camel.processor.interceptor.BacklogDebugger} if enabled.
     */
    public static final class BacklogDebuggerAdvice implements CamelInternalProcessorAdvice<StopWatch>, Ordered {

        private final BacklogDebugger backlogDebugger;
        private final Processor target;
        private final ProcessorDefinition<?> definition;
        private final String nodeId;

        public BacklogDebuggerAdvice(BacklogDebugger backlogDebugger, Processor target, ProcessorDefinition<?> definition) {
            this.backlogDebugger = backlogDebugger;
            this.target = target;
            this.definition = definition;
            this.nodeId = definition.getId();
        }

        @Override
        public StopWatch before(Exchange exchange) throws Exception {
            if (backlogDebugger.isEnabled() && (backlogDebugger.hasBreakpoint(nodeId) || backlogDebugger.isSingleStepMode())) {
                StopWatch watch = new StopWatch();
                backlogDebugger.beforeProcess(exchange, target, definition);
                return watch;
            } else {
                return null;
            }
        }

        @Override
        public void after(Exchange exchange, StopWatch stopWatch) throws Exception {
            if (stopWatch != null) {
                backlogDebugger.afterProcess(exchange, target, definition, stopWatch.taken());
            }
        }

        @Override
        public int getOrder() {
            // we want debugger just before calling the processor
            return Ordered.LOWEST;
        }
    }

    /**
     * Advice to inject new {@link UnitOfWork} to the {@link Exchange} if needed, and as well to ensure
     * the {@link UnitOfWork} is done and stopped.
     */
    public static class UnitOfWorkProcessorAdvice implements CamelInternalProcessorAdvice<UnitOfWork> {

        private final RouteContext routeContext;
        private String routeId;

        public UnitOfWorkProcessorAdvice(RouteContext routeContext) {
            this.routeContext = routeContext;
            if (routeContext != null) {
                RouteDefinition definition = (RouteDefinition) routeContext.getRoute();
                this.routeId = definition.idOrCreate(routeContext.getCamelContext().getNodeIdFactory());
            }
        }

        @Override
        public UnitOfWork before(Exchange exchange) throws Exception {
            // if the exchange doesn't have from route id set, then set it if it originated
            // from this unit of work
            if (routeContext != null && exchange.getFromRouteId() == null) {
                if (routeId == null) {
                    RouteDefinition definition = (RouteDefinition) routeContext.getRoute();
                    routeId = definition.idOrCreate(routeContext.getCamelContext().getNodeIdFactory());
                }
                exchange.setFromRouteId(routeId);
            }

            // only return UnitOfWork if we created a new as then its us that handle the lifecycle to done the created UoW
            UnitOfWork created = null;

            if (exchange.getUnitOfWork() == null) {
                // If there is no existing UoW, then we should start one and
                // terminate it once processing is completed for the exchange.
                created = createUnitOfWork(exchange);
                exchange.setUnitOfWork(created);
                created.start();
            }

            // for any exchange we should push/pop route context so we can keep track of which route we are routing
            if (routeContext != null) {
                UnitOfWork existing = exchange.getUnitOfWork();
                if (existing != null) {
                    existing.pushRouteContext(routeContext);
                }
            }

            return created;
        }

        @Override
        public void after(Exchange exchange, UnitOfWork uow) throws Exception {
            UnitOfWork existing = exchange.getUnitOfWork();

            // execute done on uow if we created it, and the consumer is not doing it
            if (uow != null) {
                UnitOfWorkHelper.doneUow(uow, exchange);
            }

            // after UoW is done lets pop the route context which must be done on every existing UoW
            if (routeContext != null && existing != null) {
                existing.popRouteContext();
            }
        }

        protected UnitOfWork createUnitOfWork(Exchange exchange) {
            return exchange.getContext().getUnitOfWorkFactory().createUnitOfWork(exchange);
        }

    }

    /**
     * Advice when an EIP uses the <tt>shareUnitOfWork</tt> functionality.
     */
    public static class ChildUnitOfWorkProcessorAdvice extends UnitOfWorkProcessorAdvice {

        private final UnitOfWork parent;

        public ChildUnitOfWorkProcessorAdvice(RouteContext routeContext, UnitOfWork parent) {
            super(routeContext);
            this.parent = parent;
        }

        @Override
        protected UnitOfWork createUnitOfWork(Exchange exchange) {
            // let the parent create a child unit of work to be used
            return parent.createChildUnitOfWork(exchange);
        }

    }

    /**
     * Advice when an EIP uses the <tt>shareUnitOfWork</tt> functionality.
     */
    public static class SubUnitOfWorkProcessorAdvice implements CamelInternalProcessorAdvice<UnitOfWork> {

        @Override
        public UnitOfWork before(Exchange exchange) throws Exception {
            // begin savepoint
            exchange.getUnitOfWork().beginSubUnitOfWork(exchange);
            return exchange.getUnitOfWork();
        }

        @Override
        public void after(Exchange exchange, UnitOfWork unitOfWork) throws Exception {
            // end sub unit of work
            unitOfWork.endSubUnitOfWork(exchange);
        }
    }

    /**
     * Advice when Message History has been enabled.
     */
    @SuppressWarnings("unchecked")
    public static class MessageHistoryAdvice implements CamelInternalProcessorAdvice<MessageHistory> {

        private final MessageHistoryFactory factory;
        private final ProcessorDefinition<?> definition;
        private final String routeId;

        public MessageHistoryAdvice(MessageHistoryFactory factory, ProcessorDefinition<?> definition) {
            this.factory = factory;
            this.definition = definition;
            this.routeId = ProcessorDefinitionHelper.getRouteId(definition);
        }

        @Override
        public MessageHistory before(Exchange exchange) throws Exception {
            List<MessageHistory> list = exchange.getProperty(Exchange.MESSAGE_HISTORY, List.class);
            if (list == null) {
                list = new LinkedList<>();
                exchange.setProperty(Exchange.MESSAGE_HISTORY, list);
            }

            // we may be routing outside a route in an onException or interceptor and if so then grab
            // route id from the exchange UoW state
            String targetRouteId = this.routeId;
            if (targetRouteId == null) {
                UnitOfWork uow = exchange.getUnitOfWork();
                if (uow != null && uow.getRouteContext() != null) {
                    targetRouteId = uow.getRouteContext().getRoute().getId();
                }
            }

            MessageHistory history = factory.newMessageHistory(targetRouteId, definition, System.currentTimeMillis());
            list.add(history);
            return history;
        }

        @Override
        public void after(Exchange exchange, MessageHistory history) throws Exception {
            if (history != null) {
                history.nodeProcessingDone();
            }
        }
    }

    /**
     * Advice for {@link org.apache.camel.spi.StreamCachingStrategy}
     */
    public static class StreamCachingAdvice implements CamelInternalProcessorAdvice<StreamCache>, Ordered {

        private final StreamCachingStrategy strategy;

        public StreamCachingAdvice(StreamCachingStrategy strategy) {
            this.strategy = strategy;
        }

        @Override
        public StreamCache before(Exchange exchange) throws Exception {
            // check if body is already cached
            Object body = exchange.getIn().getBody();
            if (body == null) {
                return null;
            } else if (body instanceof StreamCache) {
                StreamCache sc = (StreamCache) body;
                // reset so the cache is ready to be used before processing
                sc.reset();
                return sc;
            }
            // cache the body and if we could do that replace it as the new body
            StreamCache sc = strategy.cache(exchange);
            if (sc != null) {
                exchange.getIn().setBody(sc);
            }
            return sc;
        }

        @Override
        public void after(Exchange exchange, StreamCache sc) throws Exception {
            Object body;
            if (exchange.hasOut()) {
                body = exchange.getOut().getBody();
            } else {
                body = exchange.getIn().getBody();
            }
            if (body instanceof StreamCache) {
                // reset so the cache is ready to be reused after processing
                ((StreamCache) body).reset();
            }
        }

        @Override
        public int getOrder() {
            // we want stream caching first
            return Ordered.HIGHEST;
        }
    }

    /**
     * Advice for delaying
     */
    public static class DelayerAdvice implements CamelInternalProcessorAdvice {

        private final Logger log = LoggerFactory.getLogger(getClass());
        private final long delay;

        public DelayerAdvice(long delay) {
            this.delay = delay;
        }

        @Override
        public Object before(Exchange exchange) throws Exception {
            try {
                log.trace("Sleeping for: {} millis", delay);
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                log.debug("Sleep interrupted");
                Thread.currentThread().interrupt();
                throw e;
            }
            return null;
        }

        @Override
        public void after(Exchange exchange, Object data) throws Exception {
            // noop
        }
    }
}
