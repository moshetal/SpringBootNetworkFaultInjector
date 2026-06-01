package com.mta.faultinjection.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProtocolSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void telemetryBatchRoundTrip() throws Exception {
        JsonNode metrics = mapper.readTree("{\"totals\":{\"matchCount\":1},\"resilience\":{\"retryObservations\":[]}}");
        TelemetryBatch batch = new TelemetryBatch("pod-a", "order-svc", 1000L, null, metrics, null, null);
        String json = mapper.writeValueAsString(batch);
        TelemetryBatch back = mapper.readValue(json, TelemetryBatch.class);
        assertThat(back.instanceId()).isEqualTo("pod-a");
        assertThat(back.metrics().get("totals").get("matchCount").asInt()).isEqualTo(1);
    }

    @Test
    void commandEnvelopeRoundTrip() throws Exception {
        CommandEnvelope env = new CommandEnvelope("cmd-1", CommandType.SET_ENABLED, mapper.readTree("{\"enabled\":true}"));
        CommandEnvelope back = mapper.readValue(mapper.writeValueAsString(env), CommandEnvelope.class);
        assertThat(back.type()).isEqualTo(CommandType.SET_ENABLED);
        assertThat(back.payload().get("enabled").asBoolean()).isTrue();
    }
}
