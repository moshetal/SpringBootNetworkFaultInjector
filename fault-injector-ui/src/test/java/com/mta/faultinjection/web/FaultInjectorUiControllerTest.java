package com.mta.faultinjection.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mta.faultinjection.config.FaultInjectionProperties;
import com.mta.faultinjection.config.FaultInjectionProperties.Rule;
import com.mta.faultinjection.core.FaultDecisionStrategyImpl;
import com.mta.faultinjection.core.FaultType;
import com.mta.faultinjection.core.TriggerMode;
import com.mta.faultinjection.telemetry.FaultInjectionTelemetry;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

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

        // MockEnvironment has no property sources, so findApplicationYamlPath()
        // returns empty and exportConfig() takes the subtree-only fallback path —
        // which is what the existing tests assume.
        FaultInjectorUiService service =
                new FaultInjectorUiService(properties, strategy, telemetry, new MockEnvironment());
        FaultInjectorUiController controller = new FaultInjectorUiController(service);
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
    void updateDefaultsMutatesLiveBean() throws Exception {
        mvc.perform(put("/fault-injector/api/defaults")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"delayMs\":250,\"errorStatus\":502,\"errorMessage\":\"custom\",\"mode\":\"EVERY_N\",\"probability\":0.25,\"everyN\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaults.delayMs").value(250))
                .andExpect(jsonPath("$.defaults.mode").value("EVERY_N"));
        assertThat(properties.getDefaults().getDelayMs()).isEqualTo(250L);
        assertThat(properties.getDefaults().getErrorStatus()).isEqualTo(502);
        assertThat(properties.getDefaults().getErrorMessage()).isEqualTo("custom");
        assertThat(properties.getDefaults().getMode()).isEqualTo(TriggerMode.EVERY_N);
        assertThat(properties.getDefaults().getProbability()).isEqualTo(0.25);
        assertThat(properties.getDefaults().getEveryN()).isEqualTo(2);
    }

    @Test
    void updateDefaultsRejectsBadHttpStatus() throws Exception {
        mvc.perform(put("/fault-injector/api/defaults")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"errorStatus\":99}"))
                .andExpect(status().isBadRequest());
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
                "urlPattern", ".*/orders/.*");
        mvc.perform(post("/fault-injector/api/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("added"))
                .andExpect(jsonPath("$.fault").value("ERROR"));

        Rule added = properties.getRules().stream()
                .filter(r -> "added".equals(r.getName()))
                .findFirst()
                .orElseThrow();
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
        mvc.perform(delete("/fault-injector/api/rules/ghost")).andExpect(status().isNotFound());
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

    // ------------------------------------------------------------------
    // YAML config export
    // ------------------------------------------------------------------

    @Test
    void exportConfigYamlReflectsLiveProperties() throws Exception {
        properties.getDefaults().setDelayMs(123);
        properties.getDefaults().setErrorMessage("test fallback");

        var fullRule = new Rule();
        fullRule.setName("full-coverage");
        fullRule.setFault(FaultType.BOTH);
        fullRule.setMode(TriggerMode.EVERY_N);
        fullRule.setEveryN(7);
        fullRule.setDelayMs(250L);
        fullRule.setErrorStatus(418);
        fullRule.setErrorMessage("boom");
        fullRule.setHostPattern("api\\.example\\.com");
        fullRule.setUrlPattern(".*/orders/.*");
        fullRule.setMethods(java.util.Set.of(HttpMethod.GET, HttpMethod.POST));
        fullRule.setProbability(0.42d);
        properties.getRules().add(fullRule);

        var result = mvc.perform(get("/fault-injector/api/config/export"))
                .andExpect(status().isOk())
                .andReturn();

        @SuppressWarnings("unchecked")
        Map<String, Object> root =
                new org.yaml.snakeyaml.Yaml().load(result.getResponse().getContentAsString());

        @SuppressWarnings("unchecked")
        Map<String, Object> injection =
                (Map<String, Object>) ((Map<String, Object>) root.get("fault")).get("injection");

        assertThat(injection.get("enabled")).isEqualTo(true);

        @SuppressWarnings("unchecked")
        Map<String, Object> defaults = (Map<String, Object>) injection.get("defaults");
        assertThat(defaults).containsEntry("delay-ms", 123);
        assertThat(defaults).containsEntry("error-message", "test fallback");
        assertThat(defaults).containsEntry("mode", "PROBABILITY");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rules = (List<Map<String, Object>>) injection.get("rules");
        var full = rules.stream()
                .filter(r -> "full-coverage".equals(r.get("name")))
                .findFirst()
                .orElseThrow();
        assertThat(full)
                .containsEntry("fault", "BOTH")
                .containsEntry("mode", "EVERY_N")
                .containsEntry("every-n", 7)
                .containsEntry("delay-ms", 250)
                .containsEntry("error-status", 418)
                .containsEntry("error-message", "boom")
                .containsEntry("host-pattern", "api\\.example\\.com")
                .containsEntry("url-pattern", ".*/orders/.*")
                .containsEntry("probability", 0.42d);
        @SuppressWarnings("unchecked")
        List<String> exportedMethods = (List<String>) full.get("methods");
        assertThat(exportedMethods).containsExactly("GET", "POST");
    }

    @Test
    void exportConfigOmitsNullOverrideFields() throws Exception {
        properties.getRules().clear();
        var minimal = new Rule();
        minimal.setName("minimal");
        minimal.setFault(FaultType.DELAY);
        properties.getRules().add(minimal);

        var result = mvc.perform(get("/fault-injector/api/config/export"))
                .andExpect(status().isOk())
                .andReturn();

        @SuppressWarnings("unchecked")
        Map<String, Object> root =
                new org.yaml.snakeyaml.Yaml().load(result.getResponse().getContentAsString());
        @SuppressWarnings("unchecked")
        var rules = (List<Map<String, Object>>)
                ((Map<String, Object>) ((Map<String, Object>) root.get("fault")).get("injection")).get("rules");
        var minimalMap = rules.get(0);
        // Null overrides shouldn't leak into the YAML — they cascade to defaults
        // at parse time anyway.
        assertThat(minimalMap.keySet()).containsExactlyInAnyOrder("name", "fault");
    }

    @Test
    void exportConfigIncludesUiAddedRule() throws Exception {
        Map<String, Object> body = Map.of(
                "name", "ui-test",
                "fault", "ERROR",
                "mode", "PROBABILITY",
                "probability", 0.5d,
                "errorStatus", 503,
                "urlPattern", ".*/things/.*");
        mvc.perform(post("/fault-injector/api/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(body)))
                .andExpect(status().isCreated());

        var result = mvc.perform(get("/fault-injector/api/config/export"))
                .andExpect(status().isOk())
                .andReturn();

        @SuppressWarnings("unchecked")
        Map<String, Object> root =
                new org.yaml.snakeyaml.Yaml().load(result.getResponse().getContentAsString());
        @SuppressWarnings("unchecked")
        var rules = (List<Map<String, Object>>)
                ((Map<String, Object>) ((Map<String, Object>) root.get("fault")).get("injection")).get("rules");

        var added = rules.stream()
                .filter(r -> "ui-test".equals(r.get("name")))
                .findFirst()
                .orElseThrow();
        assertThat(added)
                .containsEntry("fault", "ERROR")
                .containsEntry("mode", "PROBABILITY")
                .containsEntry("probability", 0.5d)
                .containsEntry("error-status", 503)
                .containsEntry("url-pattern", ".*/things/.*");
    }

    @Test
    void exportConfigRejectsUnknownFormat() throws Exception {
        mvc.perform(get("/fault-injector/api/config/export").param("format", "xml"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void exportConfigSetsDownloadHeaders() throws Exception {
        mvc.perform(get("/fault-injector/api/config/export"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/yaml"))
                .andExpect(header().string(
                                "Content-Disposition",
                                org.hamcrest.Matchers.containsString("filename=fault-injection.yml")));
    }

    // ------------------------------------------------------------------
    // Merge endpoint — splice live fault.injection into existing yaml
    // ------------------------------------------------------------------

    @Test
    void mergePreservesSurroundingSectionsAndComments() throws Exception {
        String existing =
                """
                server:
                  port: 8080

                # important note about logging
                logging:
                  level:
                    root: INFO

                fault:
                  injection:
                    enabled: false
                    rules:
                      - name: stale-rule
                        url-pattern: ".*/old/.*"
                        fault: ERROR

                management:
                  endpoints:
                    web:
                      exposure:
                        include: health
                """;

        var result = mvc.perform(post("/fault-injector/api/config/merge")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(existing))
                .andExpect(status().isOk())
                .andReturn();

        String merged = result.getResponse().getContentAsString();

        // Sections outside fault: come through verbatim, including the comment.
        assertThat(merged).contains("server:\n  port: 8080");
        assertThat(merged).contains("# important note about logging");
        assertThat(merged).contains("logging:\n  level:\n    root: INFO");
        assertThat(merged).contains("management:\n  endpoints:\n    web:\n      exposure:\n        include: health");

        // Old fault block content is gone, replaced by live state.
        assertThat(merged).doesNotContain("stale-rule");
        assertThat(merged).contains("name: seed");
        assertThat(merged).contains("enabled: true"); // live properties.isEnabled() == true
    }

    @Test
    void mergeAppendsWhenNoFaultBlockPresent() throws Exception {
        String existing = """
                server:
                  port: 9090
                """;

        var result = mvc.perform(post("/fault-injector/api/config/merge")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(existing))
                .andExpect(status().isOk())
                .andReturn();

        String merged = result.getResponse().getContentAsString();
        assertThat(merged).startsWith("server:\n  port: 9090");
        assertThat(merged).contains("fault:\n  injection:");
        assertThat(merged).contains("name: seed");
    }

    @Test
    void mergeHandlesFaultAsLastBlock() throws Exception {
        String existing =
                """
                server:
                  port: 8080

                fault:
                  injection:
                    enabled: false
                    rules: []
                """;

        var result = mvc.perform(post("/fault-injector/api/config/merge")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(existing))
                .andExpect(status().isOk())
                .andReturn();

        String merged = result.getResponse().getContentAsString();
        assertThat(merged).contains("server:\n  port: 8080");
        assertThat(merged).contains("name: seed");
        // The replaced subtree is gone — `enabled: false` from the original
        // fault block must NOT survive.
        var liveFaultStart = merged.indexOf("fault:");
        assertThat(merged.substring(liveFaultStart)).doesNotContain("enabled: false");
    }

    @Test
    void mergeOutputIsParseableYaml() throws Exception {
        String existing =
                """
                server:
                  port: 8080
                fault:
                  injection:
                    enabled: false
                logging:
                  level:
                    root: WARN
                """;

        var result = mvc.perform(post("/fault-injector/api/config/merge")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(existing))
                .andExpect(status().isOk())
                .andReturn();

        String merged = result.getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = new org.yaml.snakeyaml.Yaml().load(merged);
        assertThat(parsed).containsKeys("server", "fault", "logging");
        @SuppressWarnings("unchecked")
        Map<String, Object> injection =
                (Map<String, Object>) ((Map<String, Object>) parsed.get("fault")).get("injection");
        assertThat(injection.get("enabled")).isEqualTo(true);
    }

    @Test
    void mergeSetsDownloadHeaders() throws Exception {
        mvc.perform(post("/fault-injector/api/config/merge")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("server:\n  port: 8080\n"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/yaml"))
                .andExpect(header().string(
                                "Content-Disposition",
                                org.hamcrest.Matchers.containsString("filename=application-merged.yml")));
    }
}
