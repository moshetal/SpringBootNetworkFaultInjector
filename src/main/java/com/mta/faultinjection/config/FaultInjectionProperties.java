package com.mta.faultinjection.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Type-safe configuration mapped from the "fault.injection" namespace.
 * <p>
 * Fields in this class are bound from either application.yml or application.properties.
 */
@Data
@ConfigurationProperties(prefix = "fault.injection")
public class FaultInjectionProperties {
    /**
     * Enables or disables fault injection globally.
     */
    private boolean enabled = false;

    // TODO: Add more properties (e.g., default latency, per-service rules) as the library evolves.
}