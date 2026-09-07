# Oracle Public Demo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a Compose overlay, Ubuntu bootstrap script, and operator doc so the existing six-container cloud demo can run on an Oracle Always Free ARM VM with public HTTP URLs.

**Architecture:** Keep `examples/docker/docker-compose.yml` as the stack. Bind Postgres to `127.0.0.1` so a public VM does not expose 5432. Add `docker-compose.public.yml` for JVM memory caps. Operator creates the Oracle VM; after SSH, `oracle-bootstrap.sh` installs Docker and brings the stack up.

**Tech Stack:** Docker Compose v2, Oracle Cloud Always Free `VM.Standard.A1.Flex` (2 OCPU / 12 GB, Ubuntu ARM64), existing Temurin 17 Dockerfiles.

## Global Constraints

- Do not change application Java/Spring code or the two Dockerfiles.
- Do not add a Makefile target, reverse proxy, HTTPS, auth, or Oracle API automation.
- Do not publish Postgres on `0.0.0.0:5432`.
- Overlay must not re-publish 5432 on all interfaces.
- Agent WebSocket URL stays `ws://fault-injector-server:8080/ws` (Compose DNS).
- JVM memory: `mem_limit: 512m` on server and each demo pod, `mem_limit: 256m` on Postgres, plus `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75.0` on JVM services.
- Ingress documented as TCP 22 and 8080–8084; do not open 5432.
- No new automated test suite; verify with `docker compose … config` and (on the VM) the smoke checks in the spec.

## File map

| File | Role |
|---|---|
| `examples/docker/docker-compose.yml` | Bind Postgres published port to `127.0.0.1` only |
| `examples/docker/docker-compose.public.yml` | Memory caps for public VM; no extra ports |
| `examples/docker/oracle-bootstrap.sh` | Install Docker on Ubuntu, compose up overlay |
| `examples/docker/oracle-public-demo.md` | Operator steps: VM, VCN, bootstrap, verify, failures |
| `examples/README.md` | Link to the public demo doc |

---

### Task 1: Bind Postgres to localhost

**Files:**
- Modify: `examples/docker/docker-compose.yml` (postgres `ports`)
- Test: `docker compose -f examples/docker/docker-compose.yml config` (no new test file)

**Interfaces:**
- Consumes: existing `postgres` service with `ports: ["5432:5432"]`
- Produces: `postgres` published as `127.0.0.1:5432:5432` so local `psql` still works and a public NIC does not expose Postgres

- [ ] **Step 1: Capture current published Postgres port (expect all-interfaces)**

From repo root:

```bash
docker compose -f examples/docker/docker-compose.yml config
```

Expected: YAML includes a published mapping for container port 5432 without `127.0.0.1` (typically `0.0.0.0:5432` / host IP empty). If Docker is not installed, skip to Step 3 and run Step 4 after Docker is available.

- [ ] **Step 2: Change only the Postgres ports line**

In `examples/docker/docker-compose.yml`, replace:

```yaml
    ports:
      - "5432:5432"
```

with:

```yaml
    ports:
      - "127.0.0.1:5432:5432"
```

Do not change any other service.

- [ ] **Step 3: Re-render compose config and confirm localhost bind**

```bash
docker compose -f examples/docker/docker-compose.yml config
```

Expected: Postgres published port host is `127.0.0.1` (or equivalent `127.0.0.1:5432:5432/tcp`). Services `fault-injector-server`, `billing-a`, `billing-b`, `catalog-a`, `catalog-b` still publish `8080`/`8081`/`8082`/`8083`/`8084` on all interfaces.

- [ ] **Step 4: Commit**

```bash
git add examples/docker/docker-compose.yml
git commit -m "$(cat <<'EOF'
fix: bind demo Postgres to localhost so public VMs do not expose 5432

EOF
)"
```

---

### Task 2: Public Compose overlay with memory caps

**Files:**
- Create: `examples/docker/docker-compose.public.yml`
- Test: `docker compose -f examples/docker/docker-compose.yml -f examples/docker/docker-compose.public.yml config`

