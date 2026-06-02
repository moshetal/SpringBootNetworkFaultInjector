package com.mta.faultinjector.server.registry;

import com.mta.faultinjector.server.config.ServerProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RegistryEvictionJob {

    private final InstanceRegistry registry;
    private final ServerProperties properties;

    public RegistryEvictionJob(InstanceRegistry registry, ServerProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    @Scheduled(fixedDelay = 15_000)
    public void evictStale() {
        registry.evictStale(properties.getHeartbeatTtlMs());
    }
}
