#!/usr/bin/env bash
# Sourced by ./check and scripts/bootstrap.sh, so the gate and the report cannot disagree about what
# counts as a usable JDK or Android SDK. Each function prints one reason and returns 1 when the
# toolchain piece is unusable, and prints nothing and returns 0 otherwise.

# jdk_problem <major>: the java that ./gradlew would run must report exactly this specification
# version. Exact, not a minimum: Kotlin resolves JDK classes from the running JDK, so a newer JDK
# compiles an API that CI's JDK 17 lacks.
jdk_problem() {
    local want="$1" java spec
    if [ -n "${JAVA_HOME:-}" ]; then
        # gradlew dies on an invalid JAVA_HOME instead of falling back to PATH, so this does too.
        java="$JAVA_HOME/bin/java"
        if [ ! -x "$java" ]; then
            echo "JAVA_HOME=$JAVA_HOME has no executable bin/java, want $want"
            return 1
        fi
    elif command -v java >/dev/null 2>&1; then
        java="java"
    else
        echo "absent, want $want"
        return 1
    fi
    # java.specification.version is "17" or "1.8" on every vendor. `java -version` prose is not
    # stable, and JAVA_TOOL_OPTIONS puts a line before it. A java that fails, like the macOS shim
    # without a JDK, prints nothing and is reported rather than aborting the caller.
    spec="$("$java" -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java\.specification\.version = //p' || true)"
    if [ "$spec" != "$want" ]; then
        echo "${spec:-unusable}, want $want"
        return 1
    fi
}

# sdk_problem: the Android SDK location Gradle would use must be a directory. Gradle reads
# `sdk.dir` from local.properties first and ANDROID_HOME only when that line is absent, so a stale
# `sdk.dir` fails the build even beside a good ANDROID_HOME. Run from the repository root.
sdk_problem() {
    local dir
    dir="$(sed -n 's/^sdk\.dir[[:space:]]*=[[:space:]]*//p' local.properties 2>/dev/null | tail -n 1 || true)"
    if [ -n "$dir" ]; then
        if [ ! -d "$dir" ]; then
            echo "local.properties sdk.dir=$dir is not a directory"
            return 1
        fi
        return 0
    fi
    dir="${ANDROID_HOME:-$HOME/Android/Sdk}"
    if [ ! -d "$dir" ]; then
        echo "not found: ${ANDROID_HOME:+ANDROID_HOME=$ANDROID_HOME is not a directory; }set ANDROID_HOME, install it at ~/Android/Sdk, or put sdk.dir in local.properties"
        return 1
    fi
}