**Interfaces:**
- Consumes: Task 1 base compose (Postgres already on `127.0.0.1:5432`)
- Produces: merged stack with `mem_limit` 256m (postgres) / 512m (five JVM services) and `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75.0` on JVM services only; no additional `ports:` keys

- [ ] **Step 1: Write the overlay file**

Create `examples/docker/docker-compose.public.yml` with this exact content:

```yaml
services:
  postgres:
    mem_limit: 256m

  fault-injector-server:
    mem_limit: 512m
    environment:
      JAVA_TOOL_OPTIONS: "-XX:MaxRAMPercentage=75.0"

  billing-a:
    mem_limit: 512m
    environment:
      JAVA_TOOL_OPTIONS: "-XX:MaxRAMPercentage=75.0"

  billing-b:
    mem_limit: 512m
    environment:
      JAVA_TOOL_OPTIONS: "-XX:MaxRAMPercentage=75.0"

  catalog-a:
    mem_limit: 512m
    environment:
      JAVA_TOOL_OPTIONS: "-XX:MaxRAMPercentage=75.0"

  catalog-b:
    mem_limit: 512m
    environment:
      JAVA_TOOL_OPTIONS: "-XX:MaxRAMPercentage=75.0"
```

- [ ] **Step 2: Render the merged config**

```bash
docker compose \
  -f examples/docker/docker-compose.yml \
  -f examples/docker/docker-compose.public.yml \
  config
```

Expected:
- Postgres host bind remains `127.0.0.1` for 5432 (overlay did not add a `0.0.0.0` mapping).
- `mem_limit` is `268435456` (256m) on `postgres` and `536870912` (512m) on the five JVM services (Compose may print bytes).
- Each of `fault-injector-server`, `billing-a`, `billing-b`, `catalog-a`, `catalog-b` has `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75.0`.
- Agent URL still `ws://fault-injector-server:8080/ws` on the four demo services.

- [ ] **Step 3: Commit**

```bash
git add examples/docker/docker-compose.public.yml
git commit -m "$(cat <<'EOF'
feat: add public-demo Compose overlay with JVM memory caps

EOF
)"
```

---

### Task 3: Ubuntu bootstrap script

**Files:**
- Create: `examples/docker/oracle-bootstrap.sh`
- Test: `bash -n examples/docker/oracle-bootstrap.sh` (syntax only; do not install Docker on the dev machine)

**Interfaces:**
- Consumes: overlay command from Task 2 (`-f examples/docker/docker-compose.yml -f examples/docker/docker-compose.public.yml`)
- Produces: executable script that resolves repo root from its path, installs Docker Engine + Compose plugin if missing, then `docker compose … up --build -d`. No Oracle API calls.

- [ ] **Step 1: Write the script**

Create `examples/docker/oracle-bootstrap.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

run() {
  if [[ "${EUID}" -eq 0 ]]; then
    "$@"
  else
    sudo "$@"
  fi
}

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${ROOT}"

if ! command -v docker >/dev/null 2>&1; then
  curl -fsSL https://get.docker.com | run sh
fi

run usermod -aG docker "${SUDO_USER:-${USER}}" || true

docker_cmd() {
  if docker info >/dev/null 2>&1; then
    docker "$@"
  else
    run docker "$@"
  fi
}

# Oracle Ubuntu images often filter INPUT in iptables independently of VCN rules.
if command -v iptables >/dev/null 2>&1; then
  run iptables -C INPUT -p tcp --dport 8080:8084 -j ACCEPT 2>/dev/null \
    || run iptables -I INPUT -p tcp --dport 8080:8084 -j ACCEPT || true
fi

docker_cmd compose \
  -f examples/docker/docker-compose.yml \
  -f examples/docker/docker-compose.public.yml \
  up --build -d

echo
echo "Stack starting. Console: http://$(curl -fsS --max-time 2 ifconfig.me || echo '<public-ip>'):8080/console/"
echo "If this session cannot talk to Docker yet, log out and back in (docker group) and re-run this script."
```

- [ ] **Step 2: Make it executable and syntax-check**

```bash
chmod +x examples/docker/oracle-bootstrap.sh
bash -n examples/docker/oracle-bootstrap.sh
```

Expected: no output, exit 0.

- [ ] **Step 3: Commit**

