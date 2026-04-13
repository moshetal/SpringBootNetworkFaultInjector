package com.mta.faultinjection.core;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable result of a {@link FaultDecisionStrategy} evaluation.
 * <p>
 * Carries enough information for an interceptor or filter to know
 * whether to delay, short-circuit with an error, or pass through.
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
        INJECT_DELAY_AND_ERROR
    }

    private static final FaultDecision PASS = new FaultDecision(Instruction.PASS, Duration.ZERO, 0, null);

    private final Instruction instruction;
    private final Duration delay;
    private final int errorStatus;
    private final String errorMessage;

    private FaultDecision(Instruction instruction, Duration delay, int errorStatus, String errorMessage) {
        this.instruction = Objects.requireNonNull(instruction, "instruction");
        this.delay = delay == null ? Duration.ZERO : delay;
        this.errorStatus = errorStatus;
        this.errorMessage = errorMessage;
    }

    /** A no-op decision; interceptors should pass the request through. */
    public static FaultDecision pass() {
        return PASS;
    }

    /** Inject latency only. */
    public static FaultDecision delay(Duration delay) {
        return new FaultDecision(Instruction.INJECT_DELAY, delay, 0, null);
    }

    /** Short-circuit with a synthetic error response. */
    public static FaultDecision error(int status, String message) {
        return new FaultDecision(Instruction.INJECT_ERROR, Duration.ZERO, status, message);
    }

    /** Delay, then short-circuit with a synthetic error response. */
    public static FaultDecision delayThenError(Duration delay, int status, String message) {
        return new FaultDecision(Instruction.INJECT_DELAY_AND_ERROR, delay, status, message);
    }

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

    public boolean hasDelay() {
        return instruction == Instruction.INJECT_DELAY || instruction == Instruction.INJECT_DELAY_AND_ERROR;
    }

    public boolean hasError() {
        return instruction == Instruction.INJECT_ERROR || instruction == Instruction.INJECT_DELAY_AND_ERROR;
    }

    @Override
    public String toString() {
        return "FaultDecision{" +
                "instruction=" + instruction +
                ", delay=" + delay +
                ", errorStatus=" + errorStatus +
                ", errorMessage='" + errorMessage + '\'' +
                '}';
    }
}
