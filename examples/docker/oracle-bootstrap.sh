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
