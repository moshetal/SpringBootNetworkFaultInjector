package com.mta.faultinjection.sidecar;

import com.mta.faultinjection.config.FaultInjectionProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.io.FileSystemResource;

public final class SidecarConfigLoader {

    private SidecarConfigLoader() {}

    public static FaultInjectionProperties load(Path configPath) {
        if (configPath == null || !Files.isRegularFile(configPath)) {
            throw new IllegalArgumentException("config file not found: " + configPath);
        }
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(configPath.toFile()));
        Properties flat;
        try {
            flat = yaml.getObject();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("config could not be parsed: " + configPath, e);
        }
        if (flat == null) {
            throw new IllegalArgumentException("config could not be parsed: " + configPath);
        }
        MapConfigurationPropertySource source = new MapConfigurationPropertySource();
        flat.forEach((k, v) -> source.put(k.toString(), v));
        try {
            return new Binder(source)
                    .bind("fault.injection", FaultInjectionProperties.class)
                    .orElseThrow(() -> new IllegalArgumentException("config missing fault.injection: " + configPath));
        } catch (BindException e) {
            throw new IllegalArgumentException("config bind failed: " + configPath, e);
        }
    }
}
