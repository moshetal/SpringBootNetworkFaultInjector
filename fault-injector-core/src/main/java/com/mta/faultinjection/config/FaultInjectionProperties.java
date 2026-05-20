package com.mta.faultinjection.config;

import com.mta.faultinjection.core.FaultType;
import com.mta.faultinjection.core.TriggerMode;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.HttpMethod;

/**
 * Type-safe configuration mapped from the "fault.injection" namespace.
 * <p>
 * Supports a global kill-switch, shared defaults, and an ordered list of
 * per-host/per-URL rules. The first rule that matches an outbound request
 * wins; its fields fall back to {@link Defaults} when unset.
 */
@Data
@ConfigurationProperties(prefix = "fault.injection")
public class FaultInjectionProperties {

    /** Global kill-switch. When {@code false}, all rules are bypassed. */
    private volatile boolean enabled = false;

    /** Shared fallbacks for rule fields. */
    private Defaults defaults = new Defaults();

    /**
     * Ordered rules; first match wins. Backed by {@link CopyOnWriteArrayList} so
     * the bundled UI can add/remove rules at runtime without coordinating with
     * concurrent {@code decide()} traversal.
     */
    private List<Rule> rules = new CopyOnWriteArrayList<>();

    /** Settings for the bundled web UI (gated by {@code fault.injection.ui.enabled}). */
    private Ui ui = new Ui();

    /**
     * When {@code true}, outbound requests whose URL matches built-in or configured
     * patterns (e.g. actuator fault endpoint, UI prefix) never receive injected faults.
     */
    private volatile boolean outboundExcludeEnabled = true;

    /**
     * When {@code true} and {@link #outboundExcludeEnabled} is {@code true}, prepend
     * built-in exclude patterns for the faultinjector actuator path and UI base path.
     */
    private volatile boolean outboundExcludeIncludeBuiltins = true;

    /**
     * Extra URL regex patterns (matched with {@link java.util.regex.Matcher#find()}
     * against the full URI string) for which fault injection is skipped. Invalid
     * patterns are ignored at runtime.
     */
    private List<String> outboundExcludeUrlPatterns = new CopyOnWriteArrayList<>();

    /**
     * Default values applied when a rule omits a field.
     */
    @Data
    public static class Defaults {
        private long delayMs = 0L;
        private int errorStatus = 503;
        private String errorMessage = "Injected fault";
        private TriggerMode mode = TriggerMode.PROBABILITY;
        /** Probability in [0.0, 1.0]; 0 means never fire by probability. */
        private double probability = 0.0d;
        /** Fire every Nth matching request; 0 means disabled. */
        private int everyN = 0;
    }

    /**
     * Single fault-injection rule. Null/absent override fields fall through to
     * {@link Defaults}.
     */
    @Data
    public static class Rule {
        /** Human-readable identifier, used by the actuator for targeted updates. */
        private String name;

        /** Per-rule enable flag so the actuator can toggle individual rules at runtime. */
        private volatile boolean enabled = true;

        /** Regex matched against {@code URI.getHost()}; null or blank means match any. */
        private String hostPattern;

        /** Regex matched against the full URL; null or blank means match any. */
        private String urlPattern;

        /** HTTP methods to match; empty means any. */
        private Set<HttpMethod> methods = new HashSet<>();

        /** Kind of fault to inject when this rule fires. */
        private FaultType fault = FaultType.DELAY;

        /** Overrides {@link Defaults#getMode()} when set. */
        private TriggerMode mode;

        /** Overrides {@link Defaults#getProbability()} when set. */
        private volatile Double probability;

        /** Overrides {@link Defaults#getEveryN()} when set. */
        private Integer everyN;

        /** Overrides {@link Defaults#getDelayMs()} when set. */
        private Long delayMs;

        /** Overrides {@link Defaults#getErrorStatus()} when set. */
        private Integer errorStatus;

        /** Overrides {@link Defaults#getErrorMessage()} when set. */
        private String errorMessage;

        /** Convenience: null-safe view of matched methods. */
        public Set<HttpMethod> safeMethods() {
            return methods == null ? Collections.emptySet() : methods;
        }
    }

    /**
     * Settings for the bundled, "Swagger-style" UI served by this starter.
     * <p>
     * The UI is only registered when Spring MVC is on the classpath; switching
     * {@link #enabled} to {@code false} opts out entirely.
     */
    @Data
    public static class Ui {
        /** Master switch for the bundled UI. */
        private boolean enabled = true;

        /** Path prefix the UI is mounted under (no trailing slash). */
        private String path = "/fault-injector";

        /** Maximum number of recent decisions retained for the UI's events log. */
        private int eventBufferSize = 1000;

        /** Width of one time-series bucket in seconds. */
        private int timeseriesBucketSeconds = 10;

        /** Number of time-series buckets retained (rolling window). */
        private int timeseriesBuckets = 60;

        /** UI hint: how often the page should re-poll backend snapshots. */
        private int snapshotPollMs = 2000;

        /**
         * When {@code true}, only loopback clients may access the UI and its REST API.
         * Not a substitute for authentication; useful for local-only hardening.
         */
        private boolean requireLocalhost = false;
    }
}
