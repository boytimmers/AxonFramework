/*
 * Copyright (c) 2010-2018. Axon Framework
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

package org.axonframework.config;

import org.axonframework.commandhandling.AnnotationCommandHandlerAdapter;
import org.axonframework.commandhandling.CommandBus;
import org.axonframework.commandhandling.SimpleCommandBus;
import org.axonframework.commandhandling.gateway.CommandGateway;
import org.axonframework.commandhandling.gateway.DefaultCommandGateway;
import org.axonframework.commandhandling.model.Repository;
import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.Assert;
import org.axonframework.common.IdentifierFactory;
import org.axonframework.common.Registration;
import org.axonframework.common.jdbc.PersistenceExceptionResolver;
import org.axonframework.common.jpa.EntityManagerProvider;
import org.axonframework.common.transaction.NoTransactionManager;
import org.axonframework.common.transaction.TransactionManager;
import org.axonframework.deadline.DeadlineManager;
import org.axonframework.deadline.SimpleDeadlineManager;
import org.axonframework.eventhandling.EventBus;
import org.axonframework.eventhandling.SimpleEventBus;
import org.axonframework.eventhandling.saga.ResourceInjector;
import org.axonframework.eventhandling.saga.repository.SagaStore;
import org.axonframework.eventhandling.saga.repository.jpa.JpaSagaStore;
import org.axonframework.eventhandling.tokenstore.TokenStore;
import org.axonframework.eventhandling.tokenstore.jpa.JpaTokenStore;
import org.axonframework.eventsourcing.eventstore.EmbeddedEventStore;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.eventsourcing.eventstore.jpa.JpaEventStorageEngine;
import org.axonframework.messaging.Message;
import org.axonframework.messaging.annotation.ClasspathHandlerDefinition;
import org.axonframework.messaging.annotation.ClasspathParameterResolverFactory;
import org.axonframework.messaging.annotation.HandlerDefinition;
import org.axonframework.messaging.annotation.MultiParameterResolverFactory;
import org.axonframework.messaging.annotation.ParameterResolverFactory;
import org.axonframework.messaging.correlation.CorrelationDataProvider;
import org.axonframework.messaging.correlation.MessageOriginProvider;
import org.axonframework.messaging.interceptors.CorrelationDataInterceptor;
import org.axonframework.monitoring.MessageMonitor;
import org.axonframework.queryhandling.DefaultQueryGateway;
import org.axonframework.queryhandling.QueryBus;
import org.axonframework.queryhandling.QueryGateway;
import org.axonframework.queryhandling.QueryInvocationErrorHandler;
import org.axonframework.queryhandling.QueryUpdateEmitter;
import org.axonframework.queryhandling.SimpleQueryBus;
import org.axonframework.queryhandling.annotation.AnnotationQueryHandlerAdapter;
import org.axonframework.serialization.AnnotationRevisionResolver;
import org.axonframework.serialization.RevisionResolver;
import org.axonframework.serialization.Serializer;
import org.axonframework.serialization.upcasting.event.EventUpcaster;
import org.axonframework.serialization.upcasting.event.EventUpcasterChain;
import org.axonframework.serialization.xml.XStreamSerializer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static java.util.Comparator.comparingInt;
import static java.util.stream.Collectors.toList;

/**
 * Entry point of the Axon Configuration API. It implements the Configurer interface, providing access to the methods to
 * configure the default Axon components.
 * <p>
 * Using {@link #defaultConfiguration()}, you will get a Configurer instance with default components configured. You
 * will need to register your Aggregates (using {@link #configureAggregate(AggregateConfiguration)} and provide a
 * repository implementation for each of them, or if you wish to use event sourcing, register your aggregates through
 * {@link #configureAggregate(Class)} and configure an Event Store ({@link #configureEventStore(Function)} or
 * {@link #configureEmbeddedEventStore(Function)}).
 * <p>
 * Use {@link #buildConfiguration()} to build the configuration, which provides access to the configured building
 * blocks, such as the {@link CommandBus} and {@link EventBus}.
 * <p>
 * Note that this Configurer implementation is not thread-safe.
 */
public class DefaultConfigurer implements Configurer {

    private final Configuration config = new ConfigurationImpl();

