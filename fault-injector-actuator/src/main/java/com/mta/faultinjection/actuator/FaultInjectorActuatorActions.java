package com.mta.faultinjection.actuator;

/**
 * {@code action} values accepted by the faultinjector actuator write operation.
 */
public final class FaultInjectorActuatorActions {

    public static final String ENABLE = "enable";
    public static final String DISABLE = "disable";
    public static final String SET_RULE_ENABLED = "setRuleEnabled";
    public static final String SET_PROBABILITY = "setProbability";

    private FaultInjectorActuatorActions() {}
}
