#!/bin/sh
set -eu

# Convert libpq DATABASE_URL (postgresql://user:pass@host[:port]/db)
# into JDBC URL + Spring username/password. jdbc:postgresql://user:pass@host/db
# is not a valid Postgres JDBC URL.
if [ -n "${DATABASE_URL:-}" ]; then
  raw="$DATABASE_URL"
  case "$raw" in
    jdbc:*) jdbc="$raw" ;;
    *)
      rest="${raw#*://}"
      userinfo="${rest%%@*}"
      hostpath="${rest#*@}"
      user="${userinfo%%:*}"
      pass="${userinfo#*:}"
      hostport="${hostpath%%/*}"
      dbquery="${hostpath#*/}"
      db="${dbquery%%\?*}"
      host="${hostport%%:*}"
      port="${hostport##*:}"
      if [ "$port" = "$hostport" ]; then
        port=5432
      fi
      jdbc="jdbc:postgresql://${host}:${port}/${db}"
      case "$dbquery" in
        *\?*)
          q="${dbquery#*\?}"
          case "$q" in
            *sslmode=*) jdbc="${jdbc}?${q}" ;;
            *) jdbc="${jdbc}?${q}&sslmode=require" ;;
          esac
          ;;
        *) jdbc="${jdbc}?sslmode=require" ;;
      esac
      export SPRING_DATASOURCE_USERNAME="$user"
      export SPRING_DATASOURCE_PASSWORD="$pass"
      ;;
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