    private final MessageMonitorFactoryBuilder messageMonitorFactoryBuilder = new MessageMonitorFactoryBuilder();
    private final Component<BiFunction<Class<?>, String, MessageMonitor<Message<?>>>> messageMonitorFactoryComponent =
            new Component<>(config, "monitorFactory", messageMonitorFactoryBuilder::build);

    private final Component<List<CorrelationDataProvider>> correlationProviders =
            new Component<>(config, "correlationProviders",
                            c -> Collections.singletonList(new MessageOriginProvider()));

    private final Map<Class<?>, Component<?>> components = new HashMap<>();
    private final Component<Serializer> eventSerializer =
            new Component<>(config, "eventSerializer", Configuration::messageSerializer);
    private final Component<Serializer> messageSerializer =
            new Component<>(config, "messageSerializer", Configuration::serializer);
    private final List<Component<EventUpcaster>> upcasters = new ArrayList<>();
    private final Component<EventUpcasterChain> upcasterChain = new Component<>(
            config, "eventUpcasterChain",
            c -> new EventUpcasterChain(upcasters.stream().map(Component::get).collect(toList()))
    );

    private final Component<Function<Class, HandlerDefinition>> handlerDefinition = new Component<>(
            config, "handlerDefinition",
            c -> this::defaultHandlerDefinition);

    private final Map<Class<?>, AggregateConfiguration> aggregateConfigurations = new HashMap<>();

    private final List<ConsumerHandler> initHandlers = new ArrayList<>();
    private final List<RunnableHandler> startHandlers = new ArrayList<>();
    private final List<RunnableHandler> shutdownHandlers = new ArrayList<>();
    private final List<ModuleConfiguration> modules = new ArrayList<>();

    private boolean initialized = false;

    /**
     * Initialize the Configurer.
     */
    protected DefaultConfigurer() {
        components.put(ParameterResolverFactory.class,
                       new Component<>(config, "parameterResolverFactory", this::defaultParameterResolverFactory));
        components.put(Serializer.class, new Component<>(config, "serializer", this::defaultSerializer));
        components.put(CommandBus.class, new Component<>(config, "commandBus", this::defaultCommandBus));
        components.put(EventBus.class, new Component<>(config, "eventBus", this::defaultEventBus));
        components.put(EventStore.class, new Component<>(config, "eventStore", Configuration::eventStore));
        components.put(CommandGateway.class, new Component<>(config, "commandGateway", this::defaultCommandGateway));
        components.put(QueryBus.class, new Component<>(config, "queryBus", this::defaultQueryBus));
        components.put(QueryUpdateEmitter.class, new Component<>(config, "queryUpdateEmitter", this::defaultQueryUpdateEmitter));
        components.put(QueryGateway.class, new Component<>(config, "queryGateway", this::defaultQueryGateway));
        components.put(ResourceInjector.class,
                       new Component<>(config, "resourceInjector", this::defaultResourceInjector));
        components.put(DeadlineManager.class, new Component<>(config, "deadlineManager", this::defaultDeadlineManager));
    }

    /**
     * Returns a Configurer instance with default components configured, such as a {@link SimpleCommandBus} and
     * {@link SimpleEventBus}.
     *
     * @return Configurer instance for further configuration.
     */
    public static Configurer defaultConfiguration() {
        return new DefaultConfigurer();
    }

    /**
     * Returns a Configurer instance which has JPA versions of building blocks configured, such as a JPA based Event
     * Store (see {@link JpaEventStorageEngine}), a {@link JpaTokenStore} and {@link JpaSagaStore}.
     * <br>
     * This method allows to provide a transaction manager for usage in JTA-managed entity manager.
     *
     * @param entityManagerProvider The instance that provides access to the JPA EntityManager.
     * @param transactionManager    TransactionManager to be used for accessing the entity manager.
     * @return A Configurer instance for further configuration.
     */
    public static Configurer jpaConfiguration(EntityManagerProvider entityManagerProvider,
                                              TransactionManager transactionManager) {
        return new DefaultConfigurer()
                .registerComponent(EntityManagerProvider.class, c -> entityManagerProvider)
                .registerComponent(TransactionManager.class, c -> transactionManager)
                .configureEmbeddedEventStore(c -> new JpaEventStorageEngine(
                        c.serializer(),
                        c.upcasterChain(),
                        c.getComponent(PersistenceExceptionResolver.class),
                        c.eventSerializer(),
                        null,
                        c.getComponent(EntityManagerProvider.class),
                        c.getComponent(TransactionManager.class),
                        null, null, true
                ))
                .registerComponent(TokenStore.class,
                                   c -> new JpaTokenStore(c.getComponent(EntityManagerProvider.class),
                                                          c.serializer()))
                .registerComponent(SagaStore.class,
                                   c -> new JpaSagaStore(c.serializer(),
                                                         c.getComponent(EntityManagerProvider.class)));
    }

