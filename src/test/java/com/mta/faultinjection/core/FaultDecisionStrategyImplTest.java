package com.mta.faultinjection.core;

import com.mta.faultinjection.config.FaultInjectionProperties;
import com.mta.faultinjection.config.FaultInjectionProperties.Rule;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.function.DoubleSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class FaultDecisionStrategyImplTest {

    private static final URI ANY_URI = URI.create("https://api.example.com/v1/things");

    @Test
    void globallyDisabledAlwaysPasses() {
        FaultInjectionProperties props = new FaultInjectionProperties();
        props.setEnabled(false);
        props.getRules().add(delayRule("any", null, 100));

        FaultDecisionStrategyImpl strategy = alwaysTrigger(props);

        assertThat(strategy.decide(HttpMethod.GET, ANY_URI).instruction())
                .isEqualTo(FaultDecision.Instruction.PASS);
    }

    @Test
    void noMatchingRulePasses() {
        FaultInjectionProperties props = enabled();
        Rule r = delayRule("host-only", null, 50);
        r.setHostPattern("other\\.example\\.com");
        props.getRules().add(r);

        FaultDecisionStrategyImpl strategy = alwaysTrigger(props);

        assertThat(strategy.decide(HttpMethod.GET, ANY_URI).instruction())
                .isEqualTo(FaultDecision.Instruction.PASS);
    }

    @Test
    void hostRegexMatchProducesDelayDecision() {
        FaultInjectionProperties props = enabled();
        Rule r = delayRule("hostmatch", null, 250);
        r.setHostPattern("api\\.example\\.com");
        props.getRules().add(r);

        FaultDecision d = alwaysTrigger(props).decide(HttpMethod.GET, ANY_URI);

        assertThat(d.instruction()).isEqualTo(FaultDecision.Instruction.INJECT_DELAY);
        assertThat(d.delay().toMillis()).isEqualTo(250L);
    }

    @Test
    void methodFilterFiltersNonMatches() {
        FaultInjectionProperties props = enabled();
        Rule r = delayRule("post-only", null, 10);
        r.setMethods(Set.of(HttpMethod.POST));
        props.getRules().add(r);

        FaultDecisionStrategyImpl strategy = alwaysTrigger(props);
        assertThat(strategy.decide(HttpMethod.GET, ANY_URI).instruction())
                .isEqualTo(FaultDecision.Instruction.PASS);
        assertThat(strategy.decide(HttpMethod.POST, ANY_URI).instruction())
                .isEqualTo(FaultDecision.Instruction.INJECT_DELAY);
    }

    @Test
    void probabilityUsesInjectedRandom() {
        FaultInjectionProperties props = enabled();
        Rule r = delayRule("prob", TriggerMode.PROBABILITY, 10);
        r.setProbability(0.5);
        props.getRules().add(r);

        // 0.0 always triggers, 0.99 never triggers at p=0.5.
        FaultDecisionStrategyImpl hits = new FaultDecisionStrategyImpl(props, () -> 0.0d);
        FaultDecisionStrategyImpl misses = new FaultDecisionStrategyImpl(props, () -> 0.99d);

        assertThat(hits.decide(HttpMethod.GET, ANY_URI).instruction())
                .isEqualTo(FaultDecision.Instruction.INJECT_DELAY);
        assertThat(misses.decide(HttpMethod.GET, ANY_URI).instruction())
                .isEqualTo(FaultDecision.Instruction.PASS);
    }

    @Test
    void everyNFiresOnEveryThirdCall() {
        FaultInjectionProperties props = enabled();
        Rule r = errorRule("every3", TriggerMode.EVERY_N);
        r.setEveryN(3);
        r.setErrorStatus(418);
        props.getRules().add(r);

        FaultDecisionStrategyImpl strategy = alwaysTrigger(props);
        List<FaultDecision.Instruction> seen = List.of(
                strategy.decide(HttpMethod.GET, ANY_URI).instruction(),
                strategy.decide(HttpMethod.GET, ANY_URI).instruction(),
                strategy.decide(HttpMethod.GET, ANY_URI).instruction(),
                strategy.decide(HttpMethod.GET, ANY_URI).instruction(),
                strategy.decide(HttpMethod.GET, ANY_URI).instruction(),
                strategy.decide(HttpMethod.GET, ANY_URI).instruction()
        );
        assertThat(seen).containsExactly(
                FaultDecision.Instruction.PASS,
                FaultDecision.Instruction.PASS,
                FaultDecision.Instruction.INJECT_ERROR,
                FaultDecision.Instruction.PASS,
                FaultDecision.Instruction.PASS,
                FaultDecision.Instruction.INJECT_ERROR);
    }

    @Test
    void ruleOverridesDefaults() {
        FaultInjectionProperties props = enabled();
        props.getDefaults().setDelayMs(10);
        props.getDefaults().setErrorStatus(503);
        Rule r = new Rule();
        r.setName("ovr");
        r.setFault(FaultType.BOTH);
        r.setDelayMs(750L);
        r.setErrorStatus(504);
        r.setErrorMessage("boom");
        r.setProbability(1.0);
        props.getRules().add(r);

        FaultDecision d = alwaysTrigger(props).decide(HttpMethod.GET, ANY_URI);
        assertThat(d.instruction()).isEqualTo(FaultDecision.Instruction.INJECT_DELAY_AND_ERROR);
        assertThat(d.delay().toMillis()).isEqualTo(750L);
        assertThat(d.errorStatus()).isEqualTo(504);
        assertThat(d.errorMessage()).isEqualTo("boom");
    }

    @Test
    void countersTrackMatchesAndTriggers() {
        FaultInjectionProperties props = enabled();
        Rule r = delayRule("counted", TriggerMode.EVERY_N, 10);
        r.setEveryN(2);
        props.getRules().add(r);

        FaultDecisionStrategyImpl strategy = alwaysTrigger(props);
        for (int i = 0; i < 5; i++) {
            strategy.decide(HttpMethod.GET, ANY_URI);
        }
        var metrics = strategy.metricsSnapshot().get("counted");
        assertThat(metrics.matchCount()).isEqualTo(5);
        assertThat(metrics.triggerCount()).isEqualTo(2);
    }

    @Test
    void disabledRuleIsSkipped() {
        FaultInjectionProperties props = enabled();
        Rule r = delayRule("off", null, 50);
        r.setEnabled(false);
        props.getRules().add(r);

        assertThat(alwaysTrigger(props).decide(HttpMethod.GET, ANY_URI).instruction())
                .isEqualTo(FaultDecision.Instruction.PASS);
    }

    // ----- helpers -----

    private static FaultInjectionProperties enabled() {
        FaultInjectionProperties p = new FaultInjectionProperties();
        p.setEnabled(true);
        return p;
    }

    private static Rule delayRule(String name, TriggerMode mode, long delayMs) {
        Rule r = new Rule();
        r.setName(name);
        r.setFault(FaultType.DELAY);
        r.setDelayMs(delayMs);
        r.setMode(mode);
        r.setProbability(1.0);
        return r;
    }

    private static Rule errorRule(String name, TriggerMode mode) {
        Rule r = new Rule();
        r.setName(name);
        r.setFault(FaultType.ERROR);
        r.setMode(mode);
        r.setProbability(1.0);
        return r;
    }

    /** Strategy whose probability rolls always trigger. */
    private static FaultDecisionStrategyImpl alwaysTrigger(FaultInjectionProperties props) {
        DoubleSupplier zero = () -> 0.0d;
        return new FaultDecisionStrategyImpl(props, zero);
    }
}
