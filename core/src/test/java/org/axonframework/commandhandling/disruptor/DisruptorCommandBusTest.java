/*
 * Copyright (c) 2010-2012. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.commandhandling.disruptor;

import com.lmax.disruptor.SleepingWaitStrategy;
import com.lmax.disruptor.dsl.ProducerType;
import org.axonframework.commandhandling.CommandCallback;
import org.axonframework.commandhandling.CommandMessage;
import org.axonframework.commandhandling.GenericCommandMessage;
import org.axonframework.commandhandling.NoHandlerForCommandException;
import org.axonframework.commandhandling.annotation.TargetAggregateIdentifier;
import org.axonframework.commandhandling.callbacks.FutureCallback;
import org.axonframework.eventhandling.EventBus;
import org.axonframework.eventhandling.EventMessage;
import org.axonframework.eventhandling.GenericEventMessage;
import org.axonframework.eventsourcing.*;
import org.axonframework.eventstore.EventStore;
import org.axonframework.eventstore.EventStreamNotFoundException;
import org.axonframework.messaging.InterceptorChain;
import org.axonframework.messaging.MessageDispatchInterceptor;
import org.axonframework.messaging.MessageHandler;
import org.axonframework.messaging.MessageHandlerInterceptor;
import org.axonframework.messaging.unitofwork.RollbackConfigurationType;
import org.axonframework.messaging.unitofwork.TransactionManager;
import org.axonframework.messaging.unitofwork.UnitOfWork;
import org.axonframework.repository.Repository;
import org.axonframework.testutils.MockException;
import org.hamcrest.Description;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.internal.stubbing.answers.ReturnsArgumentAt;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

import static java.util.Arrays.asList;
import static junit.framework.TestCase.*;
import static org.mockito.Mockito.*;

/**
 * @author Allard Buijze
 */
//todo fix test
@Ignore
public class DisruptorCommandBusTest {

    private static final int COMMAND_COUNT = 100 * 1000;
    private StubHandler stubHandler;
    private InMemoryEventStore inMemoryEventStore;
    private DisruptorCommandBus testSubject;
    private String aggregateIdentifier;
    private TransactionManager mockTransactionManager;

    @Before
    public void setUp() throws Exception {
        aggregateIdentifier = UUID.randomUUID().toString();
        stubHandler = new StubHandler();
        inMemoryEventStore = new InMemoryEventStore();

        inMemoryEventStore.appendEvents(Collections.singletonList(
                new GenericDomainEventMessage<>(aggregateIdentifier, 0, new StubDomainEvent())));
    }