```bash
git add examples/docker/oracle-bootstrap.sh
git commit -m "$(cat <<'EOF'
feat: add Ubuntu bootstrap script for the Oracle public demo

EOF
)"
```

---

### Task 4: Operator doc and examples README link

**Files:**
- Create: `examples/docker/oracle-public-demo.md`
- Modify: `examples/README.md`

**Interfaces:**
- Consumes: bootstrap path `examples/docker/oracle-bootstrap.sh`; URLs `:8080/console/`, `:8081/demo/slow`, `:8083/demo/catalog/browse`; failure table from the spec
- Produces: copy-paste operator guide covering VM shape, VCN ingress, clone, bootstrap, smoke checks, ARM capacity regions

- [ ] **Step 1: Write `examples/docker/oracle-public-demo.md`**

Create the file with this exact content:

```markdown
# Public cloud demo (Oracle Always Free)

Run the existing Compose stack (Postgres + control server + four dummy pods) on one free ARM VM. Public HTTP, no HTTPS, no auth — disposable demo only.

## 1. Create the VM

1. Sign up for [Oracle Cloud](https://www.oracle.com/cloud/free/) (card for verification is common; Always Free is not billed if you stay on the free shape).
2. Compute → Create instance.
3. Image: **Ubuntu 22.04 or 24.04**.
4. Shape: **VM.Standard.A1.Flex**, **2 OCPU**, **12 GB** RAM (Always Free Ampere A1).
5. Networking: assign a **public IPv4**.
6. Add your SSH public key.
7. If create fails with out of capacity, retry another region: `eu-frankfurt-1`, `uk-london-1`, `us-ashburn-1`.

## 2. Open ports (VCN)

In the subnet’s **security list** and/or **network security group**:

| Direction | Protocol | Ports | Source |
|---|---|---|---|
| Ingress | TCP | 22 | your IP (or `0.0.0.0/0` if you must) |
| Ingress | TCP | 8080–8084 | `0.0.0.0/0` |

Do **not** open 5432. Postgres is bound to localhost on the VM only.

## 3. SSH, clone, bootstrap

```bash
ssh ubuntu@<public-ip>
sudo apt-get update && sudo apt-get install -y git
git clone <this-repo-url>
cd SpringBootNetworkFaultInjector
./examples/docker/oracle-bootstrap.sh
```

First `compose up` builds Maven inside Docker (several minutes). Later starts reuse images.

If Docker complains about permissions, log out and back in (or `newgrp docker`) and re-run the script.

## 4. Verify

```bash
docker compose \
  -f examples/docker/docker-compose.yml \
  -f examples/docker/docker-compose.public.yml \
  ps
```

Six containers should be running; Postgres `healthy`.

| What | URL |
|---|---|
| Console | `http://<public-ip>:8080/console/` |
| billing-a | `http://<public-ip>:8081/` |
| billing-b | `http://<public-ip>:8082/` |
| catalog-a | `http://<public-ip>:8083/` |
| catalog-b | `http://<public-ip>:8084/` |

Console should list agents `billing-a`, `billing-b`, `catalog-a`, `catalog-b`.

Optional HTTP checks:

```bash
curl -sS -o /dev/null -w "%{http_code}\n" http://<public-ip>:8081/demo/slow
curl -sS -o /dev/null -w "%{http_code}\n" http://<public-ip>:8083/demo/catalog/browse
```

Expect `200`.

## 5. If something is wrong

| Symptom | Likely cause | What to do |
|---|---|---|
| Instance create fails | ARM capacity | Retry `eu-frankfurt-1`, `uk-london-1`, or `us-ashburn-1` |
| Ports closed from browser | Missing VCN NSG/security list, or host iptables | Open TCP 8080–8084; re-run bootstrap (it inserts iptables ACCEPT) |
| Container OOM | Heap vs memory cap | `docker compose … restart <service>`; overlay already sets `mem_limit` |
| Console up, no agents | Pods still starting | `docker compose … ps` and `docker compose … logs -f`; wait until four pods are up |
| SSH timeout | No public IP or port 22 closed | Assign public IPv4; open TCP 22 |

## 6. Stop

On the VM, from the repo root:

