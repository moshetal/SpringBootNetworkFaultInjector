package com.example.faultinjection.core;

/**
 * Rule Engine (FaultInjectionManager)
 * Role: The "brain" of the system. It holds the active logic for deciding if a specific request should fail or be delayed.
 * Function: It receives request metadata (URL, Host), checks against the defined rules, and returns an instruction
 * (Inject Delay / Inject Error / Pass).
 *
 * Note: Structure only. No actual decision logic is implemented.
 */
public class FaultInjectionManager {

    public enum Instruction {
        INJECT_DELAY,
        INJECT_ERROR,
        PASS
    }

    /**
     * Placeholder for decision method. Real implementation would inspect URL/host and configuration.
     */
    public Instruction decide(String url, String host) {
        return Instruction.PASS; // No-op default for structure-only project
    }
}
