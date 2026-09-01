package com.mta.faultinjection.sidecar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mta.faultinjection.config.FaultInjectionProperties;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class SidecarConfigLoaderTest {

    @Test
    void loadsEnabledRuleFromYaml() throws Exception {
        Path yaml = Path.of(new ClassPathResource("sidecar-always-delay.yml").getFile().getAbsolutePath());
        FaultInjectionProperties props = SidecarConfigLoader.load(yaml);
        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getRules()).hasSize(1);
        assertThat(props.getRules().get(0).getName()).isEqualTo("always-delay");
        assertThat(props.getRules().get(0).getDelayMs()).isEqualTo(50L);
    }

    @Test
    void missingFileThrows() {
        assertThatThrownBy(() -> SidecarConfigLoader.load(Path.of("no-such-file.yml")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("config");
    }
}
