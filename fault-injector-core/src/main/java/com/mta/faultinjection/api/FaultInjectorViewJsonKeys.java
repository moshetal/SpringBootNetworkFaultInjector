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

    private FaultInjectorViewJsonKeys() {}
}
