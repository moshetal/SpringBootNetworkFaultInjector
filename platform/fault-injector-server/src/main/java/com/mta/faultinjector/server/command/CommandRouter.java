package com.mta.faultinjector.server.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mta.faultinjection.protocol.CommandAck;
import com.mta.faultinjection.protocol.CommandEnvelope;
import com.mta.faultinjection.protocol.CommandResult;
import com.mta.faultinjection.protocol.CommandType;
import com.mta.faultinjector.server.config.ServerProperties;
import com.mta.faultinjector.server.registry.InstanceRegistry;
import com.mta.faultinjector.server.registry.InstanceRegistry.AgentInstance;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
public class CommandRouter {

    private final InstanceRegistry registry;
    private final SimpMessagingTemplate messaging;
    private final ServerProperties properties;
    private final ObjectMapper mapper;
    private final Map<String, CompletableFuture<CommandAck>> pending = new ConcurrentHashMap<>();

    public CommandRouter(
            InstanceRegistry registry,
            SimpMessagingTemplate messaging,
            ServerProperties properties,
            ObjectMapper mapper) {
        this.registry = registry;
        this.messaging = messaging;
        this.properties = properties;
        this.mapper = mapper;
    }

    public CommandResult dispatch(String serviceName, String scope, CommandType type, JsonNode payload) {
        List<AgentInstance> targets = resolveTargets(serviceName, scope);
        if (targets.isEmpty()) {
            return new CommandResult(0, 0, List.of());
        }
        String commandId = UUID.randomUUID().toString();
        CommandEnvelope envelope = new CommandEnvelope(commandId, type, payload);
        List<CommandResult.InstanceResult> results = new ArrayList<>();
        int applied = 0;
        int failed = 0;

        for (AgentInstance inst : targets) {
            CompletableFuture<CommandAck> future = new CompletableFuture<>();
            pending.put(commandId + ":" + inst.instanceId(), future);
            messaging.convertAndSendToUser(inst.sessionId(), "/queue/commands", envelope);
            try {
                CommandAck ack = future.get(properties.getCommandTimeoutMs(), TimeUnit.MILLISECONDS);
                if (ack.success()) {
                    applied++;
                    results.add(new CommandResult.InstanceResult(inst.instanceId(), true, null));
                } else {
                    failed++;
                    results.add(new CommandResult.InstanceResult(inst.instanceId(), false, ack.error()));
                }
            } catch (TimeoutException ex) {
                failed++;
                results.add(new CommandResult.InstanceResult(inst.instanceId(), false, "timeout"));
            } catch (Exception ex) {
                failed++;
                results.add(new CommandResult.InstanceResult(
                        inst.instanceId(), false, ex.getMessage()));
            } finally {
                pending.remove(commandId + ":" + inst.instanceId());
            }
        }
        return new CommandResult(applied, failed, results);
    }

    public void completeAck(CommandAck ack) {
        CompletableFuture<CommandAck> future = pending.get(ack.commandId() + ":" + ack.instanceId());
        if (future != null) {
            future.complete(ack);
        }
    }

    private List<AgentInstance> resolveTargets(String serviceName, String scope) {
        List<AgentInstance> all = registry.findByService(serviceName);
        if ("all".equals(scope) || scope == null || scope.isBlank()) {
            return all;
        }
        return all.stream().filter(i -> scope.equals(i.instanceId())).toList();
    }
}
