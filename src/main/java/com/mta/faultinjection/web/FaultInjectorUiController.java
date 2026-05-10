package com.mta.faultinjection.web;

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
import org.springframework.boot.origin.Origin;
import org.springframework.boot.origin.OriginTrackedValue;
import org.springframework.boot.origin.TextResourceOrigin;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.boot.env.OriginTrackedMapPropertySource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

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

/**
 * REST endpoints powering the bundled fault-injection UI.
 * <p>
 * Mounted under {@code ${fault.injection.ui.path}/api}; the same path prefix
 * serves the static HTML/JS bundle. Mutations are written into the live
 * {@link FaultInjectionProperties} bean so changes take effect immediately for
 * subsequent {@code decide()} calls.
 * <p>
 * The endpoints intentionally accept a flat DTO ({@link RuleDto}) rather than
 * the underlying {@link Rule} so the API is decoupled from quirks of Spring's
 * property binding (notably {@code Set<HttpMethod>} which doesn't round-trip
 * through Jackson without custom configuration).
 */
@RestController
@RequestMapping("${fault.injection.ui.path:/fault-injector}/api")
public class FaultInjectorUiController {

    private final FaultInjectionProperties properties;
    private final FaultDecisionStrategy strategy;
    private final FaultInjectionTelemetry telemetry;
    private final Environment environment;

    public FaultInjectorUiController(FaultInjectionProperties properties,
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

    @GetMapping("/config")
    public Map<String, Object> config() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", properties.isEnabled());
        out.put("defaults", properties.getDefaults());
        out.put("rules", rulesView());
        Map<String, Object> ui = new LinkedHashMap<>();
        ui.put("path", properties.getUi().getPath());
        ui.put("pollMs", properties.getUi().getSnapshotPollMs());
        ui.put("eventBufferSize", properties.getUi().getEventBufferSize());
        ui.put("timeseriesBucketSeconds", properties.getUi().getTimeseriesBucketSeconds());
        ui.put("timeseriesBuckets", properties.getUi().getTimeseriesBuckets());
        out.put("ui", ui);
        return out;
    }

    // ------------------------------------------------------------------
    // Global enable / disable
    // ------------------------------------------------------------------

    @PostMapping("/enabled")
    public Map<String, Object> setEnabled(@RequestBody EnabledDto body) {
        if (body == null || body.enabled == null) {
            throw badRequest("'enabled' is required");
        }
        properties.setEnabled(body.enabled);
        return Map.of("enabled", properties.isEnabled());
    }

    // ------------------------------------------------------------------
    // Rule CRUD
    // ------------------------------------------------------------------

    @PostMapping("/rules")
    public ResponseEntity<Map<String, Object>> addRule(@RequestBody RuleDto dto) {
        if (dto == null || dto.name == null || dto.name.isBlank()) {
            throw badRequest("'name' is required");
        }
        if (findRule(dto.name) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Rule already exists: " + dto.name);
        }
        Rule rule = new Rule();
        rule.setName(dto.name);
        applyDtoToRule(dto, rule, true);
        properties.getRules().add(rule);
        return ResponseEntity.status(HttpStatus.CREATED).body(ruleView(rule));
    }

