package com.mta.faultinjection.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Type-safe configuration mapped from the "fault.injection" namespace.
 * <p>
 * Add fields here to control behavior such as enablement, default latency, and
 * per-service rules. Spring Boot will bind values from application.yml/properties.
 */
@Data
@ConfigurationProperties(prefix = "fault.injection")
public class FaultInjectionProperties {
    // TODO: Define properties, e.g. enabled flag, default latency, and per-service rules.
    // private boolean enabled;
    // private Duration defaultLatency;
    // private Map<String, ServiceRule> services;
}