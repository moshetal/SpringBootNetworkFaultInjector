package com.example.demo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Endpoints that exercise the fault-injection starter from every angle.
 * <p>
 * Each demo endpoint:
 * <ul>
 *     <li>uses one of the three supported HTTP clients,</li>
 *     <li>calls a local "upstream" path that matches (or deliberately doesn't match) a configured rule,</li>
 *     <li>returns the elapsed time + body or error so injected behavior is visible at a glance.</li>
 * </ul>
 */
@RestController
public class DemoController {

    private final RestTemplate restTemplate;
    private final RestClient restClient;
    private final WebClient webClient;
    private final String upstreamBase;

    public DemoController(RestTemplate restTemplate,
                          RestClient restClient,
                          WebClient webClient,
                          @Value("${demo.upstream-base:http://localhost:8080/upstream}") String upstreamBase) {
        this.restTemplate = restTemplate;
        this.restClient = restClient;
        this.webClient = webClient;
        this.upstreamBase = upstreamBase;
    }

    // ----- the upstream the demo clients call back into -----

    @GetMapping("/upstream/{bucket}")
    public String upstreamGet(@PathVariable String bucket) {
        return "GET upstream/" + bucket;
    }

    @GetMapping("/upstream/{bucket}/{id}")
    public String upstreamGetItem(@PathVariable String bucket, @PathVariable String id) {
        return "GET upstream/" + bucket + "/" + id;
    }

    @PostMapping("/upstream/{bucket}/write")
    public String upstreamWrite(@PathVariable String bucket) {
        return "POST upstream/" + bucket + "/write";
    }

    // ----- demo endpoints — each shows off a different rule -----

    /** No rule matches -> request passes through, fast 200. */
    @GetMapping("/demo/normal")
    public Map<String, Object> normal() {
        return get("RestTemplate", "/upstream/normal", restTemplate);
    }

    /** URL pattern matches the DELAY rule -> ~delay-ms slower, 200 from upstream. */
    @GetMapping("/demo/slow")
    public Map<String, Object> slow() {
        return get("RestClient", "/upstream/billing/invoice-42", restClient);
    }

    /** EVERY_N ERROR rule -> deterministic: every 3rd call fails with 503. */
    @GetMapping("/demo/flaky")
    public Map<String, Object> flaky() {
        return get("WebClient", "/upstream/search/things?q=demo", webClient);
    }

    /** Disabled rule on this path -> always passes through even though the path matches. */
    @GetMapping("/demo/healthy")
    public Map<String, Object> healthy() {
        return get("RestTemplate", "/upstream/healthy", restTemplate);
    }

    /** Method filter: only POST writes get faulted (BOTH = delay + 503). */
    @PostMapping("/demo/write")
    public Map<String, Object> write() {
        return post("RestClient", "/upstream/orders/write", restClient);
    }

    /** Same path as /demo/write but GET -> method filter excludes it -> passes through. */
    @GetMapping("/demo/write-but-get")
    public Map<String, Object> writeButGet() {
        return get("RestClient", "/upstream/orders/write", restClient);
    }

    /** Catch-all rule via probability — caller picks the probability via ?p=. */
    @GetMapping("/demo/probabilistic")
    public Map<String, Object> probabilistic(@RequestParam(defaultValue = "0.5") double p) {
        // The rule's probability is set live via the actuator before calling this — see scripts.
        Map<String, Object> r = get("WebClient", "/upstream/probabilistic/" + p, webClient);
        r.put("requestedProbability", p);
        return r;
    }

    // ----- helpers -----

    private Map<String, Object> get(String label, String path, RestTemplate rt) {
        return timed(label + " GET " + path, () -> rt.getForObject(upstreamBase + path.substring("/upstream".length()), String.class));
    }

    private Map<String, Object> get(String label, String path, RestClient rc) {
        return timed(label + " GET " + path, () -> rc.get().uri(upstreamBase + path.substring("/upstream".length())).retrieve().body(String.class));
    }

    private Map<String, Object> get(String label, String path, WebClient wc) {
        return timed(label + " GET " + path, () -> wc.get().uri(upstreamBase + path.substring("/upstream".length()))
                .retrieve().bodyToMono(String.class).block(Duration.ofSeconds(10)));
    }

    private Map<String, Object> post(String label, String path, RestClient rc) {
        return timed(label + " POST " + path, () -> rc.post().uri(upstreamBase + path.substring("/upstream".length()))
                .retrieve().body(String.class));
    }

    private Map<String, Object> timed(String op, ThrowingSupplier<String> call) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("op", op);
        long start = System.nanoTime();
        try {
            String body = call.get();
            out.put("elapsedMs", (System.nanoTime() - start) / 1_000_000);
            out.put("status", "ok");
            out.put("body", body);
        } catch (Exception e) {
            out.put("elapsedMs", (System.nanoTime() - start) / 1_000_000);
            out.put("status", "error");
            out.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return out;
    }

    @FunctionalInterface
    interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
