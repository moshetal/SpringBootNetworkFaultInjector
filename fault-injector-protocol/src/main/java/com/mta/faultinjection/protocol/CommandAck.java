package com.mta.faultinjection.protocol;

import com.fasterxml.jackson.databind.JsonNode;

/** Agent → server acknowledgement for a dispatched command. */
public record CommandAck(
        String commandId,
        String instanceId,
        boolean success,
        JsonNode payload,
        String error) {}
