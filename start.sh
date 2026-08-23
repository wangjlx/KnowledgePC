#!/usr/bin/env bash
set -e
BASE="$(cd "$(dirname "$0")" && pwd)"

# 1. JAVA_HOME from system environment
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA_EXE="$JAVA_HOME/bin/java"
# 2. java from PATH
elif command -v java >/dev/null 2>&1; then
    JAVA_EXE=java
# 3. Bundled JDK
elif [ -x "$BASE/../jdk17/jdk-17.0.2/bin/java" ]; then
    JAVA_EXE="$BASE/../jdk17/jdk-17.0.2/bin/java"
else
    echo "[ERROR] Java not found. Set JAVA_HOME or install JDK 11+." >&2
    exit 1
fi

CP="$BASE/server/build/classes/java/main"
CP="$CP:$BASE/server/build/libs/sqlite-jdbc-3.45.1.0.jar"
CP="$CP:$BASE/server/build/libs/json-20231013.jar"
CP="$CP:$BASE/server/build/libs/slf4j-api-2.0.9.jar"
CP="$CP:$BASE/server/build/libs/slf4j-simple-2.0.9.jar"

exec "$JAVA_EXE" -cp "$CP" com.knowledge.KnowledgeServer 8080