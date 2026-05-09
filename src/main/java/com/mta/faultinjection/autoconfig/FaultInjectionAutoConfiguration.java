package com.mta.faultinjection.autoconfig;

import com.mta.faultinjection.actuator.FaultInjectionEndpoint;
import com.mta.faultinjection.config.FaultInjectionProperties;
import com.mta.faultinjection.core.FaultDecisionStrategy;
import com.mta.faultinjection.core.FaultDecisionStrategyImpl;
import com.mta.faultinjection.interceptor.FaultInjectionFilter;
import com.mta.faultinjection.interceptor.FaultInjectionInterceptor;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.SearchStrategy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Auto-configuration that registers fault-injection components for every
 * supported HTTP client present on the classpath.
 */
@AutoConfiguration
@EnableConfigurationProperties(FaultInjectionProperties.class)
public class FaultInjectionAutoConfiguration {

    /**
     * Default, configuration-driven decision strategy. Backs off if the
     * application provides its own {@link FaultDecisionStrategy} bean.
     */
    @Bean
    @ConditionalOnMissingBean(value = FaultDecisionStrategy.class, search = SearchStrategy.CURRENT)
    public FaultDecisionStrategyImpl faultDecisionStrategy(FaultInjectionProperties properties) {
        return new FaultDecisionStrategyImpl(properties);
    }

    /**
     * Shared interceptor used by both RestTemplate and RestClient. Only created
     * when at least one sync HTTP client is on the classpath.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = {"org.springframework.web.client.RestTemplate"})
    public FaultInjectionInterceptor faultInjectionInterceptor(FaultDecisionStrategy strategy) {
        return new FaultInjectionInterceptor(strategy);
    }

    /**
     * Exposes the Actuator endpoint (id = "faultinjector") when Spring Boot
     * Actuator is on the classpath.
     */
    @Bean
    @ConditionalOnClass(Endpoint.class)
    @ConditionalOnMissingBean
    public FaultInjectionEndpoint faultInjectionEndpoint(FaultInjectionProperties properties,
                                                         FaultDecisionStrategy strategy) {
        return new FaultInjectionEndpoint(properties, strategy);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RestTemplate.class)
    static class RestTemplateAutoConfig {
        @Bean
        public RestTemplateCustomizer faultInjectionRestTemplateCustomizer(FaultInjectionInterceptor interceptor) {
            return restTemplate -> {
                if (!restTemplate.getInterceptors().contains(interceptor)) {
                    restTemplate.getInterceptors().add(interceptor);
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RestClient.class)
    static class RestClientAutoConfig {
        @Bean
        public RestClientCustomizer faultInjectionRestClientCustomizer(FaultInjectionInterceptor interceptor) {
            return builder -> builder.requestInterceptor(interceptor);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(WebClient.class)
    static class WebClientAutoConfig {
        @Bean
        @ConditionalOnMissingBean
        public FaultInjectionFilter faultInjectionFilter(FaultDecisionStrategy strategy) {
            return new FaultInjectionFilter(strategy);
        }

        @Bean
        public WebClientCustomizer faultInjectionWebClientCustomizer(FaultInjectionFilter filter) {
            return builder -> builder.filter(filter);
        }
    }
}
