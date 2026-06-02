package com.mta.faultinjection.protocol;

/** Periodic liveness signal from an agent. */
public record AgentHeartbeat(String instanceId, long timestampMs) {}
