package com.mta.faultinjection.core;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable result of a {@link FaultDecisionStrategy} evaluation.
 * <p>
 * Carries enough information for an interceptor or filter to know
 * whether to delay, short-circuit with an error, throw a network-level
 * exception, or pass through.
 */
public final class FaultDecision {

    /**
     * Action an interceptor/filter should take for a given request.
     */
    public enum Instruction {
        /** Proceed without modification. */
        PASS,
        /** Sleep for {@link #delay()} before proceeding. */
        INJECT_DELAY,
        /** Return a synthetic error response instead of proceeding. */
        INJECT_ERROR,
        /** Sleep for {@link #delay()} and then return a synthetic error response. */
        INJECT_DELAY_AND_ERROR,
        /**
         * Throw a network-level {@link java.io.IOException} subclass instead of
         * proceeding.  When {@link #delay()} is non-zero the interceptor sleeps
         * first (simulating a timeout), then throws.  The specific exception
         * type is described by {@link #networkFaultType()}.
         */
        INJECT_NETWORK_FAULT
    }

    private static final FaultDecision PASS = new FaultDecision(
            Instruction.PASS, Duration.ZERO, 0, null, null, null);

    private final Instruction instruction;
    private final Duration delay;
    private final int errorStatus;
    private final String errorMessage;
    private final NetworkFaultType networkFaultType;
    private final String ruleName;

    private FaultDecision(
            Instruction instruction,
            Duration delay,
            int errorStatus,
            String errorMessage,
            NetworkFaultType networkFaultType,
            String ruleName) {
        this.instruction = Objects.requireNonNull(instruction, "instruction");
        this.delay = delay == null ? Duration.ZERO : delay;
        this.errorStatus = errorStatus;
        this.errorMessage = errorMessage;
        this.networkFaultType = networkFaultType;
        this.ruleName = ruleName;
    }

    // ----- factory methods -----

    /** A no-op decision; interceptors should pass the request through. */
    public static FaultDecision pass() {
        return PASS;
    }

    /** Inject latency only. */
    public static FaultDecision delay(Duration delay) {
        return new FaultDecision(Instruction.INJECT_DELAY, delay, 0, null, null, null);
    }

    /** Short-circuit with a synthetic error response. */
    public static FaultDecision error(int status, String message) {
        return new FaultDecision(Instruction.INJECT_ERROR, Duration.ZERO, status, message, null, null);
    }

    /** Delay, then short-circuit with a synthetic error response. */
    public static FaultDecision delayThenError(Duration delay, int status, String message) {
        return new FaultDecision(Instruction.INJECT_DELAY_AND_ERROR, delay, status, message, null, null);
    }

    /**
     * Throw a network-level exception immediately (no preceding sleep).
     * Use for {@link NetworkFaultType#CONNECTION_REFUSED},
     * {@link NetworkFaultType#CONNECTION_RESET}, and
     * {@link NetworkFaultType#DNS_FAILURE}.
     */
    public static FaultDecision networkFault(NetworkFaultType type) {
        Objects.requireNonNull(type, "type");
        return new FaultDecision(Instruction.INJECT_NETWORK_FAULT, Duration.ZERO, 0, null, type, null);
    }

    /**
     * Sleep for {@code delay}, then throw a network-level exception.
     * Use for {@link NetworkFaultType#CONNECTION_TIMEOUT} and
     * {@link NetworkFaultType#READ_TIMEOUT} to realistically simulate a
     * hanging connection that eventually times out.
     */
    public static FaultDecision delayThenNetworkFault(Duration delay, NetworkFaultType type) {
        Objects.requireNonNull(type, "type");
        return new FaultDecision(Instruction.INJECT_NETWORK_FAULT, delay, 0, null, type, null);
    }

    // ----- accessors -----

    public Instruction instruction() {
        return instruction;
    }

    public Duration delay() {
        return delay;
    }

    public int errorStatus() {
        return errorStatus;
    }

    public String errorMessage() {
        return errorMessage;
    }

    /**
     * The network fault variant to simulate; non-null only when
     * {@link #instruction()} is {@link Instruction#INJECT_NETWORK_FAULT}.
     */
    public NetworkFaultType networkFaultType() {
        return networkFaultType;
    }

    public boolean hasDelay() {
        return !delay.isZero() && (
                instruction == Instruction.INJECT_DELAY
                || instruction == Instruction.INJECT_DELAY_AND_ERROR
                || instruction == Instruction.INJECT_NETWORK_FAULT);
    }

    public boolean hasError() {
        return instruction == Instruction.INJECT_ERROR || instruction == Instruction.INJECT_DELAY_AND_ERROR;
    }

    /** {@code true} when the interceptor should throw a network-level IOException. */
    public boolean hasNetworkFault() {
        return instruction == Instruction.INJECT_NETWORK_FAULT;
    }

    /** Optional name of the rule that produced this decision; {@code null} when not set. */
    public String ruleName() {
        return ruleName;
    }

    /** Return a copy of this decision with {@code ruleName} populated. */
    public FaultDecision withRuleName(String ruleName) {
        return new FaultDecision(instruction, delay, errorStatus, errorMessage, networkFaultType, ruleName);
    }

    @Override
    public String toString() {
        return "FaultDecision{instruction=" + instruction
                + ", delay=" + delay
                + ", errorStatus=" + errorStatus
                + ", errorMessage='" + errorMessage + '\''
                + ", networkFaultType=" + networkFaultType + '}';
    }
}
