package com.mta.faultinjection.autoconfig;

import static org.assertj.core.api.Assertions.assertThat;

import com.mta.faultinjection.telemetry.FaultInjectionTelemetry;
import com.mta.faultinjection.web.FaultInjectorUiController;
import com.mta.faultinjection.web.FaultInjectorUiResourceConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

/**
 * Smoke tests for {@link FaultInjectionUiAutoConfiguration} — confirming the UI
 * beans are wired by default and can be opted out via configuration.
 */
class FaultInjectionUiAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    FaultInjectionAutoConfiguration.class, FaultInjectionUiAutoConfiguration.class));

    @Test
    void wiresUiBeansWhenWebIsOnClasspath() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(FaultInjectionTelemetry.class);
            assertThat(ctx).hasSingleBean(FaultInjectorUiController.class);
            assertThat(ctx).hasSingleBean(FaultInjectorUiResourceConfig.class);
        });
    }

    @Test
    void backsOffWhenUiDisabled() {
        runner.withPropertyValues("fault.injection.ui.enabled=false").run(ctx -> {
            assertThat(ctx).doesNotHaveBean(FaultInjectionTelemetry.class);
            assertThat(ctx).doesNotHaveBean(FaultInjectorUiController.class);
            assertThat(ctx).doesNotHaveBean(FaultInjectorUiResourceConfig.class);
        });
    }
}
