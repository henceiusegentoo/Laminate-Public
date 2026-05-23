#!/bin/bash
set -e
ROOT_DIR="$(dirname "$(realpath "$0")")"
SERVER_LIBS_DIR="$ROOT_DIR/paper-server/build/libs"
PLUGINS_DIR="$SERVER_LIBS_DIR/plugins"
PLUGIN_LIBS_DIR="$ROOT_DIR/test-plugin/build/libs"
SERVER_JAR="laminate-server-1.21.11-R0.1-SNAPSHOT.jar"
# Use JAVA_HOME if set, otherwise fall back to java on PATH
if [[ -n "$JAVA_HOME" && -x "$JAVA_HOME/bin/java" ]]; then
  JAVA_CMD="$JAVA_HOME/bin/java"
else
  JAVA_CMD="java"
fi
DRY_RUN=0
if [[ "$1" == "--dry-run" ]]; then
  DRY_RUN=1
  shift
fi
# Remaining script arguments are forwarded to the Paper server.
# Default to "nogui" when no server arguments are supplied.
SERVER_ARGS=("${@:-nogui}")
if [[ ! -f "$ROOT_DIR/gradlew" ]]; then
  echo "[ERROR] gradlew not found: $ROOT_DIR"
  exit 1
fi
echo "[1/3] Building Laminate Server JAR (createLaminateServerJar)..."
"$ROOT_DIR/gradlew" -p "$ROOT_DIR" :paper-server:createLaminateServerJar
echo "[2/3] Building Plugin as shadowJar..."
"$ROOT_DIR/gradlew" -p "$ROOT_DIR" :test-plugin:shadowJar
echo "[3/3] Copying shadowJar to $PLUGINS_DIR..."
mkdir -p "$PLUGINS_DIR"
LATEST_PLUGIN_JAR=$(ls -t "$PLUGIN_LIBS_DIR"/*-all.jar 2>/dev/null | head -n 1)
if [[ -z "$LATEST_PLUGIN_JAR" ]]; then
  echo "[ERROR] No shadowJar found in $PLUGIN_LIBS_DIR (expected: *-all.jar)"
  exit 1
fi
cp "$LATEST_PLUGIN_JAR" "$PLUGINS_DIR/"
echo "[INFO] Copied: $LATEST_PLUGIN_JAR"
echo ""
echo "Starting Laminate from $SERVER_LIBS_DIR..."
echo "  Server JAR : $SERVER_JAR"
echo "  Server args: ${SERVER_ARGS[*]}"
echo ""
if [[ ! -f "$SERVER_LIBS_DIR/$SERVER_JAR" ]]; then
  echo "[ERROR] Server JAR not found: $SERVER_LIBS_DIR/$SERVER_JAR"
  exit 1
fi
if [[ "$DRY_RUN" -eq 1 ]]; then
  echo "[DRY-RUN] cd $SERVER_LIBS_DIR"
  echo "[DRY-RUN] $JAVA_CMD -jar $SERVER_JAR ${SERVER_ARGS[*]}"
  echo "[DONE] Dry-Run complete."
  exit 0
fi
# LaminateLauncher handles --add-opens and -javaagent automatically.
cd "$SERVER_LIBS_DIR"
"$JAVA_CMD" -jar "$SERVER_JAR" "${SERVER_ARGS[@]}"
