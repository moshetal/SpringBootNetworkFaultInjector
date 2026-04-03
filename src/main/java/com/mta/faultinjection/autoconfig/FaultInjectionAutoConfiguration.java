package com.mta.faultinjection.autoconfig;

import com.mta.faultinjection.actuator.FaultInjectionEndpoint;
import com.mta.faultinjection.config.FaultInjectionProperties;
import com.mta.faultinjection.core.FaultDecisionStrategyImpl;
import com.mta.faultinjection.interceptor.FaultInjectionFilter;
import com.mta.faultinjection.interceptor.FaultInjectionInterceptor;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.SearchStrategy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Auto-configuration that registers fault-injection components when compatible HTTP clients
 * are present on the classpath. Provides beans for RestTemplate, RestClient, WebClient,
 * a FaultDecisionStrategyImpl, and an optional Actuator endpoint.
 */
@AutoConfiguration
@EnableConfigurationProperties(FaultInjectionProperties.class)
public class FaultInjectionAutoConfiguration {

    /**
     * Provides the default {@link FaultDecisionStrategyImpl} used by client interceptors/filters
     * to decide how to handle outbound calls.
     *
     * Library behavior: creates a no-op manager by default and backs off if the application
     * defines its own bean of the same type in the current context.
     *
     * @return the default manager instance
     */
    @Bean
    @ConditionalOnMissingBean(search = SearchStrategy.CURRENT)
    public FaultDecisionStrategyImpl faultInjectionManager() {
        return new FaultDecisionStrategyImpl();
    }

    /**
     * Exposes the optional Actuator endpoint (id = "faultinjector") when Spring Boot Actuator
     * is present on the classpath.
     *
     * @return the endpoint bean
     */
    @Bean
    @ConditionalOnClass(Endpoint.class)
    @ConditionalOnMissingBean
    public FaultInjectionEndpoint faultInjectionEndpoint() {
        return new FaultInjectionEndpoint();
    }

    // RestTemplate support
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RestTemplate.class)
    static class RestTemplateAutoConfig {
        /**
         * Provides the RestTemplate interceptor used to apply fault-injection behavior.
         *
         * @return the interceptor bean
         */
        @Bean
        @ConditionalOnMissingBean
        public FaultInjectionInterceptor faultInjectionInterceptor() {
            return new FaultInjectionInterceptor();
        }

        /**
         * Registers the interceptor with RestTemplate instances created via RestTemplateBuilder.
         *
         * @param interceptor the interceptor bean to add
         * @return a customizer that augments RestTemplate with the interceptor
         */
        @Bean
        public RestTemplateCustomizer faultInjectionRestTemplateCustomizer(FaultInjectionInterceptor interceptor) {
            return restTemplate -> restTemplate.getInterceptors().add(interceptor);
        }
    }

    // RestClient support (Spring Framework 6.1+)
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RestClient.class)
    static class RestClientAutoConfig {
        /**
         * Provides the RestClient interceptor used to apply fault-injection behavior.
         *
         * @return the interceptor bean
         */
        @Bean
        @ConditionalOnMissingBean
        public FaultInjectionInterceptor faultInjectionInterceptor() {
            return new FaultInjectionInterceptor();
        }

        /**
         * Registers the interceptor with RestClient.Builder via a RestClientCustomizer.
         *
         * @param interceptor the interceptor bean to register
         * @return a customizer that augments RestClient.Builder
         */
        @Bean
        public RestClientCustomizer faultInjectionRestClientCustomizer(FaultInjectionInterceptor interceptor) {
            return builder -> builder.requestInterceptor(interceptor);
        }
    }

    // WebClient support
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(WebClient.class)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    static class WebClientAutoConfig {
        /**
         * Provides a WebClient ExchangeFilterFunction hook for fault injection.
         *
         * @return the filter bean
         */
        @Bean
        @ConditionalOnMissingBean
        public FaultInjectionFilter faultInjectionFilter() {
            return new FaultInjectionFilter();
        }
        // TODO: Consider wiring the filter into WebClient.Builder automatically when WebFlux is present.
    }
}
