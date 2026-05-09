package com.mta.faultinjection.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mta.faultinjection.config.FaultInjectionProperties;
import com.mta.faultinjection.config.FaultInjectionProperties.Rule;
import com.mta.faultinjection.core.FaultDecisionStrategy;
import com.mta.faultinjection.core.FaultDecisionStrategyImpl;
import com.mta.faultinjection.core.FaultType;
import com.mta.faultinjection.core.TriggerMode;
import com.mta.faultinjection.telemetry.FaultInjectionTelemetry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit-level tests for the UI REST controller. Uses standalone MockMvc so we
 * don't pull in a full Spring context.
 */
class FaultInjectorUiControllerTest {

    private FaultInjectionProperties properties;
    private FaultDecisionStrategyImpl strategy;
    private FaultInjectionTelemetry telemetry;
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        properties = new FaultInjectionProperties();
        properties.setEnabled(true);
        // Seed one rule so update/toggle/delete tests have something to work on.
        Rule seed = new Rule();
        seed.setName("seed");
        seed.setFault(FaultType.DELAY);
        seed.setMode(TriggerMode.PROBABILITY);
        seed.setProbability(0.5);
        seed.setDelayMs(100L);
        properties.getRules().add(seed);

        telemetry = new FaultInjectionTelemetry(50, 1_000L, 6);
        strategy = new FaultDecisionStrategyImpl(properties, () -> 0.0d, telemetry);

        FaultInjectorUiController controller =
                new FaultInjectorUiController(properties, (FaultDecisionStrategy) strategy, telemetry);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void configReturnsCurrentSnapshot() throws Exception {
        mvc.perform(get("/fault-injector/api/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.rules[0].name").value("seed"))
                .andExpect(jsonPath("$.ui.path").value("/fault-injector"));
    }

    @Test
    void enabledFlipFlipsTheFlag() throws Exception {
        mvc.perform(post("/fault-injector/api/enabled")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        assertThat(properties.isEnabled()).isFalse();
    }

    @Test
    void addRuleAppendsToProperties() throws Exception {
        Map<String, Object> body = Map.of(
                "name", "added",
                "fault", "ERROR",
                "mode", "EVERY_N",
                "everyN", 3,
                "errorStatus", 502,
                "methods", java.util.List.of("GET", "POST"),
                "urlPattern", ".*/orders/.*"
        );
        mvc.perform(post("/fault-injector/api/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("added"))
                .andExpect(jsonPath("$.fault").value("ERROR"));

        Rule added = properties.getRules().stream()
                .filter(r -> "added".equals(r.getName())).findFirst().orElseThrow();
        assertThat(added.getFault()).isEqualTo(FaultType.ERROR);
        assertThat(added.getMode()).isEqualTo(TriggerMode.EVERY_N);
        assertThat(added.getEveryN()).isEqualTo(3);
        assertThat(added.getMethods()).containsExactlyInAnyOrder(HttpMethod.GET, HttpMethod.POST);
    }

    @Test
    void addRuleRejectsDuplicateName() throws Exception {
        mvc.perform(post("/fault-injector/api/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"seed\",\"fault\":\"DELAY\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void addRuleRejectsInvalidProbability() throws Exception {
        mvc.perform(post("/fault-injector/api/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"bad\",\"fault\":\"DELAY\",\"probability\":2.5}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void addRuleRejectsBadRegex() throws Exception {
        mvc.perform(post("/fault-injector/api/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"bad-regex\",\"hostPattern\":\"[unterminated\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateRuleAppliesPartialFields() throws Exception {
        mvc.perform(put("/fault-injector/api/rules/seed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"probability\":0.9,\"delayMs\":250}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.probability").value(0.9))
                .andExpect(jsonPath("$.delayMs").value(250));

        Rule seed = properties.getRules().get(0);
        assertThat(seed.getProbability()).isEqualTo(0.9);
        assertThat(seed.getDelayMs()).isEqualTo(250L);
        // Fields not in the body are preserved.
        assertThat(seed.getFault()).isEqualTo(FaultType.DELAY);
    }

    @Test
    void updateRuleRejectsRename() throws Exception {
        mvc.perform(put("/fault-injector/api/rules/seed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"renamed\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteRuleRemovesAndScrubsMetrics() throws Exception {
        // Generate counters first
        strategy.decide(HttpMethod.GET, URI.create("https://api.example.com/x"));
        assertThat(strategy.metricsSnapshot().get("seed").matchCount()).isEqualTo(1L);

        mvc.perform(delete("/fault-injector/api/rules/seed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removed").value("seed"));

        assertThat(properties.getRules()).isEmpty();
        assertThat(strategy.metricsSnapshot()).doesNotContainKey("seed");
        assertThat(telemetry.recentEvents(0)).isEmpty();
    }

    @Test
    void deleteUnknownRuleIs404() throws Exception {
        mvc.perform(delete("/fault-injector/api/rules/ghost"))
                .andExpect(status().isNotFound());
    }

    @Test
    void toggleRuleFlipsEnabled() throws Exception {
        mvc.perform(post("/fault-injector/api/rules/seed/enabled")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk());
        assertThat(properties.getRules().get(0).isEnabled()).isFalse();
    }

    @Test
    void metricsAggregatesPerRuleAndTotals() throws Exception {
        // Trigger twice so the seed rule has counters.
        strategy.decide(HttpMethod.GET, URI.create("https://api.example.com/x"));
        strategy.decide(HttpMethod.GET, URI.create("https://api.example.com/y"));

        mvc.perform(get("/fault-injector/api/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rules[0].name").value("seed"))
                .andExpect(jsonPath("$.rules[0].matchCount").value(2))
                .andExpect(jsonPath("$.totals.matchCount").value(2));
    }

    @Test
    void resetMetricsClearsAll() throws Exception {
        strategy.decide(HttpMethod.GET, URI.create("https://api.example.com/x"));
        assertThat(strategy.metricsSnapshot().get("seed").matchCount()).isEqualTo(1L);

        mvc.perform(post("/fault-injector/api/metrics/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        // metricsSnapshot() repopulates zeroed entries for every existing rule, so we
        // assert on the counter values rather than the map key.
        assertThat(strategy.metricsSnapshot().get("seed").matchCount()).isZero();
        assertThat(telemetry.recentEvents(0)).isEmpty();
    }

    @Test
    void exportCsvIncludesEventRows() throws Exception {
        strategy.decide(HttpMethod.GET, URI.create("https://api.example.com/x"));

        mvc.perform(get("/fault-injector/api/export").param("format", "csv"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("timestampMs,ruleName")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("seed")));
    }

    @Test
    void exportJsonReturnsBundle() throws Exception {
        mvc.perform(get("/fault-injector/api/export").param("format", "json"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.config").exists())
                .andExpect(jsonPath("$.metrics").exists())
                .andExpect(jsonPath("$.events").exists());
    }
}
