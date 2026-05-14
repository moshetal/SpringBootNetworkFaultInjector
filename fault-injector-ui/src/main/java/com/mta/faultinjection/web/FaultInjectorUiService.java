package com.mta.faultinjection.web;

import com.mta.faultinjection.api.FaultInjectorViewJsonKeys;
import com.mta.faultinjection.config.FaultInjectionProperties;
import com.mta.faultinjection.config.FaultInjectionProperties.Rule;
import com.mta.faultinjection.core.FaultDecisionStrategy;
import com.mta.faultinjection.core.FaultDecisionStrategyImpl;
import com.mta.faultinjection.core.FaultDecisionStrategyImpl.RuleMetrics;
import com.mta.faultinjection.core.FaultType;
import com.mta.faultinjection.core.TriggerMode;
import com.mta.faultinjection.telemetry.FaultInjectionEvent;
import com.mta.faultinjection.telemetry.FaultInjectionTelemetry;
import com.mta.faultinjection.telemetry.FaultInjectionTelemetry.TimeSeriesBucket;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import org.springframework.boot.env.OriginTrackedMapPropertySource;
import org.springframework.boot.origin.Origin;
import org.springframework.boot.origin.OriginTrackedValue;
import org.springframework.boot.origin.TextResourceOrigin;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Business logic backing the bundled fault-injection UI REST API.
 * <p>
 * Mounted under {@code ${fault.injection.ui.path}/api}; the same path prefix
 * serves the static HTML/JS bundle. Mutations are written into the live
 * {@link FaultInjectionProperties} bean so changes take effect immediately for
 * subsequent {@code decide()} calls.
 * <p>
 * The endpoints intentionally accept a flat DTO ({@link FaultInjectorUiDtos.RuleDto}) rather than
 * the underlying {@link Rule} so the API is decoupled from quirks of Spring's
 * property binding (notably {@code Set<HttpMethod>} which doesn't round-trip
 * through Jackson without custom configuration).
 */
public class FaultInjectorUiService {

    private final FaultInjectionProperties properties;
    private final FaultDecisionStrategy strategy;
    private final FaultInjectionTelemetry telemetry;
    private final Environment environment;

