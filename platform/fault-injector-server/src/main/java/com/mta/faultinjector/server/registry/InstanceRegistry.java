package com.mta.faultinjector.server.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.mta.faultinjection.protocol.AgentRegister;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class InstanceRegistry {

    public record AgentInstance(
            String instanceId,
            String serviceName,
            String sessionId,
            String localUiPath,
            Instant registeredAt,
            Instant lastSeen,
            JsonNode lastConfig) {}

    private final Map<String, AgentInstance> byInstanceId = new ConcurrentHashMap<>();
    private final Map<String, String> sessionToInstance = new ConcurrentHashMap<>();

    public void register(String sessionId, AgentRegister reg, JsonNode configSnapshot) {
        Instant now = Instant.now();
        AgentInstance inst = new AgentInstance(
                reg.instanceId(),
                reg.serviceName(),
                sessionId,
                reg.localUiPath(),
                now,
                now,
                configSnapshot);
        byInstanceId.put(reg.instanceId(), inst);
        sessionToInstance.put(sessionId, reg.instanceId());
    }

    public void heartbeat(String instanceId) {
        AgentInstance current = byInstanceId.get(instanceId);
        if (current != null) {
            byInstanceId.put(
                    instanceId,
                    new AgentInstance(
                            current.instanceId(),
                            current.serviceName(),
                            current.sessionId(),
                            current.localUiPath(),
                            current.registeredAt(),
                            Instant.now(),
                            current.lastConfig()));
        }
    }

    public void updateConfig(String instanceId, JsonNode config) {
        AgentInstance current = byInstanceId.get(instanceId);
        if (current != null) {
            byInstanceId.put(
                    instanceId,
                    new AgentInstance(
                            current.instanceId(),
                            current.serviceName(),
                            current.sessionId(),
                            current.localUiPath(),
                            current.registeredAt(),
                            Instant.now(),
                            config));
        }
    }

    public void removeSession(String sessionId) {
        String instanceId = sessionToInstance.remove(sessionId);
        if (instanceId != null) {
            byInstanceId.remove(instanceId);
        }
    }

    public Optional<AgentInstance> findByInstanceId(String instanceId) {
        return Optional.ofNullable(byInstanceId.get(instanceId));
    }

    public Optional<AgentInstance> findBySessionId(String sessionId) {
        String id = sessionToInstance.get(sessionId);
        return id == null ? Optional.empty() : findByInstanceId(id);
    }

    public List<AgentInstance> findByService(String serviceName) {
        List<AgentInstance> out = new ArrayList<>();
        for (AgentInstance inst : byInstanceId.values()) {
            if (serviceName.equals(inst.serviceName())) {
                out.add(inst);
            }
        }
        return out;
    }

    public Collection<AgentInstance> allInstances() {
        return List.copyOf(byInstanceId.values());
    }

    public List<String> serviceNames() {
        return byInstanceId.values().stream()
                .map(AgentInstance::serviceName)
                .distinct()
                .sorted()
                .toList();
    }

    public void evictStale(long ttlMs) {
        Instant cutoff = Instant.now().minusMillis(ttlMs);
        byInstanceId.entrySet().removeIf(e -> {
            if (e.getValue().lastSeen().isBefore(cutoff)) {
                sessionToInstance.remove(e.getValue().sessionId());
                return true;
            }
            return false;
        });
    }
}