```bash
docker compose \
  -f examples/docker/docker-compose.yml \
  -f examples/docker/docker-compose.public.yml \
  down
```
```

- [ ] **Step 2: Link it from `examples/README.md`**

After the “Server-only deployment” paragraph, append:

```markdown
## Public Oracle demo

To run the cloud stack on a free Oracle ARM VM (public IP, four pods), see [docker/oracle-public-demo.md](docker/oracle-public-demo.md).
```

Also extend the Layout tree `docker/` comment to mention the public overlay and doc:

```
└── docker/                  # docker-compose, public overlay, Oracle demo doc
```

- [ ] **Step 3: Commit**

```bash
git add examples/docker/oracle-public-demo.md examples/README.md
git commit -m "$(cat <<'EOF'
docs: add Oracle Always Free public demo operator guide

EOF
)"
```

---

### Task 5: Local merge sanity (no Oracle required)

**Files:**
- None (verification only)

**Interfaces:**
- Consumes: files from Tasks 1–4
- Produces: confirmation that merged Compose config matches the spec; live VM smoke stays a human step on Oracle

- [ ] **Step 1: Merged config assertions**

From repo root:

```bash
docker compose \
  -f examples/docker/docker-compose.yml \
  -f examples/docker/docker-compose.public.yml \
  config --format json > /tmp/fi-public-compose.json

python3 - <<'PY'
import json
from pathlib import Path
cfg = json.loads(Path("/tmp/fi-public-compose.json").read_text())
svcs = cfg["services"]
assert set(svcs) == {
    "postgres", "fault-injector-server",
    "billing-a", "billing-b", "catalog-a", "catalog-b",
}
pubs = svcs["postgres"].get("ports") or svcs["postgres"].get("published") or []
# Compose JSON: ports is a list of dicts with host_ip / published / target
ports = svcs["postgres"]["ports"]
assert any(
    str(p.get("target")) == "5432" and p.get("host_ip") in ("127.0.0.1", "localhost")
    for p in ports
), ports
for name in ("fault-injector-server", "billing-a", "billing-b", "catalog-a", "catalog-b"):
    mem = svcs[name].get("mem_limit") or svcs[name].get("memLimit")
    # compose v2 json uses mem_limit as int bytes
    assert int(mem) == 512 * 1024 * 1024, (name, mem)
    env = svcs[name].get("environment") or {}
    if isinstance(env, list):
        env = dict(item.split("=", 1) for item in env)
    assert env.get("JAVA_TOOL_OPTIONS") == "-XX:MaxRAMPercentage=75.0", (name, env)
    if name != "fault-injector-server":
        assert env.get("FAULT_INJECTION_AGENT_SERVER_URL") == "ws://fault-injector-server:8080/ws"
assert int(svcs["postgres"].get("mem_limit") or svcs["postgres"].get("memLimit")) == 256 * 1024 * 1024
print("ok")
PY

bash -n examples/docker/oracle-bootstrap.sh
test -x examples/docker/oracle-bootstrap.sh
test -f examples/docker/oracle-public-demo.md
```

Expected: `ok`, then silent success for `bash -n` and `test`.

If `config --format json` is unsupported, use YAML `config` and visually confirm the same facts.

- [ ] **Step 2: Commit only if Step 1 forced a fix**

If assertions failed and you changed files, commit those fixes with a message that states what was wrong. If they passed, do not create an empty commit.

---

## Spec coverage

| Spec requirement | Task |
|---|---|
| Postgres not on `0.0.0.0:5432`; bind `127.0.0.1` in base compose | 1 |
| Overlay memory caps; overlay does not re-publish 5432 | 2 |
| `oracle-bootstrap.sh` (Docker + compose up, no Oracle API) | 3 |
| `oracle-public-demo.md` (VM, VCN, regions, verify, failures) | 4 |
| README entry point; no Makefile target | 4 |
| Agent WS via Compose DNS | 2 (asserted), 5 |
| Smoke URLs and four instance IDs | 4 |
| Operator creates tenancy/VM | 4 |

## Execution notes

Creating the Oracle account and VM is **not** a repo task. After this plan is implemented, the human follows `examples/docker/oracle-public-demo.md` on a real VM to complete the live smoke (console + four agents).