    @After
    public void tearDown() {
        testSubject.stop();
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testCallbackInvokedBeforeUnitOfWorkCleanup() throws Exception {
        MessageHandlerInterceptor mockHandlerInterceptor = mock(MessageHandlerInterceptor.class);
        MessageDispatchInterceptor mockDispatchInterceptor = mock(MessageDispatchInterceptor.class);
        when(mockDispatchInterceptor.handle(isA(CommandMessage.class))).thenAnswer(new Parameter(0));
        ExecutorService customExecutor = Executors.newCachedThreadPool();
        testSubject = new DisruptorCommandBus(
                inMemoryEventStore,
                new DisruptorConfiguration().setInvokerInterceptors(Collections.singletonList(mockHandlerInterceptor))
                                            .setDispatchInterceptors(Collections.singletonList(mockDispatchInterceptor))
                                            .setBufferSize(8)
                                            .setProducerType(ProducerType.SINGLE)
                                            .setWaitStrategy(new SleepingWaitStrategy())
                                            .setExecutor(customExecutor)
                                            .setInvokerThreadCount(2)
                                            .setPublisherThreadCount(3)
        );
        testSubject.subscribe(StubCommand.class.getName(), stubHandler);
        stubHandler.setRepository(testSubject
                .createRepository(new GenericAggregateFactory<>(StubAggregate.class)));
        Consumer<UnitOfWork> mockPrepareCommitConsumer = mock(Consumer.class);
        Consumer<UnitOfWork> mockAfterCommitConsumer = mock(Consumer.class);
        Consumer<UnitOfWork> mockCleanUpConsumer = mock(Consumer.class);
        when(mockHandlerInterceptor.handle(any(CommandMessage.class), any(UnitOfWork.class),
                                           any(InterceptorChain.class)))
                .thenAnswer(invocation -> {
                    final UnitOfWork unitOfWork = (UnitOfWork) invocation.getArguments()[1];
                    unitOfWork.onPrepareCommit(mockPrepareCommitConsumer);
                    unitOfWork.afterCommit(mockAfterCommitConsumer);
                    unitOfWork.onCleanup(mockCleanUpConsumer);
                    return ((InterceptorChain) invocation.getArguments()[2]).proceed();
                });
        CommandMessage<StubCommand> command = new GenericCommandMessage<>(
                new StubCommand(aggregateIdentifier));
        CommandCallback mockCallback = mock(CommandCallback.class);
        testSubject.dispatch(command, mockCallback);

        testSubject.stop();
        assertFalse(customExecutor.awaitTermination(250, TimeUnit.MILLISECONDS));
        customExecutor.shutdown();
        assertTrue(customExecutor.awaitTermination(5, TimeUnit.SECONDS));
        InOrder inOrder = inOrder(mockDispatchInterceptor,
                                  mockHandlerInterceptor,
                                  mockPrepareCommitConsumer,
                                  mockAfterCommitConsumer,
                                  mockCleanUpConsumer,
                                  mockCallback);
        inOrder.verify(mockDispatchInterceptor).handle(isA(CommandMessage.class));
        inOrder.verify(mockHandlerInterceptor).handle(any(CommandMessage.class),
                                                      any(UnitOfWork.class),
                                                      any(InterceptorChain.class));
        inOrder.verify(mockPrepareCommitConsumer).accept(isA(UnitOfWork.class));
        inOrder.verify(mockAfterCommitConsumer).accept(isA(UnitOfWork.class));
        inOrder.verify(mockCleanUpConsumer).accept(isA(UnitOfWork.class));

        verify(mockCallback).onSuccess(eq(command), any());
    }

    /* see AXON-323: http://issues.axonframework.org/youtrack/issue/AXON-323 */
    @Test
    public void testEventsPublishedWithoutAggregateArePassedToListener() throws Exception {
        final EventBus eventBus = mock(EventBus.class);
        testSubject = new DisruptorCommandBus(inMemoryEventStore, new DisruptorConfiguration()
                                                      .setInvokerInterceptors(
                                                              Collections.<MessageHandlerInterceptor<CommandMessage<?>>>singletonList(
                                                                      (commandMessage, unitOfWork, interceptorChain)
                                                                              -> interceptorChain.proceed())));
        testSubject.subscribe(String.class.getName(), (commandMessage, unitOfWork) -> {
            eventBus.publish(GenericEventMessage.asEventMessage("ok"));
            return null;
        });

        final FutureCallback<String, Void> callback = new FutureCallback<>();
        testSubject.dispatch(GenericCommandMessage.asCommandMessage("test"), callback);

        callback.awaitCompletion(5, TimeUnit.SECONDS);

        verify(eventBus).publish(argThat(new org.hamcrest.TypeSafeMatcher<EventMessage>() {
            @Override
            public void describeTo(Description description) {
                description.appendText("an event with meta data");
            }

            @Override
            protected boolean matchesSafely(EventMessage item) {
                return "value".equals(item.getMetaData().get("meta-key"));
            }
        }));
    }

    @Test
    public void testPublishUnsupportedCommand() throws Exception {
        ExecutorService customExecutor = Executors.newCachedThreadPool();
        testSubject = new DisruptorCommandBus(
                inMemoryEventStore,
                new DisruptorConfiguration()
                        .setBufferSize(8)
                        .setProducerType(ProducerType.SINGLE)
                        .setWaitStrategy(new SleepingWaitStrategy())
                        .setExecutor(customExecutor)
                        .setInvokerThreadCount(2)
                        .setPublisherThreadCount(3)
        );
        try {
            testSubject.dispatch(GenericCommandMessage.asCommandMessage("Test"));
            fail("Expected exception");
        } catch (NoHandlerForCommandException e) {
            assertTrue(e.getMessage().contains(String.class.getSimpleName()));
        } finally {
            customExecutor.shutdownNow();
        }
    }

    @Test
    public void testEventStreamsDecoratedOnReadAndWrite() throws InterruptedException {
        ExecutorService customExecutor = Executors.newCachedThreadPool();
        testSubject = new DisruptorCommandBus(
                inMemoryEventStore,
                new DisruptorConfiguration().setBufferSize(8)
                                            .setProducerType(ProducerType.SINGLE)
                                            .setWaitStrategy(new SleepingWaitStrategy())
                                            .setExecutor(customExecutor)
                                            .setInvokerThreadCount(2)
                                            .setPublisherThreadCount(3)
        );
        testSubject.subscribe(StubCommand.class.getName(), stubHandler);
        final EventStreamDecorator mockDecorator = mock(EventStreamDecorator.class);
        when(mockDecorator.decorateForAppend(any(), any())).thenAnswer(new ReturnsArgumentAt(1));
        when(mockDecorator.decorateForRead(any(), any())).thenAnswer(new ReturnsArgumentAt(1));

        stubHandler.setRepository(testSubject
                                          .createRepository(new GenericAggregateFactory<>(StubAggregate.class),
                                                            mockDecorator));

        CommandMessage<StubCommand> command = new GenericCommandMessage<>(
                new StubCommand(aggregateIdentifier));
        CommandCallback mockCallback = mock(CommandCallback.class);
        testSubject.dispatch(command, mockCallback);
        testSubject.dispatch(command);

        testSubject.stop();
        assertFalse(customExecutor.awaitTermination(250, TimeUnit.MILLISECONDS));
        customExecutor.shutdown();
        assertTrue(customExecutor.awaitTermination(5, TimeUnit.SECONDS));

        // invoked only once, because the second time, the aggregate comes from the 1st level cache
        verify(mockDecorator).decorateForRead(eq(aggregateIdentifier),
                                              isA(DomainEventStream.class));
        verify(mockDecorator, times(2)).decorateForAppend(isA(EventSourcedAggregateRoot.class),
                                                          isA(List.class));
    }

    @Test
    public void testEventPublicationExecutedWithinTransaction() throws Exception {
        MessageHandlerInterceptor mockInterceptor = mock(MessageHandlerInterceptor.class);
        ExecutorService customExecutor = Executors.newCachedThreadPool();
        mockTransactionManager = mock(TransactionManager.class);
        when(mockTransactionManager.startTransaction()).thenReturn(new Object());

        dispatchCommands(mockInterceptor, customExecutor, new GenericCommandMessage<>(
                new ErrorCommand(aggregateIdentifier)));

        assertFalse(customExecutor.awaitTermination(250, TimeUnit.MILLISECONDS));
        customExecutor.shutdown();
        assertTrue(customExecutor.awaitTermination(5, TimeUnit.SECONDS));

        verify(mockTransactionManager, times(1001)).startTransaction();
        verify(mockTransactionManager, times(991)).commitTransaction(any());
        verify(mockTransactionManager, times(10)).rollbackTransaction(any());
    }

    @SuppressWarnings("unchecked")
    @Test(timeout = 10000)
    public void testAggregatesBlacklistedAndRecoveredOnError_WithAutoReschedule() throws Exception {
        MessageHandlerInterceptor mockInterceptor = mock(MessageHandlerInterceptor.class);
        ExecutorService customExecutor = Executors.newCachedThreadPool();
        CommandCallback mockCallback = dispatchCommands(mockInterceptor,
                                                        customExecutor,
                                                        new GenericCommandMessage<>(
                                                                new ErrorCommand(aggregateIdentifier))
        );
        assertFalse(customExecutor.awaitTermination(250, TimeUnit.MILLISECONDS));
        customExecutor.shutdown();
        assertTrue(customExecutor.awaitTermination(5, TimeUnit.SECONDS));
        verify(mockCallback, times(990)).onSuccess(any(), any());
        verify(mockCallback, times(10)).onFailure(any(), isA(RuntimeException.class));
    }

    @SuppressWarnings("unchecked")
    @Test(timeout = 10000)
    public void testAggregatesBlacklistedAndRecoveredOnError_WithoutReschedule() throws Exception {
        MessageHandlerInterceptor mockInterceptor = mock(MessageHandlerInterceptor.class);
        ExecutorService customExecutor = Executors.newCachedThreadPool();

        CommandCallback mockCallback = dispatchCommands(mockInterceptor,
                                                        customExecutor,
                                                        new GenericCommandMessage<>(
                                                                new ErrorCommand(aggregateIdentifier))
        );

        assertFalse(customExecutor.awaitTermination(250, TimeUnit.MILLISECONDS));
        customExecutor.shutdown();
        assertTrue(customExecutor.awaitTermination(5, TimeUnit.SECONDS));
        verify(mockCallback, times(990)).onSuccess(any(), any());
        verify(mockCallback, times(10)).onFailure(any(), isA(RuntimeException.class));
    }

    private CommandCallback dispatchCommands(MessageHandlerInterceptor mockInterceptor, ExecutorService customExecutor,
                                             GenericCommandMessage<ErrorCommand> errorCommand)
            throws Exception {
        inMemoryEventStore.storedEvents.clear();
        testSubject = new DisruptorCommandBus(
                inMemoryEventStore,
                new DisruptorConfiguration().setInvokerInterceptors(asList(mockInterceptor))
                                            .setBufferSize(8)
                                            .setProducerType(ProducerType.MULTI)
                                            .setWaitStrategy(new SleepingWaitStrategy())
                                            .setExecutor(customExecutor)
                                            .setRollbackConfiguration(RollbackConfigurationType.ANY_THROWABLE)
                                            .setInvokerThreadCount(2)
                                            .setPublisherThreadCount(3)
                                            .setTransactionManager(mockTransactionManager)
        );
        testSubject.subscribe(StubCommand.class.getName(), stubHandler);
        testSubject.subscribe(CreateCommand.class.getName(), stubHandler);
        testSubject.subscribe(ErrorCommand.class.getName(), stubHandler);
        stubHandler.setRepository(testSubject
                .createRepository(new GenericAggregateFactory<>(StubAggregate.class)));
        when(mockInterceptor.handle(any(CommandMessage.class), any(UnitOfWork.class), any(InterceptorChain.class)))
                .thenAnswer(invocation -> ((InterceptorChain) invocation.getArguments()[2]).proceed());
        testSubject.dispatch(new GenericCommandMessage<>(new CreateCommand(aggregateIdentifier)));
        CommandCallback mockCallback = mock(CommandCallback.class);
        for (int t = 0; t < 1000; t++) {
            CommandMessage command;
            if (t % 100 == 10) {
                command = errorCommand;
            } else {
                command = new GenericCommandMessage<>(new StubCommand(aggregateIdentifier));
            }
            testSubject.dispatch(command, mockCallback);
        }

        testSubject.stop();
        return mockCallback;
    }

    @Test
    public void testCreateAggregate() {
        inMemoryEventStore.storedEvents.clear();
        testSubject = new DisruptorCommandBus(inMemoryEventStore,
                                              new DisruptorConfiguration()
                                                      .setBufferSize(8)
                                                      .setProducerType(ProducerType.SINGLE)
                                                      .setWaitStrategy(new SleepingWaitStrategy())
                                                      .setInvokerThreadCount(2)
                                                      .setPublisherThreadCount(3)
        );
        testSubject.subscribe(StubCommand.class.getName(), stubHandler);
        testSubject.subscribe(CreateCommand.class.getName(), stubHandler);
        testSubject.subscribe(ErrorCommand.class.getName(), stubHandler);
        stubHandler.setRepository(testSubject.createRepository(new GenericAggregateFactory<>(StubAggregate.class)));



        testSubject.dispatch(new GenericCommandMessage<>(new CreateCommand(aggregateIdentifier)));

        testSubject.stop();

        DomainEventMessage lastEvent = inMemoryEventStore.storedEvents.get(aggregateIdentifier);

        // we expect 2 events, 1 from aggregate constructor, one from doSomething method invocation
        assertEquals(1, lastEvent.getSequenceNumber());
        assertEquals(aggregateIdentifier, lastEvent.getAggregateIdentifier());
    }

    @Test(expected = IllegalStateException.class)
    public void testCommandRejectedAfterShutdown() throws InterruptedException {
        testSubject = new DisruptorCommandBus(inMemoryEventStore);
        testSubject.subscribe(StubCommand.class.getName(), stubHandler);
        stubHandler.setRepository(testSubject
                                          .createRepository(new GenericAggregateFactory<>(StubAggregate.class)));

        testSubject.stop();
        testSubject.dispatch(new GenericCommandMessage<>(new Object()));
    }

    @Test(timeout = 10000)
    public void testCommandProcessedAndEventsStored() throws InterruptedException {
        testSubject = new DisruptorCommandBus(inMemoryEventStore);
        testSubject.subscribe(StubCommand.class.getName(), stubHandler);
        stubHandler.setRepository(testSubject
                                          .createRepository(new GenericAggregateFactory<>(StubAggregate.class)));

        for (int i = 0; i < COMMAND_COUNT; i++) {
            CommandMessage<StubCommand> command = new GenericCommandMessage<>(
                    new StubCommand(aggregateIdentifier));
            testSubject.dispatch(command);
        }

        inMemoryEventStore.countDownLatch.await(5, TimeUnit.SECONDS);
        assertEquals("Seems that some events are not stored", 0, inMemoryEventStore.countDownLatch.getCount());
    }

    private static class StubAggregate extends AbstractEventSourcedAggregateRoot {

        private static final long serialVersionUID = 8192033940704210095L;

        private String identifier;

        private StubAggregate(String identifier) {
            this.identifier = identifier;
            apply(new SomethingDoneEvent());
        }

        @SuppressWarnings("UnusedDeclaration")
        public StubAggregate() {
        }

        @Override
        public String getIdentifier() {
            return identifier;
        }

        public void doSomething() {
            apply(new SomethingDoneEvent());
        }

        public void createFailingEvent() {
            apply(new FailingEvent());
        }

        @Override
        protected void handle(EventMessage event) {
            identifier = ((DomainEventMessage) event).getAggregateIdentifier();
        }

        @Override
        protected Collection<EventSourcedEntity> getChildEntities() {
            return Collections.emptyList();
        }
    }

    private static class InMemoryEventStore implements EventStore {

        private final Map<String, DomainEventMessage> storedEvents = new ConcurrentHashMap<>();
        private final CountDownLatch countDownLatch = new CountDownLatch((int) (COMMAND_COUNT + 1L));

        @Override
        public void appendEvents(List<DomainEventMessage<?>> events) {
            if (events == null || events.isEmpty()) {
                return;
            }
            String key = events.get(0).getAggregateIdentifier();
            DomainEventMessage<?> lastEvent = null;
            for (DomainEventMessage<?> event : events) {
                countDownLatch.countDown();
                lastEvent = event;
                if (FailingEvent.class.isAssignableFrom(lastEvent.getPayloadType())) {
                    throw new MockException("This is a failing event. EventStore refuses to store that");
                }
            }
            storedEvents.put(key, lastEvent);
        }

        @Override
        public DomainEventStream readEvents(String identifier, long firstSequenceNumber,
                                            long lastSequenceNumber) {
            DomainEventMessage message = storedEvents.get(identifier);
            if (message == null
                    || message.getSequenceNumber() < firstSequenceNumber
                    || message.getSequenceNumber() > lastSequenceNumber) {
                throw new EventStreamNotFoundException(identifier);
            }
            return new SimpleDomainEventStream(Collections.singletonList(message));
        }
    }

    private static class StubCommand {

        @TargetAggregateIdentifier
        private Object aggregateIdentifier;

        public StubCommand(Object aggregateIdentifier) {
            this.aggregateIdentifier = aggregateIdentifier;
        }

        public String getAggregateIdentifier() {
            return aggregateIdentifier.toString();
        }
    }

    private static class ErrorCommand extends StubCommand {

        public ErrorCommand(Object aggregateIdentifier) {
            super(aggregateIdentifier);
        }
    }

    private static class ExceptionCommand extends StubCommand {

        private final Exception exception;

        public ExceptionCommand(Object aggregateIdentifier, Exception exception) {
            super(aggregateIdentifier);
            this.exception = exception;
        }

        public Exception getException() {
            return exception;
        }
    }

    private static class CreateCommand extends StubCommand {

        public CreateCommand(Object aggregateIdentifier) {
            super(aggregateIdentifier);
        }
    }

    private static class StubHandler implements MessageHandler<CommandMessage<?>> {

        private Repository<StubAggregate> repository;

        private StubHandler() {
        }

        @Override
        public Object handle(CommandMessage<?> command, UnitOfWork unitOfWork) throws Exception {
            StubCommand payload = (StubCommand) command.getPayload();
            if (ExceptionCommand.class.isAssignableFrom(command.getPayloadType())) {
                throw ((ExceptionCommand) command.getPayload()).getException();
            } else if (CreateCommand.class.isAssignableFrom(command.getPayloadType())) {
                StubAggregate aggregate = new StubAggregate(payload.getAggregateIdentifier());
                repository.add(aggregate);
                aggregate.doSomething();
            } else {
                StubAggregate aggregate = repository.load(payload.getAggregateIdentifier());
                if (ErrorCommand.class.isAssignableFrom(command.getPayloadType())) {
                    aggregate.createFailingEvent();
                } else {
                    aggregate.doSomething();
                }
            }

            return Void.TYPE;
        }

        public void setRepository(Repository<StubAggregate> repository) {
            this.repository = repository;
        }
    }

    private static class StubDomainEvent {

    }

    /**
     * @author Allard Buijze
     */
    static class FailingEvent {

    }

    private static class Parameter implements Answer<Object> {

        private final int index;

        private Parameter(int index) {
            this.index = index;
        }

        @Override
        public Object answer(InvocationOnMock invocation) throws Exception {
            return invocation.getArguments()[index];
        }
    }
}
