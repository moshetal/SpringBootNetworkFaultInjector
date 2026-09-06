# Examples and demos

Runnable demos and copy-paste config for the Spring Boot Fault Injector.

## Prerequisites

- **Local demo:** Java 17+, Maven
- **Cloud demo:** Docker with Compose
- **Node demo:** Java 17+, Maven, Node.js 18+, npm

## Quick start

From the **repository root**:

| Demo | Command | What you get |
|---|---|---|
| **Local** | `make demo-local` | Single app + local UI at http://localhost:8080/fault-injector/ |
| **Cloud** | `make demo-cloud` | Server + Postgres + 2 billing pods + 2 catalog pods + console at http://localhost:8080/console/ |
| **Node** | `make demo-node` | fetch + Axios fault-injection demo, no UI |

`make demo-local` runs `mvn install` first so the demo resolves the current library SNAPSHOT.

`make demo-cloud` builds everything inside Docker (no host `mvn install` required).

### Ports

| Service | URL (cloud demo) |
|---|---|
| Console UI | http://localhost:8080/console/ |
| **billing-service** pod A | http://localhost:8081/fault-injector/ |
| **billing-service** pod B | http://localhost:8082/fault-injector/ |
| **catalog-service** pod A | http://localhost:8083/fault-injector/ |
| **catalog-service** pod B | http://localhost:8084/fault-injector/ |
| PostgreSQL | localhost:5432 |

The cloud stack runs **two logical services** (`billing-service`, `catalog-service`), each with two pods. Same demo JAR, different Spring profiles (`billing`, `catalog`) and fault rules. Use billing endpoints (`/demo/slow`, `/demo/flaky`, …) on ports 8081–8082 and catalog endpoints (`/demo/catalog/browse`, `/demo/catalog/search`, …) on 8083–8084.

Do not run **local** and **cloud** demos at the same time — both use port **8080** on the host for the main HTTP entry point.

## Layout

```
examples/
├── config/                  # YAML/properties snippets (not on classpath)
├── fault-injector-demo/     # Spring Boot sample app
├── fault-injector-node-demo/ # Node fetch + Axios sample app
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
