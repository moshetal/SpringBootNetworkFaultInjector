package com.mta.faultinjection.protocol;

/** Sent by an agent on connect / reconnect. */
public record AgentRegister(
        String instanceId,
        String serviceName,
        String localUiPath,
        String appVersion,
        int protocolVersion) {

    public AgentRegister(String instanceId, String serviceName, String localUiPath, String appVersion) {
        this(instanceId, serviceName, localUiPath, appVersion, ProtocolVersion.CURRENT);
    }
}