    @PutMapping("/rules/{name}")
    public Map<String, Object> updateRule(@PathVariable String name, @RequestBody RuleDto dto) {
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

    @DeleteMapping("/rules/{name}")
    public Map<String, Object> deleteRule(@PathVariable String name) {
        Rule rule = requireRule(name);
        properties.getRules().remove(rule);
        if (strategy instanceof FaultDecisionStrategyImpl impl) {
            impl.resetMetrics(name);
        }
        telemetry.resetRule(name);
        return Map.of("removed", name);
    }

    @PostMapping("/rules/{name}/enabled")
    public Map<String, Object> setRuleEnabled(@PathVariable String name, @RequestBody EnabledDto body) {
        if (body == null || body.enabled == null) {
            throw badRequest("'enabled' is required");
        }
        Rule rule = requireRule(name);
        rule.setEnabled(body.enabled);
        return Map.of("name", name, "enabled", rule.isEnabled());
    }

    // ------------------------------------------------------------------
    // Metrics / events
    // ------------------------------------------------------------------

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        Map<String, RuleMetrics> snap = strategy instanceof FaultDecisionStrategyImpl impl
                ? impl.metricsSnapshot()
                : Map.of();
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
            row.put("name", rule.getName());
            row.put("enabled", rule.isEnabled());
            row.put("matchCount", mc);
            row.put("triggerCount", tc);
            rows.add(row);
        }
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("matchCount", totalMatch);
        totals.put("triggerCount", totalTrigger);
        totals.put("activeRules", properties.getRules().stream().filter(Rule::isEnabled).count());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rules", rows);
        out.put("totals", totals);
        return out;
    }

    @GetMapping("/metrics/timeseries")
    public Map<String, Object> timeSeries() {
        List<TimeSeriesBucket> series = telemetry.timeSeries();
        List<Map<String, Object>> points = new ArrayList<>(series.size());
        Set<String> ruleNames = new HashSet<>();
        for (TimeSeriesBucket b : series) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("startEpochMs", b.startEpochMs());
            p.put("widthMs", b.widthMs());
            p.put("matches", b.matches());
            p.put("triggers", b.triggers());
            Map<String, Map<String, Long>> per = new LinkedHashMap<>();
            b.perRule().forEach((rule, counts) -> {
                Map<String, Long> rec = new LinkedHashMap<>();
                rec.put("matches", counts[0]);
                rec.put("triggers", counts[1]);
                per.put(rule, rec);
                ruleNames.add(rule);
            });
            p.put("perRule", per);
            points.add(p);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("buckets", points);
        out.put("ruleNames", new ArrayList<>(ruleNames));
        return out;
    }

    @GetMapping("/events")
    public Map<String, Object> events(@RequestParam(defaultValue = "200") int limit) {
        List<FaultInjectionEvent> recent = telemetry.recentEvents(limit);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", recent.size());
        out.put("events", recent);
        return out;
    }

    @PostMapping("/metrics/reset")
    public Map<String, Object> resetMetrics(@RequestBody(required = false) ResetDto body) {
        String name = body == null ? null : body.name;
        if (name == null || name.isBlank()) {
            if (strategy instanceof FaultDecisionStrategyImpl impl) {
                impl.resetMetrics();
            }
            telemetry.resetAll();
            return Map.of("reset", "all");
        }
        if (strategy instanceof FaultDecisionStrategyImpl impl) {
            impl.resetMetrics(name);
        }
        telemetry.resetRule(name);
        return Map.of("reset", name);
    }

    @GetMapping("/export")
    public ResponseEntity<?> export(@RequestParam(defaultValue = "json") String format) {
        String fmt = format.toLowerCase(Locale.ROOT);
        if ("csv".equals(fmt)) {
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("text/csv"))
                    .header("Content-Disposition", "attachment; filename=fault-injector-events.csv")
                    .body(eventsAsCsv());
        }
        if ("json".equals(fmt)) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("config", config());
            body.put("metrics", metrics());
            body.put("timeseries", timeSeries());
            body.put("events", telemetry.recentEvents(0));
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Content-Disposition", "attachment; filename=fault-injector-export.json")
                    .body(body);
        }
        throw badRequest("unsupported format: " + format);
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
    @GetMapping("/config/export")
    public ResponseEntity<String> exportConfig(@RequestParam(defaultValue = "yaml") String format) {
        String fmt = format.toLowerCase(Locale.ROOT);
        if (!"yaml".equals(fmt) && !"yml".equals(fmt)) {
            throw badRequest("unsupported format: " + format);
        }
        Optional<Path> sourceFile = findApplicationYamlPath();
        if (sourceFile.isPresent()) {
            try {
                String original = Files.readString(sourceFile.get());
                String merged = spliceFaultBlock(original, renderLiveFaultBlock());
                String filename = sourceFile.get().getFileName().toString();
                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType("application/yaml; charset=utf-8"))
                        .header("Content-Disposition", "attachment; filename=" + filename)
                        .body(merged);
            } catch (IOException ignored) {
                // Fall through to the simpler subtree-only download.
            }
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/yaml; charset=utf-8"))
                .header("Content-Disposition", "attachment; filename=fault-injection.yml")
                .body(renderLiveFaultBlock());
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
    @PostMapping(value = "/config/merge",
                 consumes = {MediaType.TEXT_PLAIN_VALUE, MediaType.APPLICATION_OCTET_STREAM_VALUE,
                             "application/yaml", "text/yaml", "text/x-yaml"},
                 produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> mergeConfig(@RequestBody String existingYaml) {
        if (existingYaml == null) {
            throw badRequest("request body is required");
        }
        String merged = spliceFaultBlock(existingYaml, renderLiveFaultBlock());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/yaml; charset=utf-8"))
                .header("Content-Disposition", "attachment; filename=application-merged.yml")
                .body(merged);
    }

    // ------------------------------------------------------------------
    // DTOs
    // ------------------------------------------------------------------

    public static class EnabledDto {
        public Boolean enabled;
    }

    public static class ResetDto {
        public String name;
    }

    /**
     * Flat representation of a {@link Rule} for the REST API. All fields are
     * optional on update; on create, {@link #name} is required.
     */
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

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private void applyDtoToRule(RuleDto dto, Rule rule, boolean creating) {
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
        view.put("name", rule.getName());
        view.put("enabled", rule.isEnabled());
        view.put("fault", rule.getFault());
        view.put("mode", rule.getMode());
        view.put("hostPattern", rule.getHostPattern());
        view.put("urlPattern", rule.getUrlPattern());
        view.put("methods", rule.safeMethods().stream().map(HttpMethod::name).sorted().collect(Collectors.toList()));
        view.put("probability", rule.getProbability());
        view.put("everyN", rule.getEveryN());
        view.put("delayMs", rule.getDelayMs());
        view.put("errorStatus", rule.getErrorStatus());
        view.put("errorMessage", rule.getErrorMessage());
        if (strategy instanceof FaultDecisionStrategyImpl impl && rule.getName() != null) {
            RuleMetrics m = impl.metricsSnapshot().get(rule.getName());
            view.put("matchCount", m != null ? m.matchCount() : 0L);
            view.put("triggerCount", m != null ? m.triggerCount() : 0L);
        }
        return view;
    }

    private Rule requireRule(String name) {
        Rule r = findRule(name);
        if (r == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No rule named: " + name);
        }
        return r;
    }

    @Nullable
    private Rule findRule(String name) {
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

    private static ResponseStatusException badRequest(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    // ------------------------------------------------------------------
    // CSV export
    // ------------------------------------------------------------------

    private String eventsAsCsv() {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("timestampMs,ruleName,outcome,method,host,url,faultType,delayMs,errorStatus\n");
        for (FaultInjectionEvent e : telemetry.recentEvents(0)) {
            sb.append(e.timestampMs()).append(',')
                    .append(csv(e.ruleName())).append(',')
                    .append(e.outcome().name()).append(',')
                    .append(csv(e.method())).append(',')
                    .append(csv(e.host())).append(',')
                    .append(csv(e.url())).append(',')
                    .append(csv(e.faultType())).append(',')
                    .append(e.delayMs()).append(',')
                    .append(e.errorStatus()).append('\n');
        }
        return sb.toString();
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
        return name.equals("application.yml") || name.equals("application.yaml")
                || name.startsWith("application-");
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
     * Replace the existing top-level {@code fault:} block in {@code original}
     * with {@code liveBlock}. If {@code original} has no {@code fault:} block,
     * append the new one to the end (separated by a blank line).
     * <p>
     * The "fault block" is everything from the line that starts with
     * {@code fault:} (no leading whitespace) up to — but not including — the
     * next sibling key (another line starting at column zero with non-whitespace
     * content), or end-of-input. Comments and blank lines that immediately
     * precede the next sibling key are kept with the sibling (they "belong" to
     * what follows, not to the fault block we're replacing).
     */
    static String spliceFaultBlock(String original, String liveBlock) {
        // Normalize line endings so the splice math doesn't have to track CRLF
        // separately. We re-emit with `\n`; users running on Windows can convert
        // back if they need CRLF in source control.
        String normalized = original.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);

        int faultStart = -1;
        for (int i = 0; i < lines.length; i++) {
            if (isTopLevelFaultLine(lines[i])) {
                faultStart = i;
                break;
            }
        }

        if (faultStart < 0) {
            // No existing fault block — append the live one at the end with a
            // separating blank line so the result still parses as one document.
            StringBuilder sb = new StringBuilder(normalized);
            if (!normalized.isEmpty() && !normalized.endsWith("\n")) {
                sb.append('\n');
            }
            if (!normalized.endsWith("\n\n") && !normalized.isEmpty()) {
                sb.append('\n');
            }
            sb.append(liveBlock);
            if (!liveBlock.endsWith("\n")) {
                sb.append('\n');
            }
            return sb.toString();
        }

        // Walk forward from the line after fault: until we hit the next
        // top-level key. That's the end of the block being replaced.
        int faultEnd = lines.length;
        for (int i = faultStart + 1; i < lines.length; i++) {
            if (isTopLevelKeyLine(lines[i])) {
                faultEnd = i;
                break;
            }
        }

        StringBuilder out = new StringBuilder();
        for (int i = 0; i < faultStart; i++) {
            out.append(lines[i]).append('\n');
        }
        out.append(liveBlock);
        if (!liveBlock.endsWith("\n")) {
            out.append('\n');
        }
        for (int i = faultEnd; i < lines.length; i++) {
            out.append(lines[i]);
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        // Preserve a trailing newline only if the original had one.
        if (normalized.endsWith("\n") && (out.length() == 0 || out.charAt(out.length() - 1) != '\n')) {
            out.append('\n');
        }
        return out.toString();
    }

    /** True if {@code line} is the top-level {@code fault:} key (no indent). */
    private static boolean isTopLevelFaultLine(String line) {
        if (line == null || line.isEmpty() || Character.isWhitespace(line.charAt(0))) {
            return false;
        }
        String trimmedKey;
        int colon = line.indexOf(':');
        if (colon < 0) {
            return false;
        }
        trimmedKey = line.substring(0, colon).trim();
        return "fault".equals(trimmedKey);
    }

    /**
     * True if {@code line} is a top-level YAML key — column zero, non-blank,
     * not a comment. Used to detect the boundary where the fault: block ends.
     */
    private static boolean isTopLevelKeyLine(String line) {
        if (line == null || line.isEmpty()) {
            return false;
        }
        char c = line.charAt(0);
        if (Character.isWhitespace(c) || c == '#' || c == '-') {
            return false;
        }
        return line.contains(":");
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
