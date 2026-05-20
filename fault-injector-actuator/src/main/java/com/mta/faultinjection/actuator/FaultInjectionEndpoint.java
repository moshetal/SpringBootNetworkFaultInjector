package com.mta.faultinjection.actuator;

import java.util.Map;
import java.util.Objects;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.lang.Nullable;

/**
 * Actuator endpoint (id = "faultinjector") that exposes the live state of the
 * fault-injection subsystem and a small set of safe, targeted mutations for
 * runtime control.
 */
@Endpoint(id = "faultinjector")
public class FaultInjectionEndpoint {

    private final FaultInjectionActuatorService actuatorService;

    public FaultInjectionEndpoint(FaultInjectionActuatorService actuatorService) {
        this.actuatorService = Objects.requireNonNull(actuatorService, "actuatorService");
    }

    @ReadOperation
    public Map<String, Object> describe() {
        return actuatorService.describeSnapshot();
    }

    @WriteOperation
    public Map<String, Object> update(
            String action, @Nullable String name, @Nullable Boolean enabled, @Nullable Double probability) {
        return actuatorService.applyWrite(action, name, enabled, probability);
    }
}
