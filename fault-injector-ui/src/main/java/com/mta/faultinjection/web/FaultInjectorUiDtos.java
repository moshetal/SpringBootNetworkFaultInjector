package com.mta.faultinjection.web;

import java.util.List;

/** JSON bodies for the fault injector UI REST API. */
public final class FaultInjectorUiDtos {

    private FaultInjectorUiDtos() {}

    public static class EnabledDto {
        public Boolean enabled;
    }

    public static class ResetDto {
        public String name;
    }

    public static class RuleDto {
        public String name;
        public Boolean enabled;
        public String hostPattern;
        public String urlPattern;
        public List<String> methods;
        public String fault;
        public String mode;
        public Double probability;
        public Integer everyN;
        public Long delayMs;
        public Integer errorStatus;
        public String errorMessage;
    }
}
