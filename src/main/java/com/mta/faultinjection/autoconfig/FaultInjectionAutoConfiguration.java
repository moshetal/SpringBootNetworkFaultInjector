package com.mta.faultinjection.autoconfig;

import com.mta.faultinjection.actuator.FaultInjectionEndpoint;
import com.mta.faultinjection.config.FaultInjectionProperties;
import com.mta.faultinjection.core.FaultInjectionManager;
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
 * Auto-Configuration (FaultInjectionAutoConfiguration)
 * Role: The glue that makes the library plug-and-play.
 * Function: Automatically detects if RestTemplate or WebClient are present and registers the
 * necessary Interceptors and Beans without requiring code changes from the user.
 *
 * Note: Structure only. No fault injection logic implemented.
 */
@AutoConfiguration
@EnableConfigurationProperties(FaultInjectionProperties.class)
public class FaultInjectionAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(search = SearchStrategy.CURRENT)
    public FaultInjectionManager faultInjectionManager() {
        return new FaultInjectionManager();
    }

    // Expose Actuator endpoint bean if Actuator is on classpath
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
        @Bean
        @ConditionalOnMissingBean
        public FaultInjectionInterceptor faultInjectionInterceptor() {
            return new FaultInjectionInterceptor();
        }

        @Bean
        public RestTemplateCustomizer faultInjectionRestTemplateCustomizer(FaultInjectionInterceptor interceptor) {
            return restTemplate -> restTemplate.getInterceptors().add(interceptor);
        }
    }

    // RestClient support (Spring Framework 6.1+)
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RestClient.class)
    static class RestClientAutoConfig {
        @Bean
        @ConditionalOnMissingBean
        public FaultInjectionInterceptor faultInjectionInterceptor() {
            return new FaultInjectionInterceptor();
        }

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
        @Bean
        @ConditionalOnMissingBean
        public FaultInjectionFilter faultInjectionFilter() {
            return new FaultInjectionFilter();
        }
        // Note: Intentionally not wiring the filter into WebClient.Builder to keep structure-only,
        // and to avoid compile-time coupling in environments without WebFlux.
    }
}
