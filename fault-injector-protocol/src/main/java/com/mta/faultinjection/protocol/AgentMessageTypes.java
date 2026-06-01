package com.mta.faultinjection.protocol;

/** STOMP message type strings carried in {@link AgentMessage#type()}. */
public final class AgentMessageTypes {

    public static final String REGISTER = "REGISTER";
    public static final String HEARTBEAT = "HEARTBEAT";
    public static final String TELEMETRY = "TELEMETRY";
    public static final String COMMAND = "COMMAND";
    public static final String COMMAND_ACK = "COMMAND_ACK";

    private AgentMessageTypes() {}
}
