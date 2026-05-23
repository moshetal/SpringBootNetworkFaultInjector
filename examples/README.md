# Examples and demos

Runnable demos and copy-paste config for the Spring Boot Fault Injector.

## Prerequisites

- **Local demo:** Java 17+, Maven
- **Cloud demo:** Docker with Compose

## Quick start

From the **repository root**:

| Demo | Command | What you get |
|---|---|---|
| **Local** | `make demo-local` | Single app + local UI at http://localhost:8080/fault-injector/ |
| **Cloud** | `make demo-cloud` | Server + Postgres + 2 demo pods + console at http://localhost:8080/console/ |

`make demo-local` runs `mvn install` first so the demo resolves the current library SNAPSHOT.

`make demo-cloud` builds everything inside Docker (no host `mvn install` required).

### Ports

| Service | URL (cloud demo) |
|---|---|
| Console UI | http://localhost:8080/console/ |
| Demo pod A (local UI) | http://localhost:8081/fault-injector/ |
| Demo pod B (local UI) | http://localhost:8082/fault-injector/ |
| PostgreSQL | localhost:5432 |

Do not run **local** and **cloud** demos at the same time — both use port **8080** on the host for the main HTTP entry point.

## Layout

```
examples/
├── config/                  # YAML/properties snippets (not on classpath)
├── fault-injector-demo/     # Spring Boot sample app
└── docker/                  # docker-compose + demo image Dockerfile
```

## Config snippets

Copy-ready configuration (not bundled in the library JAR):

- [config/fault-injection-example.yml](config/fault-injection-example.yml)
- [config/fault-injection-example.properties](config/fault-injection-example.properties)

## Manual commands

```bash
# Local (without Make)
mvn install -DskipTests
mvn -f examples/fault-injector-demo/pom.xml spring-boot:run

# Cloud (without Make)
docker compose -f examples/docker/docker-compose.yml up --build
```

## Server-only deployment

To run the control plane without the demo stack, see [platform/README.md](../platform/README.md).
