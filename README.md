# Spring Boot Fault Injector Starter

Inject latency and errors into outbound HTTP calls (`RestTemplate`, `RestClient`, `WebClient`) for chaos and resiliency testing. Configure rules in YAML, tune them at runtime via Actuator or a bundled UI, and optionally connect many services to a central control console.

Project Document: https://mailmtaac.sharepoint.com/:w:/s/SpringBootNetworkFaultInjectorProject/IQBaUPhm4gCsRYkpETiksLdpAZKEJPTeiThQY-qTbrP0u-4?e=lUxNwn

## Features at a glance

| Area | Capabilities |
|------|----------------|
| **Injection** | `DELAY`, `ERROR`, or `BOTH`; `PROBABILITY` or `EVERY_N` triggers; host/URL regex + HTTP method filters; per-rule `enabled`; global kill switch |
| **Clients** | Auto-wired for `RestTemplate`, `RestClient`, `WebClient` (reactive path never blocks) |
| **Extensibility** | Custom `FaultDecisionStrategy` bean replaces config-driven logic |
| **Safety** | Outbound URL exclusions for actuator, local UI, and (when enabled) agent server URL |
| **Actuator** | Read state + counters; runtime enable/disable, per-rule toggle, probability changes |
| **Local UI** | `/fault-injector` — edit defaults/rules, toggle switches, live charts, event log, export JSON/CSV, **download or merge YAML config** |
| **Resilience** | Reports tab: retry depth after injected errors, circuit-breaker observations, observed vs injected delay |
| **Cluster** *(optional)* | STOMP agent in apps + separate control server + PostgreSQL + console UI at `/console/` |

**Requirements:** Java 17+, Spring Boot 3.2.x.

## Quick try

From the repo root:

| Demo | Command | Open |
|------|---------|------|
| Local (one app) | `make demo-local` | http://localhost:8080/fault-injector/ |
| Cloud (2 services, 4 pods) | `make demo-cloud` | http://localhost:8080/console/ |

Without `make` (Windows, etc.):

```bash
mvn install -DskipTests
mvn -f examples/fault-injector-demo/pom.xml spring-boot:run
docker compose -f examples/docker/docker-compose.yml up --build
```

Local and cloud demos both use host port **8080** — run one at a time. Details: [examples/README.md](examples/README.md).

## Repository layout

```
Library (Maven reactor — what apps depend on)
  fault-injector-core          injection engine, telemetry, resilience signals
  fault-injector-actuator        /actuator/faultinjector
  fault-injector-ui              local UI + REST API
  fault-injector-protocol        agent ↔ server wire format
  fault-injector-agent           STOMP client
  spring-boot-starter-fault-injector          default starter (core + actuator + ui)
  spring-boot-starter-fault-injector-agent    above + agent

Not published as library artifacts
  examples/          config snippets, demo app, docker-compose
  platform/          control server (fault-injector-server)
  Makefile           demo-local, demo-cloud, verify
```

Consumers add **one Maven dependency**; `examples/` and `platform/` are for trying and operating the control plane only.

## Installation

**Default** — injection + actuator + local UI:

```xml
<dependency>
  <groupId>com.mta.faultinjector</groupId>
  <artifactId>spring-boot-starter-fault-injector</artifactId>
  <version>0.0.1-SNAPSHOT</version>
</dependency>
```

**Cluster** — add the agent starter (includes the default starter transitively):

```xml
<dependency>
  <groupId>com.mta.faultinjector</groupId>
  <artifactId>spring-boot-starter-fault-injector-agent</artifactId>
  <version>0.0.1-SNAPSHOT</version>
</dependency>
```

Build clients with Spring-managed builders; customizers attach automatically:

```java
@Bean RestTemplate restTemplate(RestTemplateBuilder b) { return b.build(); }
@Bean RestClient restClient(RestClient.Builder b) { return b.baseUrl("https://api.example.com").build(); }
@Bean WebClient webClient(WebClient.Builder b) { return b.build(); }
```

## Configuration

Prefix: `fault.injection`. **`enabled` defaults to `false`** — turn it on in config or at runtime.

Copy-ready samples: [examples/config/](examples/config/). The [demo app YAML](examples/fault-injector-demo/src/main/resources/application.yml) exercises every rule type.

```yaml
fault:
  injection:
    enabled: true
    defaults:
      delay-ms: 0
      error-status: 503
      error-message: "Injected fault"
      mode: PROBABILITY      # PROBABILITY | EVERY_N
      probability: 0.0
      every-n: 0
    rules:
      - name: slow-api
        url-pattern: ".*/billing/.*"
        methods: [GET, POST]
        enabled: true          # per-rule toggle (actuator / UI)
        fault: DELAY           # DELAY | ERROR | BOTH
        mode: PROBABILITY
        probability: 0.10
        delay-ms: 750
```

