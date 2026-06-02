package com.mta.faultinjector.server.stomp;

import com.mta.faultinjection.protocol.AgentHeartbeat;
import com.mta.faultinjection.protocol.AgentRegister;
import com.mta.faultinjection.protocol.CommandAck;
import com.mta.faultinjection.protocol.ProtocolVersion;
import com.mta.faultinjection.protocol.TelemetryBatch;
import com.mta.faultinjector.server.registry.InstanceRegistry;
import com.mta.faultinjector.server.command.CommandRouter;
import com.mta.faultinjector.server.persistence.TelemetryPersistenceService;
import com.mta.faultinjector.server.telemetry.TelemetryAggregator;
import com.mta.faultinjector.server.web.ConsoleBroadcaster;
import java.security.Principal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Controller;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@Controller
public class AgentStompController {

    private static final Logger log = LoggerFactory.getLogger(AgentStompController.class);

    private final InstanceRegistry registry;
    private final TelemetryAggregator aggregator;
    private final CommandRouter commandRouter;
    private final TelemetryPersistenceService persistence;
    private final ConsoleBroadcaster broadcaster;

    public AgentStompController(
            InstanceRegistry registry,
            TelemetryAggregator aggregator,
            CommandRouter commandRouter,
            TelemetryPersistenceService persistence,
            ConsoleBroadcaster broadcaster) {
        this.registry = registry;
        this.aggregator = aggregator;
        this.commandRouter = commandRouter;
        this.persistence = persistence;
        this.broadcaster = broadcaster;
    }

    @MessageMapping("/agent/register")
    public void register(@Payload AgentRegister reg, Principal principal) {
        if (reg.protocolVersion() != ProtocolVersion.CURRENT) {
            log.warn("Agent {} unsupported protocol {}", reg.instanceId(), reg.protocolVersion());
            return;
        }
        String sessionId = principal.getName();
        registry.register(sessionId, reg, null);
        log.info("Registered agent {} for service {}", reg.instanceId(), reg.serviceName());
    }

    @MessageMapping("/agent/heartbeat")
    public void heartbeat(@Payload AgentHeartbeat heartbeat) {
        registry.heartbeat(heartbeat.instanceId());
    }

    @MessageMapping("/agent/telemetry")
    public void telemetry(@Payload TelemetryBatch batch) {
        aggregator.ingest(batch);
        registry.updateConfig(batch.instanceId(), batch.config());
        persistence.persistAsync(batch);
        broadcaster.broadcastService(batch.serviceName());
        broadcaster.broadcastOverview();
    }

    @MessageMapping("/agent/command-ack")
    public void commandAck(@Payload CommandAck ack) {
        commandRouter.completeAck(ack);
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        StompHeaderAccessor headers = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = headers.getUser() != null ? headers.getUser().getName() : null;
        if (sessionId != null) {
            registry.findBySessionId(sessionId).ifPresent(inst -> {
                aggregator.removeInstance(inst.instanceId());
                registry.removeSession(sessionId);
            });
        }
    }
}
