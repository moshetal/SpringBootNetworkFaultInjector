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

## Build

```bash
./mvnw clean verify
./mvnw test
```

## License

Add a LICENSE file if you need an explicit license.
