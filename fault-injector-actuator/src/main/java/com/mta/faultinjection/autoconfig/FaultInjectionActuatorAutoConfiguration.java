package com.mta.faultinjection.autoconfig;

import com.mta.faultinjection.actuator.FaultInjectionActuatorService;
import com.mta.faultinjection.actuator.FaultInjectionEndpoint;
import com.mta.faultinjection.config.FaultInjectionProperties;
import com.mta.faultinjection.core.FaultDecisionStrategy;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@AutoConfigureAfter(FaultInjectionAutoConfiguration.class)
@ConditionalOnClass(Endpoint.class)
public class FaultInjectionActuatorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public FaultInjectionActuatorService faultInjectionActuatorService(
            FaultInjectionProperties properties, FaultDecisionStrategy strategy) {
        return new FaultInjectionActuatorService(properties, strategy);
    }

    @Bean
    @ConditionalOnMissingBean
    public FaultInjectionEndpoint faultInjectionEndpoint(FaultInjectionActuatorService actuatorService) {
        return new FaultInjectionEndpoint(actuatorService);
    }
}