    public FaultInjectorUiService(
            FaultInjectionProperties properties,
            FaultDecisionStrategy strategy,
            FaultInjectionTelemetry telemetry,
            Environment environment) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.strategy = Objects.requireNonNull(strategy, "strategy");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.environment = environment;
    }

    // ------------------------------------------------------------------
    // Snapshot
    // ------------------------------------------------------------------

    public Map<String, Object> config() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(FaultInjectorViewJsonKeys.ENABLED, properties.isEnabled());
        out.put(FaultInjectorViewJsonKeys.DEFAULTS, properties.getDefaults());
        out.put(FaultInjectorViewJsonKeys.RULES, rulesView());
        Map<String, Object> ui = new LinkedHashMap<>();
        ui.put(FaultInjectorViewJsonKeys.PATH, properties.getUi().getPath());
        ui.put(FaultInjectorViewJsonKeys.POLL_MS, properties.getUi().getSnapshotPollMs());
        ui.put(FaultInjectorViewJsonKeys.EVENT_BUFFER_SIZE, properties.getUi().getEventBufferSize());
        ui.put(
                FaultInjectorViewJsonKeys.TIMESERIES_BUCKET_SECONDS,
                properties.getUi().getTimeseriesBucketSeconds());
        ui.put(FaultInjectorViewJsonKeys.TIMESERIES_BUCKETS, properties.getUi().getTimeseriesBuckets());
        out.put(FaultInjectorViewJsonKeys.UI, ui);
        return out;
    }

    // ------------------------------------------------------------------
    // Global enable / disable
    // ------------------------------------------------------------------

    public Map<String, Object> setEnabled(FaultInjectorUiDtos.EnabledDto body) {
        if (body == null || body.enabled == null) {
            throw badRequest("'enabled' is required");
        }
        properties.setEnabled(body.enabled);
        return Map.of(FaultInjectorViewJsonKeys.ENABLED, properties.isEnabled());
    }

    // ------------------------------------------------------------------
    // Rule CRUD
    // ------------------------------------------------------------------

    public Map<String, Object> addRule(FaultInjectorUiDtos.RuleDto dto) {
        if (dto == null || dto.name == null || dto.name.isBlank()) {
            throw badRequest("'name' is required");
        }
        if (findRule(dto.name) != null) {
            throw new FaultInjectorUiRequestException(HttpStatus.CONFLICT, "Rule already exists: " + dto.name);
        }
        Rule rule = new Rule();
        rule.setName(dto.name);
        applyDtoToRule(dto, rule, true);
        properties.getRules().add(rule);
        return ruleView(rule);
    }

    public Map<String, Object> updateRule(String name, FaultInjectorUiDtos.RuleDto dto) {
        Rule rule = requireRule(name);
        if (dto == null) {
            throw badRequest("rule body is required");
        }
        // Disallow renames here; the path variable is the canonical identifier.
        if (dto.name != null && !dto.name.equals(name)) {
            throw badRequest("renaming via PUT is not supported; delete + create instead");
        }
        applyDtoToRule(dto, rule, false);
        return ruleView(rule);
    }

    public Map<String, Object> deleteRule(String name) {
        Rule rule = requireRule(name);
        properties.getRules().remove(rule);
        if (strategy instanceof FaultDecisionStrategyImpl impl) {
            impl.resetMetrics(name);
        }
        telemetry.resetRule(name);
        return Map.of(FaultInjectorViewJsonKeys.REMOVED, name);
    }

    public Map<String, Object> setRuleEnabled(String name, FaultInjectorUiDtos.EnabledDto body) {
        if (body == null || body.enabled == null) {
            throw badRequest("'enabled' is required");
        }
        Rule rule = requireRule(name);
        rule.setEnabled(body.enabled);
        return Map.of(FaultInjectorViewJsonKeys.NAME, name, FaultInjectorViewJsonKeys.ENABLED, rule.isEnabled());
    }

    // ------------------------------------------------------------------
    // Metrics / events
    // ------------------------------------------------------------------

    public Map<String, Object> metrics() {
        Map<String, RuleMetrics> snap =
                strategy instanceof FaultDecisionStrategyImpl impl ? impl.metricsSnapshot() : Map.of();
        long totalMatch = 0L;
        long totalTrigger = 0L;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Rule rule : properties.getRules()) {
            RuleMetrics m = rule.getName() != null ? snap.get(rule.getName()) : null;
            long mc = m != null ? m.matchCount() : 0L;
            long tc = m != null ? m.triggerCount() : 0L;
            totalMatch += mc;
            totalTrigger += tc;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(FaultInjectorViewJsonKeys.NAME, rule.getName());
            row.put(FaultInjectorViewJsonKeys.ENABLED, rule.isEnabled());
            row.put(FaultInjectorViewJsonKeys.MATCH_COUNT, mc);
            row.put(FaultInjectorViewJsonKeys.TRIGGER_COUNT, tc);
            rows.add(row);
        }
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put(FaultInjectorViewJsonKeys.MATCH_COUNT, totalMatch);
        totals.put(FaultInjectorViewJsonKeys.TRIGGER_COUNT, totalTrigger);
        totals.put(
                FaultInjectorViewJsonKeys.ACTIVE_RULES,
                properties.getRules().stream().filter(Rule::isEnabled).count());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(FaultInjectorViewJsonKeys.RULES, rows);
        out.put(FaultInjectorViewJsonKeys.TOTALS, totals);
        return out;
    }

    public Map<String, Object> timeSeries() {
        List<TimeSeriesBucket> series = telemetry.timeSeries();
        List<Map<String, Object>> points = new ArrayList<>(series.size());
        Set<String> ruleNames = new HashSet<>();
        for (TimeSeriesBucket b : series) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put(FaultInjectorViewJsonKeys.START_EPOCH_MS, b.startEpochMs());
            p.put(FaultInjectorViewJsonKeys.WIDTH_MS, b.widthMs());
            p.put(FaultInjectorViewJsonKeys.MATCHES, b.matches());
            p.put(FaultInjectorViewJsonKeys.TRIGGERS, b.triggers());
            Map<String, Map<String, Long>> per = new LinkedHashMap<>();
            b.perRule().forEach((rule, counts) -> {
                Map<String, Long> rec = new LinkedHashMap<>();
                rec.put(FaultInjectorViewJsonKeys.MATCHES, counts[0]);
                rec.put(FaultInjectorViewJsonKeys.TRIGGERS, counts[1]);
                per.put(rule, rec);
                ruleNames.add(rule);
            });
            p.put(FaultInjectorViewJsonKeys.PER_RULE, per);
            points.add(p);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(FaultInjectorViewJsonKeys.BUCKETS, points);
        out.put(FaultInjectorViewJsonKeys.RULE_NAMES, new ArrayList<>(ruleNames));
        return out;
    }

    public Map<String, Object> events(int limit) {
        List<FaultInjectionEvent> recent = telemetry.recentEvents(limit);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(FaultInjectorViewJsonKeys.COUNT, recent.size());
        out.put(FaultInjectorViewJsonKeys.EVENTS, recent);
        return out;
    }

    public Map<String, Object> resetMetrics(FaultInjectorUiDtos.ResetDto body) {
        String name = body == null ? null : body.name;
        if (name == null || name.isBlank()) {
            if (strategy instanceof FaultDecisionStrategyImpl impl) {
                impl.resetMetrics();
            }
            telemetry.resetAll();
            return Map.of(FaultInjectorViewJsonKeys.RESET, "all");
        }
        if (strategy instanceof FaultDecisionStrategyImpl impl) {
            impl.resetMetrics(name);
        }
        telemetry.resetRule(name);
        return Map.of(FaultInjectorViewJsonKeys.RESET, name);
    }

    public String eventsAsCsv() {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("timestampMs,ruleName,outcome,method,host,url,faultType,delayMs,errorStatus\n");
        for (FaultInjectionEvent e : telemetry.recentEvents(0)) {
            sb.append(e.timestampMs())
                    .append(',')
                    .append(csv(e.ruleName()))
                    .append(',')
                    .append(e.outcome().name())
                    .append(',')
                    .append(csv(e.method()))
                    .append(',')
                    .append(csv(e.host()))
                    .append(',')
                    .append(csv(e.url()))
                    .append(',')
                    .append(csv(e.faultType()))
                    .append(',')
                    .append(e.delayMs())
                    .append(',')
                    .append(e.errorStatus())
                    .append('\n');
        }
        return sb.toString();
    }

    public Map<String, Object> buildJsonExportBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(FaultInjectorViewJsonKeys.CONFIG, config());
        body.put(FaultInjectorViewJsonKeys.METRICS, metrics());
        body.put(FaultInjectorViewJsonKeys.TIMESERIES, timeSeries());
        body.put(FaultInjectorViewJsonKeys.EVENTS, telemetry.recentEvents(0));
        return body;
    }

    /**
     * Download a YAML document the user can drop into their project to make
     * runtime mutations durable.
     * <p>
     * If the running application's {@code application.yml} (or {@code .yaml})
     * was loaded from the file system, the response is that file's full
     * content with its {@code fault:} block replaced by the live state —
     * surrounding sections (server, management, logging, comments) preserved
     * byte-for-byte. Otherwise (classpath-only, env-only, etc.) the response
     * falls back to just the {@code fault.injection.*} subtree.
     */
    public FaultInjectorYamlDownload exportConfigYaml(String format) {
        String fmt = format.toLowerCase(Locale.ROOT);
        if (!FaultInjectorExportFormats.YAML.equals(fmt) && !FaultInjectorExportFormats.YML.equals(fmt)) {
            throw badRequest("unsupported format: " + format);
        }
        Optional<Path> sourceFile = findApplicationYamlPath();
        if (sourceFile.isPresent()) {
            try {
                String original = Files.readString(sourceFile.get());
                String merged = FaultInjectorYamlFaultBlockSplicer.spliceFaultBlock(original, renderLiveFaultBlock());
                String filename = sourceFile.get().getFileName().toString();
                return new FaultInjectorYamlDownload(merged, filename);
            } catch (IOException ignored) {
                // Fall through to the simpler subtree-only download.
            }
        }
        return new FaultInjectorYamlDownload(renderLiveFaultBlock(), "fault-injection.yml");
    }

    /**
     * Take the caller's existing {@code application.yml}, replace its top-level
     * {@code fault:} block with the live fault-injection state, and return the
     * merged result. Everything outside {@code fault:} — server config,
     * management, logging, comments, blank lines — is preserved byte-for-byte
     * via textual splicing rather than parse + re-dump, so users don't lose
     * the surrounding structure of their config file.
     * <p>
     * Comments <em>inside</em> the {@code fault:} block are not preserved
     * (that subtree is regenerated from the live in-memory state).
     */
    public String mergeConfigYaml(String existingYaml) {
        if (existingYaml == null) {
            throw badRequest("request body is required");
        }
        return FaultInjectorYamlFaultBlockSplicer.spliceFaultBlock(existingYaml, renderLiveFaultBlock());
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private void applyDtoToRule(FaultInjectorUiDtos.RuleDto dto, Rule rule, boolean creating) {
        if (dto.enabled != null) {
            rule.setEnabled(dto.enabled);
        } else if (creating) {
            rule.setEnabled(true);
        }
        if (dto.hostPattern != null) {
            validateRegex("hostPattern", dto.hostPattern);
            rule.setHostPattern(blankToNull(dto.hostPattern));
        }
        if (dto.urlPattern != null) {
            validateRegex("urlPattern", dto.urlPattern);
            rule.setUrlPattern(blankToNull(dto.urlPattern));
        }
        if (dto.methods != null) {
            rule.setMethods(parseMethods(dto.methods));
        }
        if (dto.fault != null) {
            rule.setFault(parseEnum(FaultType.class, "fault", dto.fault));
        } else if (creating && rule.getFault() == null) {
            rule.setFault(FaultType.DELAY);
        }
        if (dto.mode != null) {
            rule.setMode(parseEnum(TriggerMode.class, "mode", dto.mode));
        }
        if (dto.probability != null) {
            if (dto.probability < 0.0d || dto.probability > 1.0d) {
                throw badRequest("'probability' must be in [0.0, 1.0]");
            }
            rule.setProbability(dto.probability);
        }
        if (dto.everyN != null) {
            if (dto.everyN < 0) {
                throw badRequest("'everyN' must be >= 0");
            }
            rule.setEveryN(dto.everyN);
        }
        if (dto.delayMs != null) {
            if (dto.delayMs < 0) {
                throw badRequest("'delayMs' must be >= 0");
            }
            rule.setDelayMs(dto.delayMs);
        }
        if (dto.errorStatus != null) {
            rule.setErrorStatus(dto.errorStatus);
        }
        if (dto.errorMessage != null) {
            rule.setErrorMessage(dto.errorMessage);
        }
    }

    private List<Map<String, Object>> rulesView() {
        return properties.getRules().stream().map(this::ruleView).collect(Collectors.toList());
    }

    private Map<String, Object> ruleView(Rule rule) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put(FaultInjectorViewJsonKeys.NAME, rule.getName());
        view.put(FaultInjectorViewJsonKeys.ENABLED, rule.isEnabled());
        view.put(FaultInjectorViewJsonKeys.FAULT, rule.getFault());
        view.put(FaultInjectorViewJsonKeys.MODE, rule.getMode());
        view.put(FaultInjectorViewJsonKeys.HOST_PATTERN, rule.getHostPattern());
        view.put(FaultInjectorViewJsonKeys.URL_PATTERN, rule.getUrlPattern());
        view.put(
                FaultInjectorViewJsonKeys.METHODS,
                rule.safeMethods().stream().map(HttpMethod::name).sorted().collect(Collectors.toList()));
        view.put(FaultInjectorViewJsonKeys.PROBABILITY, rule.getProbability());
        view.put(FaultInjectorViewJsonKeys.EVERY_N, rule.getEveryN());
        view.put(FaultInjectorViewJsonKeys.DELAY_MS, rule.getDelayMs());
        view.put(FaultInjectorViewJsonKeys.ERROR_STATUS, rule.getErrorStatus());
        view.put(FaultInjectorViewJsonKeys.ERROR_MESSAGE, rule.getErrorMessage());
        if (strategy instanceof FaultDecisionStrategyImpl impl && rule.getName() != null) {
            RuleMetrics m = impl.metricsSnapshot().get(rule.getName());
            view.put(FaultInjectorViewJsonKeys.MATCH_COUNT, m != null ? m.matchCount() : 0L);
            view.put(FaultInjectorViewJsonKeys.TRIGGER_COUNT, m != null ? m.triggerCount() : 0L);
        }
        return view;
    }

    private Rule requireRule(String name) {
        Rule r = findRule(name);
        if (r == null) {
            throw new FaultInjectorUiRequestException(HttpStatus.NOT_FOUND, "No rule named: " + name);
        }
        return r;
    }

    @Nullable private Rule findRule(String name) {
        if (name == null) {
            return null;
        }
        for (Rule r : properties.getRules()) {
            if (name.equals(r.getName())) {
                return r;
            }
        }
        return null;
    }

    private static Set<HttpMethod> parseMethods(List<String> methods) {
        Set<HttpMethod> out = new HashSet<>();
        for (String m : methods) {
            if (m == null || m.isBlank()) {
                continue;
            }
            try {
                out.add(HttpMethod.valueOf(m.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw badRequest("invalid HTTP method: " + m);
            }
        }
        return out;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String field, String value) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw badRequest("invalid value for '" + field + "': " + value);
        }
    }

    private static void validateRegex(String field, String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return;
        }
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw badRequest("invalid regex for '" + field + "': " + e.getMessage());
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static FaultInjectorUiRequestException badRequest(String msg) {
        return new FaultInjectorUiRequestException(HttpStatus.BAD_REQUEST, msg);
    }

    private static String csv(String s) {
        if (s == null) {
            return "";
        }
        boolean needsQuoting = s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0;
        String escaped = s.replace("\"", "\"\"");
        return needsQuoting ? "\"" + escaped + "\"" : escaped;
    }

    // ------------------------------------------------------------------
    // YAML config export
    // ------------------------------------------------------------------

    /**
     * Walk Spring's property sources looking for an {@code application.yml}
     * (or {@code application-{profile}.yml}) that was loaded from the local
     * file system, and return its absolute {@link Path}. Returns empty when
     * config came only from classpath jars, environment variables, or other
     * non-file sources — in which case the caller falls back to the simple
     * subtree-only download.
     * <p>
     * Spring Boot's YAML loader produces {@link OriginTrackedMapPropertySource}
     * instances whose entries are {@link OriginTrackedValue}s carrying a
     * {@link TextResourceOrigin}. We follow the chain back to the resource
     * and check whether it's a file we can read.
     */
    Optional<Path> findApplicationYamlPath() {
        if (!(environment instanceof ConfigurableEnvironment ce)) {
            return Optional.empty();
        }
        for (PropertySource<?> ps : ce.getPropertySources()) {
            if (!(ps instanceof OriginTrackedMapPropertySource otps)) {
                continue;
            }
            for (Object value : otps.getSource().values()) {
                if (!(value instanceof OriginTrackedValue otv)) {
                    continue;
                }
                Path file = resolveResourceFile(otv.getOrigin());
                if (file != null && isApplicationYaml(file)) {
                    return Optional.of(file);
                }
                // First entry of a source is enough to identify the file —
                // every entry in the same source has the same origin resource.
                break;
            }
        }
        return Optional.empty();
    }

    private static Path resolveResourceFile(Origin origin) {
        // TextResourceOrigin can be wrapped (e.g. by profile-specific origins),
        // so unwrap by following the parent chain too.
        Origin current = origin;
        while (current != null) {
            if (current instanceof TextResourceOrigin tro) {
                Resource resource = tro.getResource();
                if (resource != null) {
                    try {
                        File f = resource.getFile();
                        return f != null ? f.toPath().toAbsolutePath() : null;
                    } catch (IOException | UnsupportedOperationException e) {
                        return null; // resource isn't a file (classpath jar, etc.)
                    }
                }
                return null;
            }
            current = current.getParent();
        }
        return null;
    }

    private static boolean isApplicationYaml(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".yml") && !name.endsWith(".yaml")) {
            return false;
        }
        return name.equals("application.yml") || name.equals("application.yaml") || name.startsWith("application-");
    }

    /** Render the live {@code fault:} subtree as a self-contained YAML document. */
    private String renderLiveFaultBlock() {
        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setIndent(2);
        opts.setPrettyFlow(true);
        return new Yaml(opts).dump(buildConfigYamlTree());
    }

    /**
     * Build a {@code Map} tree shaped exactly like the {@code application.yml}
     * a user would write by hand — root key {@code fault.injection.*}, kebab-case
     * field names, null overrides on rules omitted so they cascade to defaults.
     */
    private Map<String, Object> buildConfigYamlTree() {
        Map<String, Object> injection = new LinkedHashMap<>();
        injection.put("enabled", properties.isEnabled());
        injection.put("defaults", defaultsAsMap(properties.getDefaults()));

        List<Map<String, Object>> rules = new ArrayList<>(properties.getRules().size());
        for (Rule rule : properties.getRules()) {
            rules.add(ruleAsMap(rule));
        }
        injection.put("rules", rules);

        Map<String, Object> fault = new LinkedHashMap<>();
        fault.put("injection", injection);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("fault", fault);
        return root;
    }

    private static Map<String, Object> defaultsAsMap(FaultInjectionProperties.Defaults d) {
        // All six fields always emitted — they're primitive and small, and a
        // user replacing their yaml expects to see the full defaults block.
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("delay-ms", d.getDelayMs());
        out.put("error-status", d.getErrorStatus());
        out.put("error-message", d.getErrorMessage());
        out.put("mode", d.getMode() != null ? d.getMode().name() : null);
        out.put("probability", d.getProbability());
        out.put("every-n", d.getEveryN());
        return out;
    }

    private static Map<String, Object> ruleAsMap(Rule rule) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (rule.getName() != null) {
            out.put("name", rule.getName());
        }
        // The starter's Rule defaults `enabled` to true. Only emit the key when
        // it diverges, mirroring the demo's application.yml style.
        if (!rule.isEnabled()) {
            out.put("enabled", false);
        }
        if (rule.getHostPattern() != null && !rule.getHostPattern().isBlank()) {
            out.put("host-pattern", rule.getHostPattern());
        }
        if (rule.getUrlPattern() != null && !rule.getUrlPattern().isBlank()) {
            out.put("url-pattern", rule.getUrlPattern());
        }
        Set<HttpMethod> methods = rule.safeMethods();
        if (!methods.isEmpty()) {
            out.put("methods", methods.stream().map(HttpMethod::name).sorted().collect(Collectors.toList()));
        }
        if (rule.getFault() != null) {
            out.put("fault", rule.getFault().name());
        }
        if (rule.getMode() != null) {
            out.put("mode", rule.getMode().name());
        }
        if (rule.getProbability() != null) {
            out.put("probability", rule.getProbability());
        }
        if (rule.getEveryN() != null) {
            out.put("every-n", rule.getEveryN());
        }
        if (rule.getDelayMs() != null) {
            out.put("delay-ms", rule.getDelayMs());
        }
        if (rule.getErrorStatus() != null) {
            out.put("error-status", rule.getErrorStatus());
        }
        if (rule.getErrorMessage() != null) {
            out.put("error-message", rule.getErrorMessage());
        }
        return out;
    }
}
