package com.example.faultinjection.autoconfig;

import com.example.faultinjection.core.FaultInjectionManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Minimal smoke tests to ensure the starter auto-configuration loads
 * without requiring optional dependencies like Web, WebFlux, or Actuator.
 */
class FaultInjectionAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FaultInjectionAutoConfiguration.class));

    @Test
    void shouldCreateFaultInjectionManagerBeanByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(FaultInjectionManager.class);
        });
    }

    @Test
    void shouldBackOffIfUserDefinesCustomManager() {
        contextRunner
                .withUserConfiguration(UserProvidedManagerConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(FaultInjectionManager.class);
                    // Ensure the bean present is the user-defined one
                    assertThat(context.getBean(FaultInjectionManager.class))
                            .isSameAs(UserProvidedManagerConfig.USER_MANAGER_INSTANCE);
                });
    }

    @org.springframework.context.annotation.Configuration
    static class UserProvidedManagerConfig {
        static final FaultInjectionManager USER_MANAGER_INSTANCE = new FaultInjectionManager();

        @org.springframework.context.annotation.Bean
        FaultInjectionManager faultInjectionManager() {
            return USER_MANAGER_INSTANCE;
        }
    }
}
