package com.mta.faultinjection.protocol;

import java.util.List;

/** Aggregated fan-out result returned by the server REST layer. */
public record CommandResult(int applied, int failed, List<InstanceResult> results) {

    public record InstanceResult(String instanceId, boolean ok, String error) {}
}
