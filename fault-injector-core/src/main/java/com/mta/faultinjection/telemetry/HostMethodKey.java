package com.mta.faultinjection.telemetry;

/** Coarser (host, method) key used by circuit-breaker observation. */
public record HostMethodKey(String host, String method) {}
