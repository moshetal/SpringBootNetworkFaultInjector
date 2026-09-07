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
