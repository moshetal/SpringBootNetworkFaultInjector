package com.mta.faultinjection.web;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST endpoints powering the bundled fault-injection UI. Delegates to
 * {@link FaultInjectorUiService}.
 */
@RestController
@RequestMapping("${fault.injection.ui.path:/fault-injector}/api")
public class FaultInjectorUiController {

    private final FaultInjectorUiService uiService;

    public FaultInjectorUiController(FaultInjectorUiService uiService) {
        this.uiService = uiService;
    }

    @ExceptionHandler(FaultInjectorUiRequestException.class)
    public ResponseEntity<Map<String, String>> handleUiRequest(FaultInjectorUiRequestException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(Map.of("error", ex.getMessage() == null ? "" : ex.getMessage()));
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        return uiService.config();
    }

    @PostMapping("/enabled")
    public Map<String, Object> setEnabled(@RequestBody FaultInjectorUiDtos.EnabledDto body) {
        return uiService.setEnabled(body);
    }

    @PutMapping("/defaults")
    public Map<String, Object> updateDefaults(@RequestBody FaultInjectorUiDtos.DefaultsDto body) {
        return uiService.updateDefaults(body);
    }

    @PostMapping("/rules")
    public ResponseEntity<Map<String, Object>> addRule(@RequestBody FaultInjectorUiDtos.RuleDto dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(uiService.addRule(dto));
    }

    @PutMapping("/rules/{name}")
    public Map<String, Object> updateRule(@PathVariable String name, @RequestBody FaultInjectorUiDtos.RuleDto dto) {
        return uiService.updateRule(name, dto);
    }

    @DeleteMapping("/rules/{name}")
    public Map<String, Object> deleteRule(@PathVariable String name) {
        return uiService.deleteRule(name);
    }

    @PostMapping("/rules/{name}/enabled")
    public Map<String, Object> setRuleEnabled(
            @PathVariable String name, @RequestBody FaultInjectorUiDtos.EnabledDto body) {
        return uiService.setRuleEnabled(name, body);
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        return uiService.metrics();
    }

    @GetMapping("/metrics/timeseries")
    public Map<String, Object> timeSeries() {
        return uiService.timeSeries();
    }

    @GetMapping("/events")
    public Map<String, Object> events(@RequestParam(defaultValue = "200") int limit) {
        return uiService.events(limit);
    }

    @PostMapping("/metrics/reset")
    public Map<String, Object> resetMetrics(@RequestBody(required = false) FaultInjectorUiDtos.ResetDto body) {
        return uiService.resetMetrics(body);
    }

    @GetMapping("/export")
    public ResponseEntity<?> export(@RequestParam(defaultValue = "json") String format) {
        String fmt = format.toLowerCase();
        if (FaultInjectorExportFormats.CSV.equals(fmt)) {
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("text/csv"))
                    .header("Content-Disposition", "attachment; filename=fault-injector-events.csv")
                    .body(uiService.eventsAsCsv());
        }
        if (FaultInjectorExportFormats.JSON.equals(fmt)) {
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Content-Disposition", "attachment; filename=fault-injector-export.json")
                    .body(uiService.buildJsonExportBody());
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported format: " + format);
    }

    @GetMapping("/config/export")
    public ResponseEntity<String> exportConfig(@RequestParam(defaultValue = "yaml") String format) {
        FaultInjectorYamlDownload dl = uiService.exportConfigYaml(format);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/yaml; charset=utf-8"))
                .header("Content-Disposition", "attachment; filename=" + dl.attachmentFilename())
                .body(dl.body());
    }

    @PostMapping(
            value = "/config/merge",
            consumes = {
                MediaType.TEXT_PLAIN_VALUE,
                MediaType.APPLICATION_OCTET_STREAM_VALUE,
                "application/yaml",
                "text/yaml",
                "text/x-yaml"
            },
            produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> mergeConfig(@RequestBody String existingYaml) {
        String merged = uiService.mergeConfigYaml(existingYaml);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/yaml; charset=utf-8"))
                .header("Content-Disposition", "attachment; filename=application-merged.yml")
                .body(merged);
    }
}
