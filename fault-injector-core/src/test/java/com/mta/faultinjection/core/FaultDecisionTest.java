package com.mta.faultinjection.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class FaultDecisionTest {

    @Test
    void factoriesReturnNullRuleNameByDefault() {
        assertThat(FaultDecision.pass().ruleName()).isNull();
        assertThat(FaultDecision.delay(Duration.ofMillis(10)).ruleName()).isNull();
        assertThat(FaultDecision.error(503, "x").ruleName()).isNull();
        assertThat(FaultDecision.delayThenError(Duration.ofMillis(5), 502, "x").ruleName()).isNull();
    }

    @Test
    void withRuleNameProducesCopyWithSameInstructionAndPayload() {
        FaultDecision base = FaultDecision.delayThenError(Duration.ofMillis(25), 502, "boom");
        FaultDecision tagged = base.withRuleName("flaky-orders");

        assertThat(tagged.ruleName()).isEqualTo("flaky-orders");
        assertThat(tagged.instruction()).isEqualTo(base.instruction());
        assertThat(tagged.delay()).isEqualTo(base.delay());
        assertThat(tagged.errorStatus()).isEqualTo(base.errorStatus());
        assertThat(tagged.errorMessage()).isEqualTo(base.errorMessage());
        // original is unchanged
        assertThat(base.ruleName()).isNull();
    }
}
