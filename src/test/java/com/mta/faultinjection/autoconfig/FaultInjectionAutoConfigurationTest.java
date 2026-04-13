package com.mta.faultinjection.autoconfig;

import com.mta.faultinjection.config.FaultInjectionProperties;
import com.mta.faultinjection.core.FaultDecisionStrategy;
import com.mta.faultinjection.core.FaultDecisionStrategyImpl;
import com.mta.faultinjection.interceptor.FaultInjectionFilter;
import com.mta.faultinjection.interceptor.FaultInjectionInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke tests verifying the auto-configuration wires the expected beans across
 * the supported HTTP clients and honors user-provided overrides.
 */
class FaultInjectionAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FaultInjectionAutoConfiguration.class));

    @Test
    void createsDefaultDecisionStrategyAndInterceptor() {
        contextRunner.run(ctx -> {
            assertThat(ctx).hasSingleBean(FaultDecisionStrategyImpl.class);
            assertThat(ctx).hasSingleBean(FaultInjectionInterceptor.class);
            assertThat(ctx).hasSingleBean(RestTemplateCustomizer.class);
        });
    }

    @Test
    void registersWebClientCustomizerAndFilter() {
        contextRunner.run(ctx -> {
            assertThat(ctx).hasSingleBean(FaultInjectionFilter.class);
            assertThat(ctx).hasSingleBean(WebClientCustomizer.class);
        });
    }

    @Test
    void backsOffWhenUserProvidesStrategyBean() {
        contextRunner
                .withUserConfiguration(UserStrategyConfig.class)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(FaultDecisionStrategy.class);
                    assertThat(ctx.getBean(FaultDecisionStrategy.class))
                            .isSameAs(UserStrategyConfig.USER_INSTANCE);
                });
    }

    @org.springframework.context.annotation.Configuration
    static class UserStrategyConfig {
        static final FaultDecisionStrategy USER_INSTANCE =
                new FaultDecisionStrategyImpl(new FaultInjectionProperties());

        @org.springframework.context.annotation.Bean
        FaultDecisionStrategy faultDecisionStrategy() {
            return USER_INSTANCE;
        }
    }
}