    /**
     * Returns a Configurer instance which has JPA versions of building blocks configured, such as a JPA based Event
     * Store (see {@link JpaEventStorageEngine}), a {@link JpaTokenStore} and {@link JpaSagaStore}.
     * <br>
     * This configuration should be used with an entity manager running without JTA transaction. If you are using a
     * entity manager in JTA mode, please provide the corresponding {@link TransactionManager} in the
     * {@link DefaultConfigurer#jpaConfiguration(EntityManagerProvider, TransactionManager)} method.
     *
     * @param entityManagerProvider The instance that provides access to the JPA EntityManager.
     * @return A Configurer instance for further configuration.
     */
    public static Configurer jpaConfiguration(EntityManagerProvider entityManagerProvider) {
        return jpaConfiguration(entityManagerProvider, NoTransactionManager.INSTANCE);
    }

    /**
     * Returns a {@link DefaultCommandGateway} that will use the configuration's {@link CommandBus} to dispatch
     * commands.
     *
     * @param config The configuration that supplies the command bus.
     * @return The default command gateway.
     */
    protected CommandGateway defaultCommandGateway(Configuration config) {
        return new DefaultCommandGateway(config.commandBus());
    }

    /**
     * Returns a {@link DefaultQueryGateway} that will use the configuration's {@link QueryBus} to dispatch queries.
     *
     * @param config The configuration that supplies the query bus.
     * @return The default query gateway.
     */
    protected QueryGateway defaultQueryGateway(Configuration config) {
        return new DefaultQueryGateway(config.queryBus());
    }

    /**
     * Provides the default QueryBus implementations. Subclasses may override this method to provide their own default.
     *
     * @param config The configuration based on which the component is initialized.
     * @return The default QueryBus to use.
     */
    protected QueryBus defaultQueryBus(Configuration config) {
        return new SimpleQueryBus(config.messageMonitor(SimpleQueryBus.class, "queryBus"),
                                  config.messageMonitor(QueryUpdateEmitter.class, "queryUpdateEmitter"),
                                  config.getComponent(TransactionManager.class, NoTransactionManager::instance),
                                  config.getComponent(QueryInvocationErrorHandler.class));
    }

    /**
     * Provides the default QueryUpdateEmitter implementation. Subclasses may override this method to provide their own
     * default.
     *
     * @param config The configuration based on which the component is initialized
     * @return The default QueryUpdateEmitter to use
     */
    protected QueryUpdateEmitter defaultQueryUpdateEmitter(Configuration config) {
        QueryBus queryBus = config.getComponent(QueryBus.class);
        if (!(queryBus instanceof QueryUpdateEmitter)) {
            throw new AxonConfigurationException(
                    "Implementation of query bus does not provide emitting functionality. Provide a query update emitter or query bus which supports emitting.");
        }
        return (QueryUpdateEmitter) queryBus;
    }

    /**
     * Provides the default ParameterResolverFactory. Subclasses may override this method to provide their own default.
     *
     * @param config The configuration based on which the component is initialized.
     * @return The default ParameterResolverFactory to use.
     */
    protected ParameterResolverFactory defaultParameterResolverFactory(Configuration config) {
        return MultiParameterResolverFactory.ordered(ClasspathParameterResolverFactory.forClass(getClass()),
                                                     new ConfigurationParameterResolverFactory(config));
    }

    /**
     * Provides the default HandlerDefinition. Subclasses may override this method to provide their own default.
     *
     * @param inspectedClass The class being inspected for handlers
     * @return The default HandlerDefinition to use
     */
    protected HandlerDefinition defaultHandlerDefinition(Class<?> inspectedClass) {
        return ClasspathHandlerDefinition.forClass(inspectedClass);
    }

