package com.mta.faultinjection.core;

import com.mta.faultinjection.config.FaultInjectionProperties;
import com.mta.faultinjection.config.FaultInjectionProperties.Defaults;
import com.mta.faultinjection.config.FaultInjectionProperties.Rule;
import org.springframework.http.HttpMethod;

import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleSupplier;
import java.util.regex.Pattern;

/**
 * Default, configuration-driven {@link FaultDecisionStrategy}.
 * <p>
 * Evaluates {@link FaultInjectionProperties#getRules()} in declared order and
 * returns a {@link FaultDecision} built from the first rule that matches the
 * request. Per-rule counters are exposed to callers (e.g. the actuator
 * endpoint) for observability.
 */
public class FaultDecisionStrategyImpl implements FaultDecisionStrategy {

    private final FaultInjectionProperties properties;
    private final DoubleSupplier randomSupplier;

    /** Per-rule metrics, keyed by {@link Rule#getName()}. */
    private final Map<String, RuleMetrics> metrics = new ConcurrentHashMap<>();

    /** Cache of compiled regex patterns keyed by the raw pattern string. */
    private final Map<String, Pattern> patternCache = new ConcurrentHashMap<>();

    public FaultDecisionStrategyImpl(FaultInjectionProperties properties) {
        this(properties, () -> java.util.concurrent.ThreadLocalRandom.current().nextDouble());
    }

    /**
     * Test-friendly constructor that accepts a deterministic random supplier.
     */
    public FaultDecisionStrategyImpl(FaultInjectionProperties properties, DoubleSupplier randomSupplier) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.randomSupplier = Objects.requireNonNull(randomSupplier, "randomSupplier");
    }

    @Override
    public FaultDecision decide(HttpMethod method, URI uri) {
        if (!properties.isEnabled() || uri == null) {
            return FaultDecision.pass();
        }

        for (Rule rule : properties.getRules()) {
            if (rule == null || !rule.isEnabled() || !matches(rule, method, uri)) {
                continue;
            }
            RuleMetrics m = metricsFor(rule);
            m.matchCount.incrementAndGet();

            if (!shouldFire(rule, m)) {
                continue;
            }
            m.triggerCount.incrementAndGet();
            return buildDecision(rule);
        }
        return FaultDecision.pass();
    }

    // ----- matching / firing -----

    private boolean matches(Rule rule, HttpMethod method, URI uri) {
        if (!rule.safeMethods().isEmpty() && method != null && !rule.safeMethods().contains(method)) {
            return false;
        }
        String hostPattern = rule.getHostPattern();
        if (hostPattern != null && !hostPattern.isBlank()) {
            String host = uri.getHost();
            if (host == null || !compile(hostPattern).matcher(host).matches()) {
                return false;
            }
        }
        String urlPattern = rule.getUrlPattern();
        if (urlPattern != null && !urlPattern.isBlank()) {
            String url = uri.toString();
            if (!compile(urlPattern).matcher(url).matches()) {
                return false;
            }
        }
        return true;
    }

    private boolean shouldFire(Rule rule, RuleMetrics m) {
        TriggerMode mode = rule.getMode() != null ? rule.getMode() : properties.getDefaults().getMode();
        if (mode == TriggerMode.EVERY_N) {
            int n = resolveInt(rule.getEveryN(), properties.getDefaults().getEveryN());
            if (n <= 0) {
                return false;
            }
            return m.fireCounter.incrementAndGet() % n == 0;
        }
        // PROBABILITY
        double p = rule.getProbability() != null ? rule.getProbability() : properties.getDefaults().getProbability();
        if (p <= 0.0d) {
            return false;
        }
        if (p >= 1.0d) {
            return true;
        }
        return randomSupplier.getAsDouble() < p;
    }

    private FaultDecision buildDecision(Rule rule) {
        Defaults d = properties.getDefaults();
        long delayMs = resolveLong(rule.getDelayMs(), d.getDelayMs());
        int status = resolveInt(rule.getErrorStatus(), d.getErrorStatus());
        String message = rule.getErrorMessage() != null ? rule.getErrorMessage() : d.getErrorMessage();

        switch (rule.getFault()) {
            case DELAY:
                return FaultDecision.delay(Duration.ofMillis(Math.max(0L, delayMs)));
            case ERROR:
                return FaultDecision.error(status, message);
            case BOTH:
                return FaultDecision.delayThenError(Duration.ofMillis(Math.max(0L, delayMs)), status, message);
            default:
                return FaultDecision.pass();
        }
    }

    // ----- metrics / accessors -----

    /**
     * @return an unmodifiable snapshot view of per-rule metrics keyed by rule name.
     *         Insertion order is preserved for predictable rendering by the actuator.
     */
    public Map<String, RuleMetrics> metricsSnapshot() {
        Map<String, RuleMetrics> ordered = new LinkedHashMap<>();
        for (Rule rule : properties.getRules()) {
            if (rule != null && rule.getName() != null) {
                ordered.put(rule.getName(), metricsFor(rule));
            }
        }
        return Collections.unmodifiableMap(ordered);
    }

    private RuleMetrics metricsFor(Rule rule) {
        String key = rule.getName() != null ? rule.getName() : "rule@" + System.identityHashCode(rule);
        return metrics.computeIfAbsent(key, k -> new RuleMetrics());
    }

    private Pattern compile(String regex) {
        return patternCache.computeIfAbsent(regex, Pattern::compile);
    }

    private static long resolveLong(Long override, long fallback) {
        return override != null ? override : fallback;
    }

    private static int resolveInt(Integer override, int fallback) {
        return override != null ? override : fallback;
    }

    /**
     * Observability counters for a single rule. All counters are updated under
     * live traffic, so the exposed accessors return the current value without
     * defensive copying.
     */
    public static final class RuleMetrics {
        private final AtomicLong matchCount = new AtomicLong();
        private final AtomicLong triggerCount = new AtomicLong();
        private final AtomicLong fireCounter = new AtomicLong();

        public long matchCount() {
            return matchCount.get();
        }

        public long triggerCount() {
            return triggerCount.get();
        }

        public Map<String, Long> asMap() {
            Map<String, Long> m = new HashMap<>(2);
            m.put("matchCount", matchCount.get());
            m.put("triggerCount", triggerCount.get());
            return m;
        }
    }
}
