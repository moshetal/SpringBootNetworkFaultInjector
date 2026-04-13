package com.mta.faultinjection.integration;

import com.mta.faultinjection.autoconfig.FaultInjectionAutoConfiguration;
import com.mta.faultinjection.core.FaultType;
import com.mta.faultinjection.core.TriggerMode;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end tests that stand the starter up, point each supported HTTP client
 * at a {@link MockWebServer}, and assert that configured rules actually alter
 * observable behavior (latency or short-circuit errors).
 */
class FaultInjectionIntegrationTest {

    private MockWebServer server;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FaultInjectionAutoConfiguration.class));

    @BeforeEach
    void startServer() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stopServer() throws IOException {
        server.shutdown();
    }

    @Test
    void restTemplateInjectsLatencyThenProceeds() {
        server.enqueue(new MockResponse().setBody("ok").setResponseCode(200));

        contextRunner
                .withPropertyValues(delayRule("slow", 200))
                .run(ctx -> {
                    RestTemplate rt = buildRestTemplate(ctx);
                    long start = System.nanoTime();
                    String body = rt.getForObject(server.url("/x").toString(), String.class);
                    long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                    assertThat(body).isEqualTo("ok");
                    assertThat(elapsedMs).isGreaterThanOrEqualTo(180L);
                    assertThat(server.getRequestCount()).isEqualTo(1);
                });
    }

    @Test
    void restTemplateErrorShortCircuits() {
        contextRunner
                .withPropertyValues(errorRule("boom", 504, "short-circuited"))
                .run(ctx -> {
                    RestTemplate rt = buildRestTemplate(ctx);
                    assertThatThrownBy(() -> rt.getForObject(server.url("/x").toString(), String.class))
                            .isInstanceOf(HttpServerErrorException.class)
                            .hasMessageContaining("504");
                    assertThat(server.getRequestCount()).isZero();
                });
    }

    @Test
    void restClientErrorShortCircuits() {
        contextRunner
                .withPropertyValues(errorRule("boom", 503, "nope"))
                .run(ctx -> {
                    RestClient client = buildRestClient(ctx);
                    assertThatThrownBy(() -> client.get()
                            .uri(server.url("/x").uri())
                            .retrieve()
                            .body(String.class))
                            .isInstanceOf(HttpServerErrorException.class)
                            .hasMessageContaining("503");
                    assertThat(server.getRequestCount()).isZero();
                });
    }

    @Test
    void webClientInjectsLatencyAndForwardsRequest() {
        server.enqueue(new MockResponse().setBody("web-ok").setResponseCode(200));

        contextRunner
                .withPropertyValues(delayRule("slow", 150))
                .run(ctx -> {
                    WebClient client = buildWebClient(ctx);
                    long start = System.nanoTime();
                    String body = client.get()
                            .uri(server.url("/x").uri())
                            .retrieve()
                            .bodyToMono(String.class)
                            .block(Duration.ofSeconds(5));
                    long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                    assertThat(body).isEqualTo("web-ok");
                    assertThat(elapsedMs).isGreaterThanOrEqualTo(130L);
                    assertThat(server.getRequestCount()).isEqualTo(1);
                });
    }

    @Test
    void webClientErrorShortCircuits() {
        contextRunner
                .withPropertyValues(errorRule("boom", 502, "bad"))
                .run(ctx -> {
                    WebClient client = buildWebClient(ctx);
                    assertThatThrownBy(() -> client.get()
                            .uri(server.url("/x").uri())
                            .retrieve()
                            .bodyToMono(String.class)
                            .block(Duration.ofSeconds(5)))
                            .isInstanceOf(WebClientResponseException.class)
                            .hasMessageContaining("502");
                    assertThat(server.getRequestCount()).isZero();
                });
    }

    @Test
    void disabledConfigPassesThrough() {
        server.enqueue(new MockResponse().setBody("raw").setResponseCode(200));

        // Rule configured but global flag off — request must hit the server.
        contextRunner
                .withPropertyValues(
                        "fault.injection.enabled=false",
                        "fault.injection.rules[0].name=boom",
                        "fault.injection.rules[0].fault=ERROR",
                        "fault.injection.rules[0].mode=PROBABILITY",
                        "fault.injection.rules[0].probability=1.0",
                        "fault.injection.rules[0].error-status=500"
                )
                .run(ctx -> {
                    RestTemplate rt = buildRestTemplate(ctx);
                    assertThat(rt.getForObject(server.url("/x").toString(), String.class)).isEqualTo("raw");
                    assertThat(server.getRequestCount()).isEqualTo(1);
                });
    }

    // ----- helpers -----

    private static String[] delayRule(String name, long delayMs) {
        return new String[]{
                "fault.injection.enabled=true",
                "fault.injection.rules[0].name=" + name,
                "fault.injection.rules[0].fault=" + FaultType.DELAY.name(),
                "fault.injection.rules[0].mode=" + TriggerMode.PROBABILITY.name(),
                "fault.injection.rules[0].probability=1.0",
                "fault.injection.rules[0].delay-ms=" + delayMs
        };
    }

    private static String[] errorRule(String name, int status, String message) {
        return new String[]{
                "fault.injection.enabled=true",
                "fault.injection.rules[0].name=" + name,
                "fault.injection.rules[0].fault=" + FaultType.ERROR.name(),
                "fault.injection.rules[0].mode=" + TriggerMode.PROBABILITY.name(),
                "fault.injection.rules[0].probability=1.0",
                "fault.injection.rules[0].error-status=" + status,
                "fault.injection.rules[0].error-message=" + message
        };
    }

    private static RestTemplate buildRestTemplate(org.springframework.context.ApplicationContext ctx) {
        // Request-scope equivalent: build via RestTemplateBuilder so the
        // auto-configured RestTemplateCustomizer is applied.
        RestTemplateBuilder builder = new RestTemplateBuilder(
                ctx.getBeanProvider(org.springframework.boot.web.client.RestTemplateCustomizer.class)
                        .orderedStream().toArray(org.springframework.boot.web.client.RestTemplateCustomizer[]::new)
        );
        return builder.build();
    }

    private static RestClient buildRestClient(org.springframework.context.ApplicationContext ctx) {
        RestClient.Builder builder = RestClient.builder();
        ctx.getBeanProvider(org.springframework.boot.web.client.RestClientCustomizer.class)
                .orderedStream()
                .forEach(c -> c.customize(builder));
        return builder.build();
    }

    private static WebClient buildWebClient(org.springframework.context.ApplicationContext ctx) {
        WebClient.Builder builder = WebClient.builder();
        ctx.getBeanProvider(org.springframework.boot.web.reactive.function.client.WebClientCustomizer.class)
                .orderedStream()
                .forEach(c -> c.customize(builder));
        return builder.build();
    }

}
