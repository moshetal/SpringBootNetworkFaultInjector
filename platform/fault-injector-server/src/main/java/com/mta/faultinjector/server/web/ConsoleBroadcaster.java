package com.mta.faultinjector.server.web;

import jakarta.annotation.PreDestroy;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Pushes console read-model snapshots to subscribed browsers over STOMP.
 * <ul>
 *     <li>{@code /topic/overview} — the cluster overview rows.</li>
 *     <li>{@code /topic/services/{serviceName}} — the per-service render bundle.</li>
 * </ul>
 * Bursts (every agent pushes telemetry on its own interval) are coalesced into
 * at most one flush per {@link #COALESCE_MS} window so many instances don't fan
 * out into a broadcast storm.
 */
@Component
public class ConsoleBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(ConsoleBroadcaster.class);

    /** Window over which repeated dirty marks collapse into a single broadcast. */
    private static final long COALESCE_MS = 150L;

    private final SimpMessagingTemplate messaging;
    private final ConsoleSnapshotService snapshots;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "console-broadcast");
        t.setDaemon(true);
        return t;
    });

    private final Set<String> dirtyServices = new HashSet<>();
    private final AtomicBoolean overviewDirty = new AtomicBoolean(false);
    private final AtomicBoolean flushScheduled = new AtomicBoolean(false);

    public ConsoleBroadcaster(SimpMessagingTemplate messaging, ConsoleSnapshotService snapshots) {
        this.messaging = messaging;
        this.snapshots = snapshots;
    }

    /** Mark a service's snapshot stale; a coalesced broadcast follows shortly. */
    public void broadcastService(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) {
            return;
        }
        synchronized (dirtyServices) {
            dirtyServices.add(serviceName);
        }
        scheduleFlush();
    }

    /** Mark the cluster overview stale; a coalesced broadcast follows shortly. */
    public void broadcastOverview() {
        overviewDirty.set(true);
        scheduleFlush();
    }

    private void scheduleFlush() {
        if (flushScheduled.compareAndSet(false, true)) {
            scheduler.schedule(this::flush, COALESCE_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void flush() {
        flushScheduled.set(false);
        Set<String> services;
        synchronized (dirtyServices) {
            services = new HashSet<>(dirtyServices);
            dirtyServices.clear();
        }
        boolean overview = overviewDirty.getAndSet(false);
        try {
            for (String service : services) {
                messaging.convertAndSend("/topic/services/" + service, snapshots.serviceSnapshot(service));
            }
            if (overview) {
                messaging.convertAndSend("/topic/overview", snapshots.overview());
            }
        } catch (RuntimeException ex) {
            log.debug("Console broadcast failed: {}", ex.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
