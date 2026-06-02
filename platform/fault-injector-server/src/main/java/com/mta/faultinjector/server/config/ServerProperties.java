package com.mta.faultinjector.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fault.injection.server")
public class ServerProperties {

    private long heartbeatTtlMs = 45_000L;
    private long commandTimeoutMs = 10_000L;
    private int telemetryRetentionDays = 7;
    private Console console = new Console();

    public long getHeartbeatTtlMs() {
        return heartbeatTtlMs;
    }

    public void setHeartbeatTtlMs(long heartbeatTtlMs) {
        this.heartbeatTtlMs = heartbeatTtlMs;
    }

    public long getCommandTimeoutMs() {
        return commandTimeoutMs;
    }

    public void setCommandTimeoutMs(long commandTimeoutMs) {
        this.commandTimeoutMs = commandTimeoutMs;
    }

    public int getTelemetryRetentionDays() {
        return telemetryRetentionDays;
    }

    public void setTelemetryRetentionDays(int telemetryRetentionDays) {
        this.telemetryRetentionDays = telemetryRetentionDays;
    }

    public Console getConsole() {
        return console;
    }

    public void setConsole(Console console) {
        this.console = console;
    }

    public static class Console {
        private boolean enabled = true;
        private String path = "/console";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }
    }
}