**Matching:** rules are ordered; **first match wins**. `host-pattern` / `url-pattern` are Java regexes; empty matches any. `methods` empty matches any method.

**Custom logic:** provide a `FaultDecisionStrategy` `@Bean` to bypass YAML rules entirely.

**Outbound exclusions** (defaults on):

| Property | Default | Purpose |
|----------|---------|---------|
| `outbound-exclude-enabled` | `true` | Master switch |
| `outbound-exclude-include-builtins` | `true` | Skip actuator, UI path, agent server URL |
| `outbound-exclude-url-patterns` | — | Extra URL regexes |

## Runtime control

### Actuator (`/actuator/faultinjector`)

Expose with `management.endpoints.web.exposure.include=faultinjector`.

| Method | Action |
|--------|--------|
| `GET` | Current config + per-rule `matchCount` / `triggerCount` |
| `POST` | `enable`, `disable`, `setRuleEnabled`, `setProbability` |

Secure write access like any sensitive actuator endpoint.

### Local UI + REST API (`${fault.injection.ui.path}/api`)

| Endpoint | Purpose |
|----------|---------|
| `GET /config` | Live configuration |
| `POST /enabled` | Global on/off |
| `PUT /defaults` | Edit shared defaults |
| `POST/PUT/DELETE /rules[...]` | CRUD rules; `POST .../enabled` per rule |
| `GET /metrics`, `/metrics/timeseries`, `/events` | Counters, chart data, recent decisions |
| `POST /metrics/reset` | Reset counters |
| `GET /export?format=json\|csv` | Export metrics/events |
| `GET /config/export?format=yaml` | Download current rules as `fault-injection.yml` |
| `POST /config/merge` | Merge runtime edits into existing `application.yml` body |

UI settings (`fault.injection.ui.*`): path (default `/fault-injector`), buffer/chart sizes, `require-localhost` for a coarse local-only gate, and `resilience.*` window tuning.

Runtime edits live in memory until restart — use **Download config** or `/config/export` to persist.

## Resilience signals

On the Reports tab (and in `GET .../api/metrics` under a `resilience` block), the library observes how **your service** reacts to injected faults:

- **Retries** — outbound calls to the same target shortly after an injected error
- **Circuit breaker** — consecutive injected errors on a host+method, then calls during an observation window
- **Observed delay** — wall-clock latency vs configured injection delay

Tune windows via `fault.injection.ui.resilience.*` (`retry-window-ms`, `cb-consecutive-error-threshold`, `cb-observation-window-ms`, `observation-buffer-size`).

## Cluster control plane *(optional)*

```
Apps (agent) ──outbound WebSocket/STOMP──► Control server + PostgreSQL
                                                    └── Console /console/
```

```yaml
fault:
  injection:
    agent:
      enabled: true
      server-url: ws://fault-injector-server:8080/ws
      service-name: ${spring.application.name}
      instance-id: ${HOSTNAME:}
      telemetry-interval-ms: 2000
      reconnect-delay-ms: 5000
```

Each instance keeps its local `/fault-injector` UI. The console lists multiple services (e.g. `billing-service`, `catalog-service`), each with several pods; use `scope=all` (default) or `scope=<instanceId>` for one pod. Telemetry aggregation includes resilience metrics.

- Full stack demo: `make demo-cloud` → [examples/README.md](examples/README.md)
- Server-only deploy: [platform/README.md](platform/README.md)

## Demo app endpoints

The [demo app](examples/fault-injector-demo/) hits configured rules via all three HTTP clients:

| Path | What it shows |
|------|----------------|
| `GET /demo/normal` | No rule match — passes through |
| `GET /demo/slow` | URL-pattern `DELAY` |
| `GET /demo/flaky` | `EVERY_N` `ERROR` |
| `GET /demo/healthy` | Matched but rule `enabled: false` |
| `POST /demo/write` | Method-filtered `BOTH` (POST only) |
| `GET /demo/write-but-get` | Same URL, GET excluded by method filter |
| `GET /demo/probabilistic?p=0.5` | Catch-all; flip probability via actuator/UI |

**Catalog profile** (`SPRING_PROFILES_ACTIVE=catalog`, cloud ports 8083–8084):

| Path | What it shows |
|------|----------------|
| `GET /demo/catalog/browse` | Product list `DELAY` |
| `GET /demo/catalog/search` | `EVERY_N` `ERROR` on recommendations |
| `GET /demo/catalog/cache` | Probabilistic `BOTH` on cache refresh |

## Build

```bash
./mvnw clean verify              # library tests
make verify                      # library + platform server tests
mvn install -DskipTests          # install SNAPSHOT locally (needed for demo/platform)
mvn -f platform/pom.xml package  # control server JAR
```

## License

Add a LICENSE file if you need an explicit license.
