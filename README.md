# Spring Boot Fault Injector Starter

A lightweight Spring Boot starter that wires interceptors/filters into popular
HTTP clients to simulate network faults (latency, errors, or both) for chaos
and resiliency testing.

## Features

- Auto-configures fault injection for every supported HTTP client on the classpath:
  - `RestTemplate` — via a `RestTemplateCustomizer` that adds a `ClientHttpRequestInterceptor`
  - `RestClient` (Spring Framework 6.1+) — via a `RestClientCustomizer`
  - `WebClient` — via a `WebClientCustomizer` that registers an `ExchangeFilterFunction` (never blocks a reactive thread)
- Configuration-driven rules with global defaults + ordered per-rule overrides
- Two trigger modes selectable per rule:
  - `PROBABILITY` — random roll per matching request
  - `EVERY_N` — deterministic fire on every Nth matching request
- Three fault types: `DELAY`, `ERROR`, or `BOTH`
- Pluggable `FaultDecisionStrategy` bean for custom logic
- Optional Spring Boot Actuator endpoint with live counters and runtime toggles

## Compatibility

- Java 17+
- Spring Boot 3.2.x

## Installation

Maven:

```xml
<dependency>
  <groupId>com.mta</groupId>
  <artifactId>spring-boot-fault-injector-starter</artifactId>
  <version>0.0.1-SNAPSHOT</version>
</dependency>
```

Gradle:

```groovy
implementation 'com.mta:spring-boot-fault-injector-starter:0.0.1-SNAPSHOT'
```

## Usage

Build any of the supported clients via its Spring-managed builder — the
starter's customizers are applied automatically:

```java
@Bean
RestTemplate restTemplate(RestTemplateBuilder builder) {
    return builder.build();
}

@Bean
RestClient restClient(RestClient.Builder builder) {
    return builder.baseUrl("https://api.example.com").build();
}

@Bean
WebClient webClient(WebClient.Builder builder) {
    return builder.build();
}
```

## Configuration

Properties prefix: `fault.injection`

```yaml
fault:
  injection:
    enabled: true

    # Fallbacks for any field a rule omits.
    defaults:
      delay-ms: 0
      error-status: 503
      error-message: "Injected fault"
      mode: PROBABILITY        # PROBABILITY | EVERY_N
      probability: 0.0
      every-n: 0

    # First matching rule wins. Empty fields fall back to `defaults`.
    rules:
      - name: slow-billing-api
        host-pattern: "api\\.billing\\.example\\.com"
        methods: [GET, POST]
        fault: DELAY            # DELAY | ERROR | BOTH
        mode: PROBABILITY
        probability: 0.10
        delay-ms: 750

      - name: flaky-search
        url-pattern: ".*/search/.*"
        fault: ERROR
        mode: EVERY_N
        every-n: 5
        error-status: 503
```

### Rule matching

- `host-pattern` / `url-pattern` — Java regex matched against `URI.getHost()`
  and the full URL respectively. Empty means match any.
- `methods` — optional set of HTTP methods. Empty means any.
- Rules are evaluated in declared order; the first match wins.

### Custom decision logic

Provide your own `FaultDecisionStrategy` bean to replace the built-in,
config-driven strategy entirely:

```java
@Bean
FaultDecisionStrategy faultDecisionStrategy() {
    return (method, uri) -> FaultDecision.delay(Duration.ofMillis(50));
}
```

## Actuator endpoint

With `spring-boot-starter-actuator` on the classpath, the endpoint is exposed
at `/actuator/faultinjector` (id: `faultinjector`).

`GET /actuator/faultinjector` returns the current enabled flag, defaults, and
per-rule configuration with `matchCount` and `triggerCount` counters.

`POST /actuator/faultinjector` accepts a JSON body describing a targeted action:

| Action             | Body                                                      | Effect                                       |
|--------------------|-----------------------------------------------------------|----------------------------------------------|
| `enable`           | `{"action":"enable"}`                                     | Flip the global enabled flag on.             |
| `disable`          | `{"action":"disable"}`                                    | Flip the global enabled flag off.            |
| `setRuleEnabled`   | `{"action":"setRuleEnabled","name":"X","enabled":true}`   | Toggle a single named rule.                  |
| `setProbability`   | `{"action":"setProbability","name":"X","probability":0.2}`| Adjust a rule's probability (in `[0,1]`).    |

Remember to expose the endpoint with
`management.endpoints.web.exposure.include=faultinjector` (or `*`).

## Bundled UI

When Spring MVC is on the classpath the starter mounts a self-contained admin
UI at `/fault-injector` (mirroring the way Swagger UI hangs off the
application's port). Open it in a browser to:

- view and edit defaults + every rule field at runtime,
- add or delete rules without restarting,
- toggle the global kill switch and per-rule enabled flag,
- watch a live time-series chart of match vs trigger counts,
- see the most recent injection decisions in a streaming table,
- reset metrics or export them as JSON / CSV.

The UI is a single static page (Tailwind + Chart.js via CDN, zero build step)
that talks to a small REST API rooted at `${fault.injection.ui.path}/api`.

### Configuration

```yaml
fault:
  injection:
    ui:
      enabled: true                  # default true; set false to opt out
      path: /fault-injector          # URL prefix for the UI and its API
      event-buffer-size: 1000        # ring buffer size for "Recent decisions"
      timeseries-bucket-seconds: 10  # width of one chart bucket
      timeseries-buckets: 60         # number of buckets retained (10 s × 60 = 10 min)
      snapshot-poll-ms: 2000         # UI hint for how often to poll
```

The UI auto-configures only when Spring MVC is on the classpath (i.e. when an
HTTP server is running). Mutations are written into the live
`FaultInjectionProperties` bean and persist for the JVM lifetime — they do
**not** survive a restart, by design (use `application.yml` for durable
configuration).

### Promoting runtime edits to durable config

Click **Download config** in the Configuration tab to download the current
`fault.injection.*` tree as `fault-injection.yml`. The file mirrors the layout
of `application.yml` exactly (kebab-case keys, null overrides omitted, methods
as a list of strings), so you can paste its contents under `fault:` in your
project's `application.yml` to make the edits survive a restart. The same data
is also reachable programmatically at
`GET /fault-injector/api/config/export?format=yaml`.

## Build

```bash
./mvnw clean verify
./mvnw test
```

## License

Add a LICENSE file if you need an explicit license.
