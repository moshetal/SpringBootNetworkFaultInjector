package com.mta.faultinjection.api;

/**
 * Stable JSON keys for actuator and UI snapshots of fault-injection state.
 */
public final class FaultInjectorViewJsonKeys {

    public static final String ENABLED = "enabled";
    public static final String DEFAULTS = "defaults";
    public static final String RULES = "rules";
    public static final String UI = "ui";
    public static final String PATH = "path";
    public static final String POLL_MS = "pollMs";
    public static final String EVENT_BUFFER_SIZE = "eventBufferSize";
    public static final String TIMESERIES_BUCKET_SECONDS = "timeseriesBucketSeconds";
    public static final String TIMESERIES_BUCKETS = "timeseriesBuckets";
    public static final String NAME = "name";
    public static final String FAULT = "fault";
    public static final String MODE = "mode";
    public static final String HOST_PATTERN = "hostPattern";
    public static final String URL_PATTERN = "urlPattern";
    public static final String METHODS = "methods";
    public static final String PROBABILITY = "probability";
    public static final String EVERY_N = "everyN";
    public static final String DELAY_MS = "delayMs";
    public static final String ERROR_STATUS = "errorStatus";
    public static final String ERROR_MESSAGE = "errorMessage";
    public static final String NETWORK_FAULT_TYPE = "networkFaultType";
    public static final String MATCH_COUNT = "matchCount";
    public static final String TRIGGER_COUNT = "triggerCount";
    public static final String STATUS = "status";
    public static final String TOTALS = "totals";
    public static final String ACTIVE_RULES = "activeRules";
    public static final String BUCKETS = "buckets";
    public static final String RULE_NAMES = "ruleNames";
    public static final String COUNT = "count";
    public static final String EVENTS = "events";
    public static final String RESET = "reset";
    public static final String REMOVED = "removed";
    public static final String MATCHES = "matches";
    public static final String TRIGGERS = "triggers";
    public static final String PER_RULE = "perRule";
    public static final String START_EPOCH_MS = "startEpochMs";
    public static final String WIDTH_MS = "widthMs";
    public static final String CONFIG = "config";
    public static final String METRICS = "metrics";
    public static final String TIMESERIES = "timeseries";

    // ----- resilience metrics -----
    public static final String RESILIENCE = "resilience";
    public static final String RESILIENCE_CONFIG = "config";
    public static final String RETRY_OBSERVATIONS = "retryObservations";
    public static final String CIRCUIT_BREAKER_OBSERVATIONS = "circuitBreakerObservations";
    public static final String DELAY_OBSERVATIONS = "delayObservations";
    public static final String RETRY_WINDOW_MS = "retryWindowMs";
    public static final String CB_THRESHOLD = "cbThreshold";
    public static final String CB_WINDOW_MS = "cbWindowMs";
    public static final String HOST = "host";
    public static final String URL_PATH = "urlPath";
    public static final String FAULT_EPOCH_MS = "faultEpochMs";
    public static final String OBSERVED_RETRIES = "observedRetries";
    public static final String THRESHOLD = "threshold";
    public static final String THRESHOLD_REACHED_AT_MS = "thresholdReachedAtMs";
    public static final String POST_WINDOW_CALL_COUNT = "postWindowCallCount";
    public static final String INJECTED_DELAY_MS = "injectedDelayMs";
    public static final String OBSERVED_WAIT_MS = "observedWaitMs";
    public static final String COMPLETED_SUCCESSFULLY = "completedSuccessfully";
    public static final String TIMESTAMP_MS = "timestampMs";

    private FaultInjectorViewJsonKeys() {}
}
