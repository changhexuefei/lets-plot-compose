#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
probe_script="$repo_root/.github/scripts/upstream-release-readiness.sh"
tmp_root="$(mktemp -d)"
server_pid=""

cleanup() {
  if [[ -n "$server_pid" ]]; then
    kill "$server_pid" >/dev/null 2>&1 || true
    wait "$server_pid" 2>/dev/null || true
  fi
  rm -rf "$tmp_root"
}
trap cleanup EXIT

properties="$tmp_root/gradle.properties"
cat > "$properties" <<EOF
letsPlotCompose.version=3.2.3-SNAPSHOT
letsPlot.version=4.11.1-SNAPSHOT
letsPlotKotlin.version=4.15.1-SNAPSHOT
EOF

central_root="$tmp_root/central"
mkdir -p   "$central_root/org/jetbrains/lets-plot/lets-plot-common/4.11.1"   "$central_root/org/jetbrains/lets-plot/lets-plot-kotlin/4.15.1"

printf '<project/>\n' > "$central_root/org/jetbrains/lets-plot/lets-plot-common/4.11.1/lets-plot-common-4.11.1.pom"
printf '<project/>\n' > "$central_root/org/jetbrains/lets-plot/lets-plot-kotlin/4.15.1/lets-plot-kotlin-4.15.1.pom"

port="$(python3 - <<'PY'
import socket
sock = socket.socket()
sock.bind(("127.0.0.1", 0))
print(sock.getsockname()[1])
sock.close()
PY
)"

python3 -m http.server "$port"   --bind 127.0.0.1   --directory "$central_root"   >"$tmp_root/http.log" 2>&1 &
server_pid="$!"

for _ in {1..30}; do
  if curl --silent --fail "http://127.0.0.1:$port/" >/dev/null; then
    break
  fi
  sleep 0.1
done

available_output="$tmp_root/available.txt"
UPSTREAM_READINESS_PROPERTIES_FILE="$properties" UPSTREAM_READINESS_OUTPUT="$available_output" UPSTREAM_READINESS_CENTRAL_BASE_URL="http://127.0.0.1:$port"   bash "$probe_script"

grep -Fx 'release.state=UPSTREAM_RELEASES_AVAILABLE' "$available_output"
grep -Fx 'release.next_required=UPDATE_DEPENDENCIES_AND_RUN_FINALIZATION_CI' "$available_output"
grep -Fx 'lets_plot_core.maven_central=AVAILABLE' "$available_output"
grep -Fx 'lets_plot_kotlin.maven_central=AVAILABLE' "$available_output"

rm "$central_root/org/jetbrains/lets-plot/lets-plot-kotlin/4.15.1/lets-plot-kotlin-4.15.1.pom"

waiting_output="$tmp_root/waiting.txt"
UPSTREAM_READINESS_PROPERTIES_FILE="$properties" UPSTREAM_READINESS_OUTPUT="$waiting_output" UPSTREAM_READINESS_CENTRAL_BASE_URL="http://127.0.0.1:$port"   bash "$probe_script"

grep -Fx 'release.state=WAITING_FOR_UPSTREAM_RELEASES' "$waiting_output"
grep -Fx 'release.next_required=NO_ACTION' "$waiting_output"
grep -Fx 'lets_plot_core.maven_central=AVAILABLE' "$waiting_output"
grep -Fx 'lets_plot_kotlin.maven_central=MISSING' "$waiting_output"

echo 'UPSTREAM_RELEASE_READINESS_CONTRACT_TEST_PASS'
