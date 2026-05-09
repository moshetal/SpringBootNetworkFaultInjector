package com.mta.faultinjection.telemetry;

/**
 * Snapshot of a single fault-injection decision recorded by
 * {@link FaultInjectionTelemetry} for the bundled UI's "recent events" log.
 * <p>
 * Stored fields are kept primitive/string so the record survives Jackson
 * serialization without custom modules.
 *
 * @param timestampMs   wall-clock time when the decision was made
 * @param ruleName      name of the rule that matched (never {@code null})
 * @param method        HTTP method as a string (e.g. {@code "GET"}); may be
 *                      {@code null} if the underlying client did not provide one
 * @param url           full request URL as a string
 * @param host          host extracted from the URL, useful for display filters
 * @param outcome       whether the rule merely matched or actually fired
 * @param faultType     fault that fired ({@code DELAY}/{@code ERROR}/{@code BOTH});
 *                      {@code null} for {@link Outcome#MATCH_NO_FIRE}
 * @param delayMs       injected delay in milliseconds; {@code 0} when no delay was applied
 * @param errorStatus   synthetic error status returned; {@code 0} when no error was injected
 */
public record FaultInjectionEvent(
        long timestampMs,
        String ruleName,
        String method,
        String url,
        String host,
        Outcome outcome,
        String faultType,
        long delayMs,
        int errorStatus
) {

    /** Whether the rule merely matched the request or actually injected a fault. */
    public enum Outcome {
        /** Rule matched but the trigger didn't fire (e.g. probability roll missed). */
        MATCH_NO_FIRE,
        /** Rule matched and the fault was injected. */
        FIRED
    }
}
