#!/bin/sh
set -eu

if [ -n "${DATABASE_URL:-}" ]; then
  url="$DATABASE_URL"
  case "$url" in
    postgres://*) url="postgresql://${url#postgres://}" ;;
  esac
  case "$url" in
    *sslmode=*) jdbc="jdbc:${url}" ;;
    *\?*) jdbc="jdbc:${url}&sslmode=require" ;;
    *) jdbc="jdbc:${url}?sslmode=require" ;;
  esac
  export SPRING_DATASOURCE_URL="$jdbc"
fi

if [ -n "${FAULT_INJECTOR_SERVER_HOST:-}" ]; then
  export FAULT_INJECTION_AGENT_SERVER_URL="wss://${FAULT_INJECTOR_SERVER_HOST}/ws"
fi

if [ -n "${PORT:-}" ]; then
  set -- --server.port="$PORT" "$@"
fi

exec java -jar /app/app.jar "$@"
