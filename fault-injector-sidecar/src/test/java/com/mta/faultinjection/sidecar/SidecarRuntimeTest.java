package com.mta.faultinjection.sidecar;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mta.faultinjection.config.FaultInjectionProperties;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class SidecarRuntimeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static List<JsonNode> run(String stdin) throws Exception {
        Path yaml = Path.of(new ClassPathResource("sidecar-always-delay.yml").getFile().getAbsolutePath());
        FaultInjectionProperties props = SidecarConfigLoader.load(yaml);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SidecarRuntime.run(new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)), out, props);
        String raw = out.toString(StandardCharsets.UTF_8);
        return java.util.Arrays.stream(raw.split("\\R"))
                .filter(s -> !s.isBlank())
                .map(line -> {
                    try {
                        return MAPPER.readTree(line);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .toList();
    }

    @Test
    void readyThenDelayDecideThenShutdown() throws Exception {
        List<JsonNode> lines = run("""
                {"id":"1","op":"decide","method":"GET","url":"https://api.example.com/x"}
                {"id":"2","op":"shutdown"}
                """);
        assertThat(lines.get(0).path("op").asText()).isEqualTo("ready");
        assertThat(lines.get(1).path("id").asText()).isEqualTo("1");
        assertThat(lines.get(1).path("instruction").asText()).isEqualTo("INJECT_DELAY");
        assertThat(lines.get(1).path("delayMs").asLong()).isEqualTo(50L);
        assertThat(lines.get(1).path("ruleName").asText()).isEqualTo("always-delay");
        assertThat(lines.get(2).path("ok").asBoolean()).isTrue();
    }

    @Test
    void metricsCountsTrigger() throws Exception {
        List<JsonNode> lines = run("""
                {"id":"1","op":"decide","method":"GET","url":"https://api.example.com/x"}
                {"id":"2","op":"metrics"}
                {"id":"3","op":"shutdown"}
                """);
        JsonNode metrics = lines.get(2).path("rules").path("always-delay");
        assertThat(metrics.path("matchCount").asLong()).isEqualTo(1L);
        assertThat(metrics.path("triggerCount").asLong()).isEqualTo(1L);
    }

    @Test
    void setEnabledFalseThenPass() throws Exception {
        List<JsonNode> lines = run("""
                {"id":"1","op":"setEnabled","enabled":false}
                {"id":"2","op":"decide","method":"GET","url":"https://api.example.com/x"}
                {"id":"3","op":"shutdown"}
                """);
        assertThat(lines.get(2).path("instruction").asText()).isEqualTo("PASS");
    }

    @Test
    void unknownOpReturnsErrorAndKeepsRunning() throws Exception {
        List<JsonNode> lines = run("""
                {"id":"1","op":"nope"}
                {"id":"2","op":"shutdown"}
                """);
        assertThat(lines.get(1).path("error").asText()).isNotBlank();
        assertThat(lines.get(2).path("ok").asBoolean()).isTrue();
    }

    @Test
    void badJsonReturnsError() throws Exception {
        List<JsonNode> lines = run("""
                not-json
                {"id":"2","op":"shutdown"}
                """);
        assertThat(lines.get(1).has("error")).isTrue();
    }

    @Test
    void invalidUrlReturnsError() throws Exception {
        List<JsonNode> lines = run("""
                {"id":"1","op":"decide","method":"GET","url":"::not-a-uri"}
                {"id":"2","op":"shutdown"}
                """);
        assertThat(lines.get(1).path("id").asText()).isEqualTo("1");
        assertThat(lines.get(1).path("error").asText()).contains("url");
    }
}
