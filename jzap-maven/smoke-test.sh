#!/usr/bin/env bash
# Runs the Maven plugin against a generated project whose expected result is known by hand.
#
# A shell script rather than a JUnit test because the thing being tested is a Maven build, and
# the honest way to test a Maven build is to run one.
set -euo pipefail

ENGINE_LIB="${1:?usage: smoke-test.sh <engine lib dir>}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Read from the POM rather than pinned here. Pinning meant a version bump left this testing a
# coordinate that was no longer built, and the failure surfaced as "plugin not found" rather than
# as the stale reference it was.
POM="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/pom.xml"
PLUGIN_VERSION="$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' "$POM" | head -1)"
[ -n "$PLUGIN_VERSION" ] || { echo "FAIL: could not read the plugin version from $POM" >&2; exit 1; }
echo "jzap-maven-plugin version under test: $PLUGIN_VERSION"

CLASSPATH="$(ls "$ENGINE_LIB"/*.jar | tr '\n' ':' | sed 's/:$//')"

mkdir -p "$WORK/src/main/java/demo" "$WORK/src/test/java/demo"

cat > "$WORK/pom.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>demo</groupId>
  <artifactId>jzap-smoke</artifactId>
  <version>1.0</version>
  <properties><maven.compiler.release>17</maven.compiler.release></properties>
  <dependencies>
    <dependency>
      <groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId>
      <version>5.14.0</version><scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.junit.platform</groupId><artifactId>junit-platform-launcher</artifactId>
      <version>1.14.0</version><scope>test</scope>
    </dependency>
  </dependencies>
  <build><plugins><plugin>
    <groupId>io.github.huyz0</groupId>
    <artifactId>jzap-maven-plugin</artifactId>
    <version>$PLUGIN_VERSION</version>
    <configuration>
      <engineClasspath>$CLASSPATH</engineClasspath>
      <reporters>console,json</reporters>
    </configuration>
  </plugin></plugins></build>
</project>
EOF

cat > "$WORK/src/main/java/demo/Fees.java" <<'EOF'
package demo;

public class Fees {
    public int fee(int amount) {
        if (amount > 1000) {
            return amount / 100;
        }
        return 10;
    }
}
EOF

cat > "$WORK/src/test/java/demo/FeesTest.java" <<'EOF'
package demo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FeesTest {
    @Test
    void chargesPercentageOnLargeAmounts() {
        assertEquals(20, new Fees().fee(2000));
    }

    @Test
    void chargesFlatFeeOtherwise() {
        assertEquals(10, new Fees().fee(100));
    }
}
EOF

cd "$WORK"
mvn -q -B test-compile io.github.huyz0:jzap-maven-plugin:$PLUGIN_VERSION:mutationCoverage > "$WORK/out.txt" 2>&1 || {
    cat "$WORK/out.txt"; echo "FAIL: the Maven plugin did not complete"; exit 1;
}

RESULT="$WORK/target/jzap/jzap-result.json"
[ -f "$RESULT" ] || { cat "$WORK/out.txt"; echo "FAIL: no report at $RESULT"; exit 1; }

# Derived by hand from Fees.java: the tests assert 2000 and 100 but never 1000 exactly, so moving
# the boundary is invisible and everything else is observable.
python3 - "$RESULT" <<'PY'
import json, sys
result = json.load(open(sys.argv[1]))
statuses = {}
for mutant in result["mutants"]:
    statuses.setdefault(mutant["status"], []).append(mutant["key"])
survived = statuses.get("SURVIVED", [])
killed = statuses.get("KILLED", [])
problems = []
if len(survived) != 1 or "CONDITIONALS_BOUNDARY" not in survived[0]:
    problems.append(f"expected one surviving boundary mutant, got {survived}")
if not killed:
    problems.append("expected some mutants to be killed")
if statuses.get("NO_COVERAGE"):
    problems.append(f"nothing should be uncovered, got {statuses['NO_COVERAGE']}")
if statuses.get("RUN_ERROR"):
    problems.append(f"run errors: {statuses['RUN_ERROR']}")
if problems:
    print("FAIL: " + "; ".join(problems))
    sys.exit(1)
print(f"PASS: {len(killed)} killed, {len(survived)} survived, as derived by hand")
PY
