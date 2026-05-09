package com.mta.faultinjection.config;

import com.mta.faultinjection.core.FaultType;
import com.mta.faultinjection.core.TriggerMode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;

class FaultInjectionPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    com.mta.faultinjection.autoconfig.FaultInjectionAutoConfiguration.class
            ));

    @Test
    void bindsEnabledFlag() {
        contextRunner
                .withPropertyValues("fault.injection.enabled=true")
                .run(ctx -> {
                    FaultInjectionProperties props = ctx.getBean(FaultInjectionProperties.class);
                    assertThat(props.isEnabled()).isTrue();
                });
    }

    @Test
    void bindsDefaultsAndRulesList() {
        contextRunner
                .withPropertyValues(
                        "fault.injection.enabled=true",
                        "fault.injection.defaults.delay-ms=50",
                        "fault.injection.defaults.error-status=502",
                        "fault.injection.defaults.mode=EVERY_N",
                        "fault.injection.defaults.every-n=4",

                        "fault.injection.rules[0].name=slow-api",
                        "fault.injection.rules[0].host-pattern=api\\.example\\.com",
                        "fault.injection.rules[0].methods=GET,POST",
                        "fault.injection.rules[0].fault=DELAY",
                        "fault.injection.rules[0].mode=PROBABILITY",
                        "fault.injection.rules[0].probability=0.25",
                        "fault.injection.rules[0].delay-ms=300",

                        "fault.injection.rules[1].name=broken-api",
                        "fault.injection.rules[1].url-pattern=.*/broken/.*",
                        "fault.injection.rules[1].fault=ERROR",
                        "fault.injection.rules[1].every-n=3",
                        "fault.injection.rules[1].error-status=503"
                )
                .run(ctx -> {
                    FaultInjectionProperties props = ctx.getBean(FaultInjectionProperties.class);
                    assertThat(props.getDefaults().getDelayMs()).isEqualTo(50);
                    assertThat(props.getDefaults().getMode()).isEqualTo(TriggerMode.EVERY_N);
                    assertThat(props.getRules()).hasSize(2);

                    FaultInjectionProperties.Rule first = props.getRules().get(0);
                    assertThat(first.getName()).isEqualTo("slow-api");
                    assertThat(first.getFault()).isEqualTo(FaultType.DELAY);
                    assertThat(first.getProbability()).isEqualTo(0.25);
                    assertThat(first.getDelayMs()).isEqualTo(300L);
                    assertThat(first.safeMethods()).contains(HttpMethod.GET, HttpMethod.POST);

                    FaultInjectionProperties.Rule second = props.getRules().get(1);
                    assertThat(second.getFault()).isEqualTo(FaultType.ERROR);
                    assertThat(second.getEveryN()).isEqualTo(3);
                    assertThat(second.getErrorStatus()).isEqualTo(503);
                });
    }
}
