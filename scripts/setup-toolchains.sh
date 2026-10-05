#!/usr/bin/env bash
# Registers a JDK 21 in ~/.m2/toolchains.xml so ./mvnw compiles and tests on JDK 21 whatever JDK runs Maven.
# Never overwrites an existing toolchains.xml; prints the snippet to add instead.
set -euo pipefail

is_jdk21() {
  [[ -x "$1/bin/java" ]] && "$1/bin/java" -version 2>&1 | head -1 | grep -Eq '"21(\.|")'
}

find_jdk21() {
  local candidate
  # note: `java_home -v 21` means "21 or newer", so every candidate is version-checked
  for candidate in "${JAVA21_HOME:-}" "$(/usr/libexec/java_home -v 21 2>/dev/null || true)" \
      /opt/homebrew/opt/openjdk@21 /usr/local/opt/openjdk@21 \
      /Library/Java/JavaVirtualMachines/*21*/Contents/Home /usr/lib/jvm/*21*; do
    if [[ -n "$candidate" ]] && is_jdk21 "$candidate"; then echo "$candidate"; return; fi
  done
  return 1
}

# `--print` only prints the JDK 21 home (used by the Makefile and .envrc)
if [[ "${1:-}" == "--print" ]]; then find_jdk21; exit; fi

JDK=$(find_jdk21) || { echo "No JDK 21 found. Install one (e.g. brew install openjdk@21) or set JAVA21_HOME." >&2; exit 1; }
SNIPPET="  <toolchain>
    <type>jdk</type>
    <provides>
      <version>21</version>
    </provides>
    <configuration>
      <jdkHome>$JDK</jdkHome>
    </configuration>
  </toolchain>"
FILE="$HOME/.m2/toolchains.xml"

if [[ -f "$FILE" ]]; then
  if grep -q "<jdkHome>$JDK</jdkHome>" "$FILE"; then
    echo "$FILE already declares JDK 21 at $JDK."
  else
    echo "$FILE exists; add this inside <toolchains>:"; echo "$SNIPPET"; exit 1
  fi
else
  mkdir -p "$HOME/.m2"
  printf '<?xml version="1.0" encoding="UTF-8"?>\n<toolchains>\n%s\n</toolchains>\n' "$SNIPPET" > "$FILE"
  echo "Created $FILE with JDK 21 at $JDK"
fi