    /**
     * Provides the default CommandBus implementation. Subclasses may override this method to provide their own default.
     *
     * @param config The configuration based on which the component is initialized.
     * @return The default CommandBus to use.
     */
    protected CommandBus defaultCommandBus(Configuration config) {
        SimpleCommandBus cb =
                new SimpleCommandBus(config.getComponent(TransactionManager.class, () -> NoTransactionManager.INSTANCE),
                                     config.messageMonitor(SimpleCommandBus.class, "commandBus"));
        cb.registerHandlerInterceptor(new CorrelationDataInterceptor<>(config.correlationDataProviders()));
        return cb;
    }

    /**
     * Returns a {@link ConfigurationResourceInjector} that injects resources defined in the given {@code config
     * Configuration}.
     *
     * @param config The configuration that supplies registered components.
     * @return A resource injector that supplies components registered with the configuration.
     */
    protected ResourceInjector defaultResourceInjector(Configuration config) {
        return new ConfigurationResourceInjector(config);
    }

    /**
     * Provides the default {@link DeadlineManager} implementation. Subclasses may override this method to provide their
     * own default.
     *
     * @param config The configuration that supplies registered components.
     * @return The default DeadlineManager to use
     */
    protected DeadlineManager defaultDeadlineManager(Configuration config) {
        return new SimpleDeadlineManager(new ConfigurationScopeAwareProvider(config));
    }

    /**
     * Provides the default EventBus implementation. Subclasses may override this method to provide their own default.
     *
     * @param config The configuration based on which the component is initialized.
     * @return The default EventBus to use.
     */
    protected EventBus defaultEventBus(Configuration config) {
        return new SimpleEventBus(Integer.MAX_VALUE, config.messageMonitor(EventBus.class, "eventBus"));
    }

    /**
     * Provides the default Serializer implementation. Subclasses may override this method to provide their own default.
     *
     * @param config The configuration based on which the component is initialized.
     * @return The default Serializer to use.
     */
    protected Serializer defaultSerializer(Configuration config) {
        return new XStreamSerializer(config.getComponent(RevisionResolver.class, AnnotationRevisionResolver::new));
    }

    @Override
    public Configurer registerEventUpcaster(Function<Configuration, EventUpcaster> upcasterBuilder) {
        upcasters.add(new Component<>(config, "upcaster", upcasterBuilder));
        return this;
    }

    @Override
    public Configurer configureMessageMonitor(
            Function<Configuration, BiFunction<Class<?>, String, MessageMonitor<Message<?>>>> builder) {
        messageMonitorFactoryBuilder.add((conf, type, name) -> builder.apply(conf).apply(type, name));
        return this;
    }

    @Override
    public Configurer configureMessageMonitor(Class<?> componentType, MessageMonitorFactory messageMonitorFactory) {
        messageMonitorFactoryBuilder.add(componentType, messageMonitorFactory);
        return this;
    }

    @Override
    public Configurer configureMessageMonitor(Class<?> componentType,
                                              String componentName,
                                              MessageMonitorFactory messageMonitorFactory) {
        messageMonitorFactoryBuilder.add(componentType, componentName, messageMonitorFactory);
        return this;
    }

    @Override
    public Configurer configureCorrelationDataProviders(
            Function<Configuration, List<CorrelationDataProvider>> correlationDataProviderBuilder) {
        correlationProviders.update(correlationDataProviderBuilder);
        return this;
    }

    @Override
    public Configurer registerModule(ModuleConfiguration module) {
        if (initialized) {
            module.initialize(config);
        } else {
            initHandlers.add(new ConsumerHandler(module.phase(), module::initialize));
        }
        this.modules.add(module);
        startHandlers.add(new RunnableHandler(module.phase(), module::start));
        shutdownHandlers.add(new RunnableHandler(module.phase(), module::shutdown));
        return this;
    }

