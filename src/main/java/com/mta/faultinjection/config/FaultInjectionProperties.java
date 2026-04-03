package com.mta.faultinjection.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration Manager (FaultInjectionProperties)
 * Role: Loads configuration from application.yml (e.g., default latency, error rates, enabled/disabled flags).
 * Function: Maps the hierarchical YAML structure into Java POJO objects for easy access by the code.
 *
 * Note: Structure only. No logic implemented.
 */
@Data
@ConfigurationProperties(prefix = "fault.injection")
public class FaultInjectionProperties {
    // Example placeholders for structure; actual fields and logic intentionally omitted.
    // private boolean enabled;
    // private Duration defaultLatency;
    // private Map<String, ServiceRule> services;
}