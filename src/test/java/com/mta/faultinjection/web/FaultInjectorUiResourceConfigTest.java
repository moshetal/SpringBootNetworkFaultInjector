package com.mta.faultinjection.web;

import com.mta.faultinjection.config.FaultInjectionProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FaultInjectorUiResourceConfigTest {

    @Test
    void defaultsToFaultInjectorPath() {
        FaultInjectionProperties props = new FaultInjectionProperties();
        FaultInjectorUiResourceConfig cfg = new FaultInjectorUiResourceConfig(props);
        assertThat(cfg.getBasePath()).isEqualTo("/fault-injector");
    }

    @Test
    void normalizesLeadingAndTrailingSlashes() {
        FaultInjectionProperties props = new FaultInjectionProperties();
        props.getUi().setPath("custom-path/");
        FaultInjectorUiResourceConfig cfg = new FaultInjectorUiResourceConfig(props);
        assertThat(cfg.getBasePath()).isEqualTo("/custom-path");
    }

    @Test
    void blankPathFallsBackToDefault() {
        FaultInjectionProperties props = new FaultInjectionProperties();
        props.getUi().setPath(" ");
        FaultInjectorUiResourceConfig cfg = new FaultInjectorUiResourceConfig(props);
        assertThat(cfg.getBasePath()).isEqualTo("/fault-injector");
    }
}
