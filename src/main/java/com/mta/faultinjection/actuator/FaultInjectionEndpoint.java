package com.mta.faultinjection.actuator;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;

import java.util.Collections;
import java.util.Map;

/**
 * Management API (FaultInjectionEndpoint)
 * Role: A Spring Boot Actuator Endpoint.
 * Function: Exposes a REST-like API (e.g., /actuator/faultinjector) to allow runtime changes
 * (enable/disable faults, update error rates) without restarting the application.
 *
 * Note: Structure only. No runtime mutation logic implemented.
 */
@Endpoint(id = "faultinjector")
public class FaultInjectionEndpoint {

    @ReadOperation
    public Map<String, Object> describe() {
        // Placeholder response
        return Collections.singletonMap("status", "ok");
    }

    @WriteOperation
    public void update(Map<String, Object> request) {
        // Placeholder no-op update
    }
}
