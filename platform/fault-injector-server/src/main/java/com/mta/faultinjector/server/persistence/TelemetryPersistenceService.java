package com.mta.faultinjector.server.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.mta.faultinjection.protocol.TelemetryBatch;
import com.mta.faultinjector.server.config.ServerProperties;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class TelemetryPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryPersistenceService.class);

    private final JdbcTemplate jdbc;
    private final ServerProperties properties;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "telemetry-persist");
        t.setDaemon(true);
        return t;
    });

    public TelemetryPersistenceService(JdbcTemplate jdbc, ServerProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    public void persistAsync(TelemetryBatch batch) {
        executor.submit(() -> persist(batch));
    }

    private void persist(TelemetryBatch batch) {
        try {
            upsertAgent(batch);
            persistEvents(batch);
            persistResilience(batch);
        } catch (Exception ex) {
            log.debug("Telemetry persist failed for {}: {}", batch.instanceId(), ex.getMessage());
        }
    }

    private void upsertAgent(TelemetryBatch batch) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update(
                """
                INSERT INTO agent_instance (instance_id, service_name, first_seen, last_seen, metadata)
                VALUES (?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (instance_id) DO UPDATE SET last_seen = EXCLUDED.last_seen, service_name = EXCLUDED.service_name
                """,
                batch.instanceId(),
                batch.serviceName(),
                now,
                now,
                batch.config() == null ? null : batch.config().toString());
    }

    private void persistEvents(TelemetryBatch batch) {
        JsonNode eventsWrapper = batch.events();
        if (eventsWrapper == null) return;
        JsonNode events = eventsWrapper.get("events");
        if (events == null || !events.isArray()) return;
        for (JsonNode e : events) {
            jdbc.update(
                    """
                    INSERT INTO fault_event (ts, service_name, instance_id, rule_name, outcome, method, host, url, fault_type, delay_ms, error_status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    e.path("timestampMs").asLong(),
                    batch.serviceName(),
                    batch.instanceId(),
                    e.path("ruleName").asText(null),
                    e.path("outcome").asText(null),
                    e.path("method").asText(null),
                    e.path("host").asText(null),
                    e.path("url").asText(null),
                    e.path("faultType").asText(null),
                    e.path("delayMs").asLong(0),
                    e.path("errorStatus").asInt(0));
        }
    }

    private void persistResilience(TelemetryBatch batch) {
        JsonNode metrics = batch.metrics();
        if (metrics == null) return;
        JsonNode resilience = metrics.get("resilience");
        if (resilience == null) return;

        JsonNode retries = resilience.get("retryObservations");
        if (retries != null && retries.isArray()) {
            for (JsonNode r : retries) {
                jdbc.update(
                        """
                        INSERT INTO resilience_retry_observation
                        (service_name, instance_id, rule_name, host, method, url_path, fault_epoch_ms, window_ms, observed_retries)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        batch.serviceName(),
                        batch.instanceId(),
                        r.path("ruleName").asText(null),
                        r.path("host").asText(null),
                        r.path("method").asText(null),
                        r.path("urlPath").asText(null),
                        r.path("faultEpochMs").asLong(),
                        r.path("windowMs").asLong(),
                        r.path("observedRetries").asInt());
            }
        }

        JsonNode cbs = resilience.get("circuitBreakerObservations");
        if (cbs != null && cbs.isArray()) {
            for (JsonNode c : cbs) {
                jdbc.update(
                        """
                        INSERT INTO resilience_cb_observation
                        (service_name, instance_id, host, method, threshold, threshold_reached_at_ms, window_ms, post_window_call_count)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        batch.serviceName(),
                        batch.instanceId(),
                        c.path("host").asText(null),
                        c.path("method").asText(null),
                        c.path("threshold").asInt(),
                        c.path("thresholdReachedAtMs").asLong(),
                        c.path("windowMs").asLong(),
                        c.path("postWindowCallCount").asInt());
            }
        }

        JsonNode delays = resilience.get("delayObservations");
        if (delays != null && delays.isArray()) {
            for (JsonNode d : delays) {
                jdbc.update(
                        """
                        INSERT INTO resilience_delay_observation
                        (ts, service_name, instance_id, rule_name, host, method, injected_delay_ms, observed_wait_ms, completed_successfully)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        d.path("timestampMs").asLong(),
                        batch.serviceName(),
                        batch.instanceId(),
                        d.path("ruleName").asText(null),
                        d.path("host").asText(null),
                        d.path("method").asText(null),
                        d.path("injectedDelayMs").asLong(),
                        d.path("observedWaitMs").asLong(),
                        d.path("completedSuccessfully").asBoolean());
            }
        }
    }

    @Scheduled(cron = "0 0 3 * * *")
    public void retentionCleanup() {
        int days = Math.max(1, properties.getTelemetryRetentionDays());
        long cutoffMs = System.currentTimeMillis() - days * 86_400_000L;
        jdbc.update("DELETE FROM fault_event WHERE ts < ?", cutoffMs);
        jdbc.update("DELETE FROM resilience_delay_observation WHERE ts < ?", cutoffMs);
        jdbc.update("DELETE FROM resilience_retry_observation WHERE fault_epoch_ms < ?", cutoffMs);
        jdbc.update("DELETE FROM resilience_cb_observation WHERE threshold_reached_at_ms < ?", cutoffMs);
    }
}
