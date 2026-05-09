package com.mta.faultinjection.core;

/**
 * How a fault-injection rule decides when to fire.
 */
public enum TriggerMode {
    /** Roll a random number per request against the rule's probability. */
    PROBABILITY,
    /** Fire deterministically on every Nth matching request. */
    EVERY_N
}
