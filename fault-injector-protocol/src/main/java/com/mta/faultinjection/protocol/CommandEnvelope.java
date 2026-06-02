package com.mta.faultinjection.protocol;

import com.fasterxml.jackson.databind.JsonNode;

/** Server → agent command with extensible JSON payload. */
public record CommandEnvelope(String commandId, CommandType type, JsonNode payload) {}
