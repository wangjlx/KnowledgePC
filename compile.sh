#!/usr/bin/env bash
set -e
BASE="$(cd "$(dirname "$0")" && pwd)"

# 1. JAVA_HOME from system environment
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
    JAVAC_EXE="$JAVA_HOME/bin/javac"
# 2. javac from PATH
elif command -v javac >/dev/null 2>&1; then
    JAVAC_EXE=javac
# 3. Bundled JDK
elif [ -x "$BASE/../jdk17/jdk-17.0.2/bin/javac" ]; then
    JAVAC_EXE="$BASE/../jdk17/jdk-17.0.2/bin/javac"
else
    echo "[ERROR] Java compiler not found. Set JAVA_HOME or install JDK 11+." >&2
    exit 1
fi

CLASSES="$BASE/server/build/classes/java/main"
LIBS="$BASE/server/build/libs"
mkdir -p "$CLASSES"
"$JAVAC_EXE" -encoding UTF-8 -d "$CLASSES" -cp "$LIBS/sqlite-jdbc-3.45.1.0.jar;$LIBS/json-20231013.jar" "$BASE/server/src/main/java/com/knowledge/"*.java
echo "Compile done."