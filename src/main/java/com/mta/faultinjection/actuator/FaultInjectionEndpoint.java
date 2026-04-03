package com.mta.faultinjection.actuator;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;

import java.util.Collections;
import java.util.Map;

/**
 * Spring Boot Actuator endpoint that exposes basic fault-injection management hooks.
 * The current implementation returns a simple status and accepts a no-op update.
 */
@Endpoint(id = "faultinjector")
public class FaultInjectionEndpoint {

    /**
     * Describes the current state of the fault-injection subsystem.
     *
     * Library behavior: returns a minimal status map. Implementations may extend this
     * to surface counters, rules, or current configuration.
     *
     * @return an immutable map describing current status
     */
    @ReadOperation
    public Map<String, Object> describe() {
        // TODO: Return real status information (enabled, rules summary, metrics)
        return Collections.singletonMap("status", "ok");
    }

    /**
     * Updates fault-injection configuration/state.
     *
     * Library behavior: treated as a no-op placeholder. Implementations may parse
     * the incoming payload and update rules accordingly.
     *
     * @param request arbitrary request body mapped to a key-value structure
     */
    @WriteOperation
    public void update(Map<String, Object> request) {
        // TODO: Apply updates from request payload
    }
}
