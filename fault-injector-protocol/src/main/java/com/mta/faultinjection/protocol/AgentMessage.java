package com.mta.faultinjection.protocol;

import com.fasterxml.jackson.databind.JsonNode;

/** Extensible envelope for agent ↔ server STOMP payloads. */
public record AgentMessage(int protocolVersion, String type, JsonNode payload) {

    public AgentMessage(String type, JsonNode payload) {
        this(ProtocolVersion.CURRENT, type, payload);
    }
}
