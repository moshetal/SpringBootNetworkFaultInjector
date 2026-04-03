#!/bin/sh
# ----------------------------------------------------------------------------
# Maven Wrapper startup script
# This script allows running Maven without a pre-installed Maven by
# downloading the configured Maven distribution and wrapper JAR on demand.
# ----------------------------------------------------------------------------

set -eu

# Resolve base directory
PRG="$0"
while [ -h "$PRG" ]; do
  ls=`ls -ld "$PRG"`
  link=`expr "$ls" : '.*-> \(.*\)$'`
  if expr "$link" : '/.*' > /dev/null; then
    PRG="$link"
  else
    PRG=`dirname "$PRG"`/"$link"
  fi
done
BASEDIR=`cd "$(dirname "$PRG")" >/dev/null; pwd`

# Files/paths
WRAPPER_DIR="$BASEDIR/.mvn/wrapper"
PROPS_FILE="$WRAPPER_DIR/maven-wrapper.properties"
WRAPPER_JAR="$WRAPPER_DIR/maven-wrapper.jar"

# Read property from properties file
# Usage: read_prop key
read_prop() {
  key="$1"
  if [ -f "$PROPS_FILE" ]; then
    # awk to get value after '=' for the given key (ignoring comments)
    awk -F= -v k="$key" 'BEGIN{IGNORECASE=0} $1==k {print $2}' "$PROPS_FILE" | tr -d '\r' | tail -n 1
  else
    echo ""  # no properties file
  fi
}

WRAPPER_URL=`read_prop wrapperUrl`
DIST_URL=`read_prop distributionUrl`

# Download wrapper JAR if missing
if [ ! -f "$WRAPPER_JAR" ]; then
  echo "Downloading Maven Wrapper JAR from: ${WRAPPER_URL}" >&2
  mkdir -p "$WRAPPER_DIR"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL -o "$WRAPPER_JAR" "$WRAPPER_URL"
  elif command -v wget >/dev/null 2>&1; then
    wget -q -O "$WRAPPER_JAR" "$WRAPPER_URL"
  else
    echo "Error: curl or wget is required to download the Maven Wrapper JAR." >&2
    exit 1
  fi
fi

# Setup JVM options from .mvn/jvm.config if present
JVM_CONFIG="$BASEDIR/.mvn/jvm.config"
JVM_OPTS=""
if [ -f "$JVM_CONFIG" ]; then
  JVM_OPTS="`tr '\n' ' ' < "$JVM_CONFIG" | xargs`"
fi

# Export distribution URL (used by wrapper main)
export MAVEN_WRAPPER_DISABLE_SSL_VERIFICATION
export MVNW_VERBOSE
[ -n "${DIST_URL:-}" ] && export MVNW_REPOURL="$DIST_URL"

# Execute Maven via wrapper main
JAVA_EXE="${JAVA_HOME:-}/bin/java"
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_EXE" ]; then
  :
else
  JAVA_EXE="java"
fi

exec "$JAVA_EXE" $JVM_OPTS -Dmaven.multiModuleProjectDirectory="$BASEDIR" -cp "$WRAPPER_JAR" org.apache.maven.wrapper.MavenWrapperMain "$@"
