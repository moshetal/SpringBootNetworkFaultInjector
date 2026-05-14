package com.mta.faultinjection.actuator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mta.faultinjection.api.FaultInjectorViewJsonKeys;
import com.mta.faultinjection.config.FaultInjectionProperties;
import com.mta.faultinjection.config.FaultInjectionProperties.Rule;
import com.mta.faultinjection.core.FaultDecisionStrategyImpl;
import com.mta.faultinjection.core.FaultType;
import com.mta.faultinjection.core.TriggerMode;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

class FaultInjectionEndpointTest {

    @Test
    void describeExposesConfigAndCounters() {
        FaultInjectionProperties props = enabledWithRule("r1", 1.0);
        FaultDecisionStrategyImpl strategy = new FaultDecisionStrategyImpl(props, () -> 0.0);
        strategy.decide(HttpMethod.GET, URI.create("https://api.example.com/x"));

        FaultInjectionEndpoint endpoint = endpoint(props, strategy);
        Map<String, Object> state = endpoint.describe();

        assertThat(state).containsEntry(FaultInjectorViewJsonKeys.ENABLED, true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rules = (List<Map<String, Object>>) state.get(FaultInjectorViewJsonKeys.RULES);
        assertThat(rules).hasSize(1);
        assertThat(rules.get(0))
                .containsEntry(FaultInjectorViewJsonKeys.NAME, "r1")
                .containsEntry(FaultInjectorViewJsonKeys.MATCH_COUNT, 1L)
                .containsEntry(FaultInjectorViewJsonKeys.TRIGGER_COUNT, 1L);
    }

    @Test
    void describeMethodsAreSerializableStrings() throws Exception {
        FaultInjectionProperties props = enabledWithRule("withMethods", 1.0);
        props.getRules().get(0).setMethods(java.util.Set.of(HttpMethod.GET, HttpMethod.POST));

        FaultInjectionEndpoint endpoint = endpoint(props, new FaultDecisionStrategyImpl(props));
        Map<String, Object> state = endpoint.describe();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rules = (List<Map<String, Object>>) state.get(FaultInjectorViewJsonKeys.RULES);
        @SuppressWarnings("unchecked")
        List<String> methods = (List<String>) rules.get(0).get(FaultInjectorViewJsonKeys.METHODS);
        assertThat(methods).containsExactly("GET", "POST");

        new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(state);
    }

    @Test
    void enableAndDisableFlipGlobalFlag() {
        FaultInjectionProperties props = enabledWithRule("r1", 1.0);
        props.setEnabled(false);
        FaultInjectionEndpoint endpoint = endpoint(props, new FaultDecisionStrategyImpl(props));

        endpoint.update(FaultInjectorActuatorActions.ENABLE, null, null, null);
        assertThat(props.isEnabled()).isTrue();
        endpoint.update(FaultInjectorActuatorActions.DISABLE, null, null, null);
        assertThat(props.isEnabled()).isFalse();
    }

    @Test
    void setRuleEnabledTogglesNamedRule() {
        FaultInjectionProperties props = enabledWithRule("r1", 1.0);
        FaultInjectionEndpoint endpoint = endpoint(props, new FaultDecisionStrategyImpl(props));

        endpoint.update(FaultInjectorActuatorActions.SET_RULE_ENABLED, "r1", false, null);
        assertThat(props.getRules().get(0).isEnabled()).isFalse();
    }

    @Test
    void setProbabilityValidatesRange() {
        FaultInjectionProperties props = enabledWithRule("r1", 0.1);
        FaultInjectionEndpoint endpoint = endpoint(props, new FaultDecisionStrategyImpl(props));

        endpoint.update(FaultInjectorActuatorActions.SET_PROBABILITY, "r1", null, 0.75);
        assertThat(props.getRules().get(0).getProbability()).isEqualTo(0.75);

        assertThatThrownBy(() -> endpoint.update(FaultInjectorActuatorActions.SET_PROBABILITY, "r1", null, 2.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownActionIsRejected() {
        FaultInjectionProperties props = enabledWithRule("r1", 1.0);
        FaultInjectionEndpoint endpoint = endpoint(props, new FaultDecisionStrategyImpl(props));

        assertThatThrownBy(() -> endpoint.update("nope", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingRuleNameIsRejected() {
        FaultInjectionProperties props = enabledWithRule("r1", 1.0);
        FaultInjectionEndpoint endpoint = endpoint(props, new FaultDecisionStrategyImpl(props));

        assertThatThrownBy(() -> endpoint.update(FaultInjectorActuatorActions.SET_RULE_ENABLED, "ghost", true, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static FaultInjectionEndpoint endpoint(FaultInjectionProperties props, FaultDecisionStrategyImpl strategy) {
        return new FaultInjectionEndpoint(new FaultInjectionActuatorService(props, strategy));
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
