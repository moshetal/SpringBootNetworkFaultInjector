package com.mta.faultinjection.actuator;

import com.mta.faultinjection.config.FaultInjectionProperties;
import com.mta.faultinjection.config.FaultInjectionProperties.Rule;
import com.mta.faultinjection.core.FaultDecisionStrategyImpl;
import com.mta.faultinjection.core.FaultType;
import com.mta.faultinjection.core.TriggerMode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.net.URI;
import java.util.List;
import java.util.Map;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FaultInjectionEndpointTest {

    @Test
    void describeExposesConfigAndCounters() {
        FaultInjectionProperties props = enabledWithRule("r1", 1.0);
        FaultDecisionStrategyImpl strategy = new FaultDecisionStrategyImpl(props, () -> 0.0);
        // Generate one match+trigger.
        strategy.decide(HttpMethod.GET, URI.create("https://api.example.com/x"));

        FaultInjectionEndpoint endpoint = new FaultInjectionEndpoint(props, strategy);
        Map<String, Object> state = endpoint.describe();

        assertThat(state).containsEntry("enabled", true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rules = (List<Map<String, Object>>) state.get("rules");
        assertThat(rules).hasSize(1);
        assertThat(rules.get(0))
                .containsEntry("name", "r1")
                .containsEntry("matchCount", 1L)
                .containsEntry("triggerCount", 1L);
    }

    @Test
    void enableAndDisableFlipGlobalFlag() {
        FaultInjectionProperties props = enabledWithRule("r1", 1.0);
        props.setEnabled(false);
        FaultInjectionEndpoint endpoint = new FaultInjectionEndpoint(props, new FaultDecisionStrategyImpl(props));

        endpoint.update("enable", null, null, null);
        assertThat(props.isEnabled()).isTrue();
        endpoint.update("disable", null, null, null);
        assertThat(props.isEnabled()).isFalse();
    }

    @Test
    void setRuleEnabledTogglesNamedRule() {
        FaultInjectionProperties props = enabledWithRule("r1", 1.0);
        FaultInjectionEndpoint endpoint = new FaultInjectionEndpoint(props, new FaultDecisionStrategyImpl(props));

        endpoint.update("setRuleEnabled", "r1", false, null);
        assertThat(props.getRules().get(0).isEnabled()).isFalse();
    }

    @Test
    void setProbabilityValidatesRange() {
        FaultInjectionProperties props = enabledWithRule("r1", 0.1);
        FaultInjectionEndpoint endpoint = new FaultInjectionEndpoint(props, new FaultDecisionStrategyImpl(props));

        endpoint.update("setProbability", "r1", null, 0.75);
        assertThat(props.getRules().get(0).getProbability()).isEqualTo(0.75);

        assertThatThrownBy(() ->
                endpoint.update("setProbability", "r1", null, 2.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownActionIsRejected() {
        FaultInjectionProperties props = enabledWithRule("r1", 1.0);
        FaultInjectionEndpoint endpoint = new FaultInjectionEndpoint(props, new FaultDecisionStrategyImpl(props));

        assertThatThrownBy(() -> endpoint.update("nope", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingRuleNameIsRejected() {
        FaultInjectionProperties props = enabledWithRule("r1", 1.0);
        FaultInjectionEndpoint endpoint = new FaultInjectionEndpoint(props, new FaultDecisionStrategyImpl(props));

        assertThatThrownBy(() -> endpoint.update("setRuleEnabled", "ghost", true, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static FaultInjectionProperties enabledWithRule(String name, double prob) {
        FaultInjectionProperties p = new FaultInjectionProperties();
        p.setEnabled(true);
        Rule r = new Rule();
        r.setName(name);
        r.setFault(FaultType.DELAY);
        r.setDelayMs(10L);
        r.setMode(TriggerMode.PROBABILITY);
        r.setProbability(prob);
        p.getRules().add(r);
        return p;
    }
}
