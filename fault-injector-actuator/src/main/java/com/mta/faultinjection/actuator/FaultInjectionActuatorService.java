package com.mta.faultinjection.actuator;

import com.mta.faultinjection.api.FaultInjectorViewJsonKeys;
import com.mta.faultinjection.config.FaultInjectionProperties;
import com.mta.faultinjection.config.FaultInjectionProperties.Rule;
import com.mta.faultinjection.core.FaultDecisionStrategy;
import com.mta.faultinjection.core.FaultDecisionStrategyImpl;
import com.mta.faultinjection.core.FaultDecisionStrategyImpl.RuleMetrics;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpMethod;
import org.springframework.lang.Nullable;

/**
 * Actuator read/write operations for fault injection state.
 */
public class FaultInjectionActuatorService {

    private final FaultInjectionProperties properties;
    private final FaultDecisionStrategy strategy;

    public FaultInjectionActuatorService(FaultInjectionProperties properties, FaultDecisionStrategy strategy) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.strategy = Objects.requireNonNull(strategy, "strategy");
    }

    public Map<String, Object> describeSnapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(FaultInjectorViewJsonKeys.ENABLED, properties.isEnabled());
        out.put(FaultInjectorViewJsonKeys.DEFAULTS, properties.getDefaults());

        Map<String, RuleMetrics> metrics =
                strategy instanceof FaultDecisionStrategyImpl impl ? impl.metricsSnapshot() : Collections.emptyMap();

        List<Map<String, Object>> rules = new ArrayList<>();
        for (Rule rule : properties.getRules()) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put(FaultInjectorViewJsonKeys.NAME, rule.getName());
            view.put(FaultInjectorViewJsonKeys.ENABLED, rule.isEnabled());
            view.put(FaultInjectorViewJsonKeys.FAULT, rule.getFault());
            view.put(FaultInjectorViewJsonKeys.MODE, rule.getMode());
            view.put(FaultInjectorViewJsonKeys.HOST_PATTERN, rule.getHostPattern());
            view.put(FaultInjectorViewJsonKeys.URL_PATTERN, rule.getUrlPattern());
            view.put(FaultInjectorViewJsonKeys.METHODS, methodNames(rule.safeMethods()));
            view.put(FaultInjectorViewJsonKeys.PROBABILITY, rule.getProbability());
            view.put(FaultInjectorViewJsonKeys.EVERY_N, rule.getEveryN());
            view.put(FaultInjectorViewJsonKeys.DELAY_MS, rule.getDelayMs());
            view.put(FaultInjectorViewJsonKeys.ERROR_STATUS, rule.getErrorStatus());
            RuleMetrics rm = rule.getName() != null ? metrics.get(rule.getName()) : null;
            view.put(FaultInjectorViewJsonKeys.MATCH_COUNT, rm != null ? rm.matchCount() : 0L);
            view.put(FaultInjectorViewJsonKeys.TRIGGER_COUNT, rm != null ? rm.triggerCount() : 0L);
            rules.add(view);
        }
        out.put(FaultInjectorViewJsonKeys.RULES, rules);
        return out;
    }

    public Map<String, Object> applyWrite(
            String action, @Nullable String name, @Nullable Boolean enabled, @Nullable Double probability) {
        if (action == null) {
            throw new IllegalArgumentException("Missing 'action' field");
        }
        switch (action) {
            case FaultInjectorActuatorActions.ENABLE:
                properties.setEnabled(true);
                break;
            case FaultInjectorActuatorActions.DISABLE:
                properties.setEnabled(false);
                break;
            case FaultInjectorActuatorActions.SET_RULE_ENABLED:
                requireRule(name).setEnabled(Boolean.TRUE.equals(enabled));
                break;
            case FaultInjectorActuatorActions.SET_PROBABILITY:
                if (probability == null) {
                    throw new IllegalArgumentException("'probability' is required for setProbability");
                }
                if (probability < 0.0d || probability > 1.0d) {
                    throw new IllegalArgumentException("'probability' must be in [0.0, 1.0]");
                }
                requireRule(name).setProbability(probability);
                break;
            default:
                throw new IllegalArgumentException("Unknown action: " + action);
        }
        return Collections.singletonMap(FaultInjectorViewJsonKeys.STATUS, "applied");
    }

    private static List<String> methodNames(Set<HttpMethod> methods) {
        return methods.stream().map(HttpMethod::name).sorted().collect(Collectors.toList());
    }

    private Rule requireRule(String name) {
        if (name == null) {
            throw new IllegalArgumentException("Missing 'name' field");
        }
        return properties.getRules().stream()
                .filter(r -> name.equals(r.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No rule named: " + name));
    }
}
