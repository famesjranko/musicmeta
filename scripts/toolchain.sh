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

# sdk_problem: one of local.properties' `sdk.dir`, ANDROID_HOME or ~/Android/Sdk must name a
# directory. Any one is enough: AGP warns about a stale `sdk.dir` and falls back to ANDROID_HOME.
# Run from the repository root.
sdk_problem() {
    local dir props
    props="$(sed -n 's/^sdk\.dir[[:space:]]*=[[:space:]]*//p' local.properties 2>/dev/null | tail -n 1 || true)"
    for dir in "$props" "${ANDROID_HOME:-}" "$HOME/Android/Sdk"; do
        if [ -n "$dir" ] && [ -d "$dir" ]; then
            return 0
        fi
    done
    echo "not found: set ANDROID_HOME to an SDK directory, install it at ~/Android/Sdk, or put sdk.dir in local.properties"
    return 1
}
