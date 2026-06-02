package com.mta.faultinjection.web;

import java.util.Map;

/**
 * Control-plane operations shared by the local UI REST layer and the optional
 * cluster agent. Implementations mutate live {@code FaultInjectionProperties}
 * and read telemetry snapshots.
 */
public interface FaultInjectorControlFacade {

    Map<String, Object> config();

    Map<String, Object> setEnabled(FaultInjectorUiDtos.EnabledDto body);

    Map<String, Object> updateDefaults(FaultInjectorUiDtos.DefaultsDto body);

    Map<String, Object> addRule(FaultInjectorUiDtos.RuleDto dto);

    Map<String, Object> updateRule(String name, FaultInjectorUiDtos.RuleDto dto);

    Map<String, Object> deleteRule(String name);

    Map<String, Object> setRuleEnabled(String name, FaultInjectorUiDtos.EnabledDto body);

    Map<String, Object> metrics();

    Map<String, Object> timeSeries();

    Map<String, Object> events(int limit);

    Map<String, Object> resetMetrics(FaultInjectorUiDtos.ResetDto body);

    String eventsAsCsv();

    Map<String, Object> buildJsonExportBody();

    FaultInjectorYamlDownload exportConfigYaml(String format);

    String mergeConfigYaml(String existingYaml);
}
