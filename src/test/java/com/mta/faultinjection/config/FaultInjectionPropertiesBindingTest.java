package com.mta.faultinjection.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class FaultInjectionPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    com.mta.faultinjection.autoconfig.FaultInjectionAutoConfiguration.class
            ));

    @Test
    void bindsFromApplicationPropertiesStyleKeys() {
        contextRunner
                .withPropertyValues("fault.injection.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(FaultInjectionProperties.class);
                    FaultInjectionProperties props = ctx.getBean(FaultInjectionProperties.class);
                    assertThat(props.isEnabled()).isTrue();
                });
    }
}
