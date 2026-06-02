package com.mta.faultinjection.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mta.faultinjection.config.FaultInjectionProperties;
import com.mta.faultinjection.protocol.AgentHeartbeat;
import com.mta.faultinjection.protocol.AgentRegister;
import com.mta.faultinjection.protocol.CommandAck;
import com.mta.faultinjection.protocol.CommandEnvelope;
import com.mta.faultinjection.protocol.TelemetryBatch;
import com.mta.faultinjection.web.FaultInjectorControlFacade;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;
import org.springframework.lang.Nullable;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/** Outbound STOMP client connecting the microservice to the control server. */
public class FaultInjectorStompAgent implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(FaultInjectorStompAgent.class);

    private final FaultInjectionProperties properties;
    private final FaultInjectorControlFacade facade;
    private final AgentCommandExecutor commandExecutor;
    private final ObjectMapper mapper;
    private final Environment environment;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "fault-injector-agent");
        t.setDaemon(true);
        return t;
    });

    private volatile @Nullable StompSession session;
    private volatile @Nullable WebSocketStompClient stompClient;

    public FaultInjectorStompAgent(
            FaultInjectionProperties properties,
            FaultInjectorControlFacade facade,
            AgentCommandExecutor commandExecutor,
            ObjectMapper mapper,
            Environment environment) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.facade = Objects.requireNonNull(facade, "facade");
        this.commandExecutor = Objects.requireNonNull(commandExecutor, "commandExecutor");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.environment = environment;
    }

    @Override
    public void start() {
        if (!properties.getAgent().isEnabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        scheduleConnect(0);
        int interval = Math.max(500, properties.getAgent().getTelemetryIntervalMs());
        scheduler.scheduleAtFixedRate(this::pushTelemetrySafe, interval, interval, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(this::heartbeatSafe, 15, 15, TimeUnit.SECONDS);
    }

    @Override
    public void stop() {
        running.set(false);
        StompSession s = session;
        if (s != null && s.isConnected()) {
            s.disconnect();
        }
        scheduler.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    private void scheduleConnect(long delayMs) {
        if (!running.get()) {
            return;
        }
        scheduler.schedule(this::connectSafe, delayMs, TimeUnit.MILLISECONDS);
    }

    private void connectSafe() {
        if (!running.get()) {
            return;
        }
        try {
            connect();
        } catch (Exception ex) {
            log.warn("Fault injector agent connect failed: {}", ex.getMessage());
            scheduleConnect(Math.max(1000, properties.getAgent().getReconnectDelayMs()));
        }
    }

    private void connect() throws Exception {
        String url = properties.getAgent().getServerUrl();
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
        converter.setObjectMapper(mapper);
        client.setMessageConverter(converter);
        this.stompClient = client;

        java.util.concurrent.CompletableFuture<StompSession> future = client.connectAsync(url, new StompSessionHandlerAdapter() {
            @Override
            public void handleTransportError(StompSession s, Throwable exception) {
                log.warn("Fault injector agent transport error: {}", exception.getMessage());
                session = null;
                scheduleConnect(Math.max(1000, properties.getAgent().getReconnectDelayMs()));
            }
        });

        StompSession connected = future.get(30, TimeUnit.SECONDS);
        this.session = connected;
        connected.subscribe("/user/queue/commands", new CommandHandler());
        register(connected);
        log.info("Fault injector agent connected to {}", url);
    }

    private void register(StompSession s) {
        AgentRegister reg = new AgentRegister(
                resolveInstanceId(),
                resolveServiceName(),
                properties.getUi().getPath(),
                environment.getProperty("spring.application.version", "unknown"));
        s.send("/app/agent/register", reg);
    }

    private void heartbeatSafe() {
        StompSession s = session;
        if (s == null || !s.isConnected()) {
            return;
        }
        try {
            s.send("/app/agent/heartbeat", new AgentHeartbeat(resolveInstanceId(), System.currentTimeMillis()));
        } catch (Exception ex) {
            log.debug("Heartbeat failed: {}", ex.getMessage());
        }
    }

    private void pushTelemetrySafe() {
        StompSession s = session;
        if (s == null || !s.isConnected()) {
            return;
        }
        try {
            JsonNode config = mapper.valueToTree(facade.config());
            JsonNode metrics = mapper.valueToTree(facade.metrics());
            JsonNode timeseries = mapper.valueToTree(facade.timeSeries());
            JsonNode events = mapper.valueToTree(facade.events(200));
            TelemetryBatch batch = new TelemetryBatch(
                    resolveInstanceId(),
                    resolveServiceName(),
                    System.currentTimeMillis(),
                    config,
                    metrics,
                    timeseries,
                    events);
            s.send("/app/agent/telemetry", batch);
        } catch (Exception ex) {
            log.debug("Telemetry push failed: {}", ex.getMessage());
        }
    }

    private String resolveServiceName() {
        String configured = properties.getAgent().getServiceName();
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        String appName = environment.getProperty("spring.application.name");
        return appName == null || appName.isBlank() ? "unknown-service" : appName;
    }

    private String resolveInstanceId() {
        String configured = properties.getAgent().getInstanceId();
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        String hostname = environment.getProperty("HOSTNAME");
        if (hostname != null && !hostname.isBlank()) {
            return hostname;
        }
        return resolveServiceName() + "-" + Integer.toHexString(System.identityHashCode(this));
    }

    private final class CommandHandler implements StompFrameHandler {
        @Override
        public Type getPayloadType(StompHeaders headers) {
            return CommandEnvelope.class;
        }

        @Override
        @SuppressWarnings("null")
        public void handleFrame(StompHeaders headers, Object payload) {
            if (!(payload instanceof CommandEnvelope command)) {
                return;
            }
            AgentCommandExecutor.ExecutionResult result = commandExecutor.execute(command);
            StompSession s = session;
            if (s == null || !s.isConnected()) {
                return;
            }
            CommandAck ack = new CommandAck(
                    command.commandId(),
                    resolveInstanceId(),
                    result.success(),
                    result.payload(),
                    result.error());
            s.send("/app/agent/command-ack", ack);
        }
    }
}
