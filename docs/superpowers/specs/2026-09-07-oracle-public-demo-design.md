# Public demo on Oracle Always Free VM

Date: 2026-09-07

## Goal

Run the existing Docker Compose cloud demo (Postgres + fault-injector-server + four dummy pods) on a free Oracle Cloud ARM VM with public HTTP URLs. Same stack as `make demo-cloud`, reachable from a browser.

## Non-goals

- HTTPS, custom domain, or a reverse proxy (Caddy can come later)
- Auth on the console (same as local)
- PaaS (Render, Fly, Hugging Face)
- Changing application Java/Spring code
- Automating Oracle account or tenancy creation

## Architecture

One Oracle Always Free ARM VM runs Docker Compose. All six containers share the Compose network so agents keep WebSocket connections to the server.

```
Internet
  :8080  → fault-injector-server  → /console/
  :8081  → billing-a
  :8082  → billing-b
  :8083  → catalog-a
  :8084  → catalog-b
  :22    → SSH (operator only)

Not public: Postgres (Docker network only, plus optional localhost:5432 on the VM)
```

Shape: `VM.Standard.A1.Flex`, 2 OCPU, 12 GB RAM, Ubuntu 22.04/24.04 ARM64, public IPv4.

Images already have arm64 variants (`postgres:16-alpine`, `maven:3.9-eclipse-temurin-17`, `eclipse-temurin:17-jre`).

## Components

Existing files stay the source of truth:

- `examples/docker/docker-compose.yml`
- `platform/fault-injector-server/Dockerfile`
- `examples/docker/Dockerfile.demo`

Add:

1. **`examples/docker/docker-compose.public.yml`** — overlay only:
   - Do not publish Postgres to `0.0.0.0:5432`. Prefer binding `127.0.0.1:5432:5432` in the base file (local `psql` still works; a public VM does not expose Postgres). Overlay must not re-publish 5432 on all interfaces.
   - Modest memory limits on each JVM service (`mem_limit` + `JAVA_TOOL_OPTIONS` / `MaxRAMPercentage`) so five JREs + Postgres stay well under 12 GB and one runaway heap cannot OOM the host.
2. **`examples/docker/oracle-public-demo.md`** — operator steps: create the VM, VCN ingress (TCP 22, 8080–8084), install Docker, clone, compose up, verify URLs. Note ARM capacity: retry another region if `Out of capacity` (`eu-frankfurt-1`, `uk-london-1`, `us-ashburn-1`).
3. **`examples/docker/oracle-bootstrap.sh`** — on a fresh Ubuntu VM: install Docker Engine + Compose plugin, then `docker compose -f docker-compose.yml -f docker-compose.public.yml up --build -d` from the repo root. No Oracle API calls.

Do not add new microservices. Skip a new Makefile target; the doc and bootstrap script are the entry points.

## Data flow

Unchanged from local cloud demo:

- Pods use `FAULT_INJECTION_AGENT_SERVER_URL=ws://fault-injector-server:8080/ws` (Compose DNS, never the public IP).
- Instance IDs stay `billing-a`, `billing-b`, `catalog-a`, `catalog-b`.
- Console at `http://<public-ip>:8080/console/` reads/writes state in Postgres on the private network.
- Hitting pod HTTP (`:8081/demo/slow`, catalog routes on 8083–8084) is optional for exercising injection. Four agents should appear after compose is healthy without extra traffic.

There is no per-service sleep. The stack stays up until the VM or compose is stopped. First start includes image builds (Maven inside Docker); later starts reuse images.

## Security (demo-grade)

- Postgres must not be reachable from the internet.
- Console and dummy apps have no auth (same as local). Treat the public IP as a disposable demo, not production.
- Keep default DB credentials; they only work inside the Docker network (and localhost on the VM if 5432 is bound to 127.0.0.1).
- Ingress: 22 (SSH, ideally from the operator’s IP), 8080–8084 from `0.0.0.0/0`. Do not open 5432.

## Failure modes

| Symptom | Likely cause | Response |
|---|---|---|
| Instance create fails | ARM capacity | Retry another home region |
| Ports closed from browser | Missing VCN security list / NSG | Add TCP 8080–8084 ingress |
| Container OOM | Heap vs `mem_limit` | Restart that service; overlay already caps RAM |
| Console up, no agents | Pods still starting or WS URL wrong | `docker compose ps` + server logs; wait for four healthy pods |
| SSH timeout | NSG missing 22, or no public IP | Fix VCN / assign public IPv4 |

No new health UI.

## Verification

After `compose up` reports healthy:

1. `docker compose ps` shows six running containers (Postgres healthy).
2. `http://<ip>:8080/console/` loads.
3. Console lists four agents: `billing-a`, `billing-b`, `catalog-a`, `catalog-b`.
4. Optional: `http://<ip>:8081/demo/slow` (and a catalog route on 8083) returns HTTP 200.

No new automated test suite.

## Operator split

The repo cannot create the Oracle tenancy. The human creates the account and VM. Everything after SSH is documented/scripted: Docker, clone, compose.

## Out of scope later

Caddy (or similar) in front of 8080–8084 for a single HTTPS hostname.