    @Override
    public Configurer registerCommandHandler(int phase,
                                             Function<Configuration, Object> annotatedCommandHandlerBuilder) {
        startHandlers.add(new RunnableHandler(phase, () -> {
            Object handler = annotatedCommandHandlerBuilder.apply(config);
            Assert.notNull(handler, () -> "annotatedCommandHandler may not be null");
            Registration registration =
                    new AnnotationCommandHandlerAdapter(handler,
                                                        config.parameterResolverFactory(),
                                                        config.handlerDefinition(handler.getClass()))
                            .subscribe(config.commandBus());
            shutdownHandlers.add(new RunnableHandler(phase, registration::cancel));
        }));
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Configurer registerQueryHandler(int phase, Function<Configuration, Object> annotatedQueryHandlerBuilder) {
        startHandlers.add(new RunnableHandler(phase, () -> {
            Object annotatedHandler = annotatedQueryHandlerBuilder.apply(config);
            Assert.notNull(annotatedHandler, () -> "annotatedQueryHandler may not be null");

            Registration registration = new AnnotationQueryHandlerAdapter(annotatedHandler,
                                                                          config.parameterResolverFactory(),
                                                                          config.handlerDefinition(annotatedHandler
                                                                                                           .getClass()))
                    .subscribe(config.queryBus());
            shutdownHandlers.add(new RunnableHandler(phase, registration::cancel));
        }));
        return this;
    }

    @Override
    public <C> Configurer registerComponent(Class<C> componentType,
                                            Function<Configuration, ? extends C> componentBuilder) {
        components.put(componentType, new Component<>(config, componentType.getSimpleName(), componentBuilder));
        return this;
    }

    @Override
    public Configurer configureEmbeddedEventStore(Function<Configuration, EventStorageEngine> storageEngineBuilder) {
        return configureEventStore(c -> {
            MessageMonitor<Message<?>> monitor =
                    messageMonitorFactoryComponent.get().apply(EmbeddedEventStore.class, "eventStore");
            EmbeddedEventStore eventStore = new EmbeddedEventStore(storageEngineBuilder.apply(c), monitor);
            c.onShutdown(eventStore::shutDown);
            return eventStore;
        });
    }

    @Override
    public Configurer configureEventSerializer(Function<Configuration, Serializer> eventSerializerBuilder) {
        eventSerializer.update(eventSerializerBuilder);
        return this;
    }

    @Override
    public Configurer configureMessageSerializer(Function<Configuration, Serializer> messageSerializerBuilder) {
        messageSerializer.update(messageSerializerBuilder);
        return this;
    }

    @Override
    public <A> Configurer configureAggregate(AggregateConfiguration<A> aggregateConfiguration) {
        this.modules.add(aggregateConfiguration);
        this.aggregateConfigurations.put(aggregateConfiguration.aggregateType(), aggregateConfiguration);
        this.initHandlers.add(new ConsumerHandler(aggregateConfiguration.phase(), aggregateConfiguration::initialize));
        this.startHandlers.add(new RunnableHandler(aggregateConfiguration.phase(), aggregateConfiguration::start));
        this.shutdownHandlers.add(
                new RunnableHandler(aggregateConfiguration.phase(), aggregateConfiguration::shutdown)
        );
        return this;
    }

    @Override
    public Configurer registerHandlerDefinition(
            BiFunction<Configuration, Class, HandlerDefinition> handlerDefinitionClass) {
        this.handlerDefinition.update(c -> clazz -> handlerDefinitionClass.apply(c, clazz));
        return this;
    }

    @Override
    public Configuration buildConfiguration() {
        if (!initialized) {
            verifyIdentifierFactory();
            boolean missingEventProcessingConfiguration = modules.stream()
                                                                 .noneMatch(m -> m.unwrap() instanceof EventProcessingConfiguration);
            if (missingEventProcessingConfiguration) {
                registerModule(new EventProcessingConfiguration());
            }
            invokeInitHandlers();
        }
        return config;
    }

    /**
     * Verifies that a valid {@link IdentifierFactory} class has been configured.
     *
     * @throws IllegalArgumentException if the configured factory is not valid
     */
    private void verifyIdentifierFactory() {
        try {
            IdentifierFactory.getInstance();
        } catch (Exception e) {
            throw new IllegalArgumentException("The configured IdentifierFactory could not be instantiated.", e);
        }
    }

    /**
     * Calls all registered init handlers. Registration of init handlers after this invocation will result in an
     * immediate invocation of that handler.
     */
    protected void invokeInitHandlers() {
        initialized = true;
        initHandlers.stream().sorted(comparingInt(ConsumerHandler::phase)).forEach(h -> h.accept(config));
    }

    /**
     * Invokes all registered start handlers.
     */
    protected void invokeStartHandlers() {
        startHandlers.stream().sorted(comparingInt(RunnableHandler::phase)).forEach(RunnableHandler::run);
    }

    /**
     * Invokes all registered shutdown handlers.
     */
    protected void invokeShutdownHandlers() {
        shutdownHandlers.stream().sorted(comparingInt(RunnableHandler::phase).reversed()).forEach(RunnableHandler::run);
    }

    /**
     * Returns the current Configuration object being built by this Configurer, without initializing it. Note that
     * retrieving objects from this configuration may lead to premature initialization of certain components.
     *
     * @return The current Configuration object being built by this Configurer.
     */
    protected Configuration getConfig() {
        return config;
    }

    /**
     * Returns a map of all registered components in this configuration. The key of the map is the registered component
     * type (typically an interface), the value is a Component instance that wraps the actual implementation. Note that
     * calling {@link Component#get()} may prematurely initialize a component.
     *
     * @return A map of all registered components in this configuration.
     */
    public Map<Class<?>, Component<?>> getComponents() {
        return components;
    }

    private static class ConsumerHandler {

        private final int phase;
        private final Consumer<Configuration> handler;

        private ConsumerHandler(int phase, Consumer<Configuration> handler) {
            this.phase = phase;
            this.handler = handler;
        }

        public int phase() {
            return phase;
        }

        public void accept(Configuration configuration) {
            handler.accept(configuration);
        }
    }

    private static class RunnableHandler {

        private final int phase;
        private final Runnable handler;

        private RunnableHandler(int phase, Runnable handler) {
            this.phase = phase;
            this.handler = handler;
        }

        public int phase() {
            return phase;
        }

        public void run() {
            handler.run();
        }
    }

    private class ConfigurationImpl implements Configuration {

        @Override
        @SuppressWarnings("unchecked")
        public <T> Repository<T> repository(Class<T> aggregateType) {
            AggregateConfiguration<T> aggregateConfigurer =
                    DefaultConfigurer.this.aggregateConfigurations.get(aggregateType);
            if (aggregateConfigurer == null) {
                throw new IllegalArgumentException(
                        "Aggregate " + aggregateType.getSimpleName() + " has not been configured");
            }
            return aggregateConfigurer.repository();
        }

        @Override
        public <T> T getComponent(Class<T> componentType, Supplier<T> defaultImpl) {
            return componentType.cast(components.computeIfAbsent(
                    componentType, k -> new Component<>(config, componentType.getSimpleName(), c -> defaultImpl.get())
            ).get());
        }

        @Override
        public <M extends Message<?>> MessageMonitor<? super M> messageMonitor(Class<?> componentType,
                                                                               String componentName) {
            return messageMonitorFactoryComponent.get().apply(componentType, componentName);
        }

        @Override
        public Serializer eventSerializer() {
            return eventSerializer.get();
        }

        @Override
        public Serializer messageSerializer() {
            return messageSerializer.get();
        }

        @Override
        public void start() {
            invokeStartHandlers();
        }

        @Override
        public void shutdown() {
            invokeShutdownHandlers();
        }

        @Override
        public List<CorrelationDataProvider> correlationDataProviders() {
            return correlationProviders.get();
        }

        @Override
        public List<ModuleConfiguration> getModules() {
            return modules;
        }

        @Override
        public void onShutdown(int phase, Runnable shutdownHandler) {
            shutdownHandlers.add(new RunnableHandler(phase, shutdownHandler));
        }

        @Override
        public EventUpcasterChain upcasterChain() {
            return upcasterChain.get();
        }

        @Override
        public void onStart(int phase, Runnable startHandler) {
            startHandlers.add(new RunnableHandler(phase, startHandler));
        }

        @Override
        public HandlerDefinition handlerDefinition(Class<?> inspectedType) {
            return handlerDefinition.get().apply(inspectedType);
        }
    }
}
