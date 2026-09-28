#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
probe="$repo_root/.github/scripts/graphite-upstream-baseline-readiness.sh"
tmp_root="$(mktemp -d)"
trap 'rm -rf "$tmp_root"' EXIT

compose_old="$tmp_root/compose-old.json"
skiko_old="$tmp_root/skiko-old.json"
compose_new="$tmp_root/compose-new.json"
skiko_new="$tmp_root/skiko-new.json"

cat > "$compose_old" <<'EOF'
{"tag_name":"v1.12.1","prerelease":false,"published_at":"2026-09-22T12:11:13Z","html_url":"https://example.invalid/compose/1.12.1"}
EOF
cat > "$skiko_old" <<'EOF'
{"tag_name":"v0.150.2","prerelease":false,"published_at":"2026-09-28T10:14:21Z","html_url":"https://example.invalid/skiko/0.150.2"}
EOF
cat > "$compose_new" <<'EOF'
{"tag_name":"v1.13.0","prerelease":false,"published_at":"2026-10-01T00:00:00Z","html_url":"https://example.invalid/compose/1.13.0"}
EOF
cat > "$skiko_new" <<'EOF'
{"tag_name":"v0.153.0","prerelease":false,"published_at":"2026-10-01T00:00:00Z","html_url":"https://example.invalid/skiko/0.153.0"}
EOF

waiting_output="$tmp_root/waiting.txt"
GRAPHITE_UPSTREAM_COMPOSE_JSON="$compose_old" GRAPHITE_UPSTREAM_SKIKO_JSON="$skiko_old" GRAPHITE_UPSTREAM_OUTPUT="$waiting_output"   bash "$probe"

grep -Fx 'readiness.state=FROZEN_WAITING_FOR_STABLE_BASELINE' "$waiting_output"
grep -Fx 'readiness.next_required=NONE' "$waiting_output"
grep -Fx 'latest.stable.compose.threshold=BELOW_THRESHOLD' "$waiting_output"
grep -Fx 'latest.stable.skiko.threshold=BELOW_THRESHOLD' "$waiting_output"
grep -Fx 'production.change=NONE' "$waiting_output"

candidate_output="$tmp_root/candidate.txt"
GRAPHITE_UPSTREAM_COMPOSE_JSON="$compose_new" GRAPHITE_UPSTREAM_SKIKO_JSON="$skiko_new" GRAPHITE_UPSTREAM_OUTPUT="$candidate_output"   bash "$probe"

grep -Fx 'readiness.state=REVIEW_UPSTREAM_BASELINE' "$candidate_output"
grep -Fx 'readiness.next_required=RUN_NEW_GRAPHITE_COMPATIBILITY_PROBE' "$candidate_output"
grep -Fx 'latest.stable.compose.threshold=THRESHOLD_MET' "$candidate_output"
grep -Fx 'latest.stable.skiko.threshold=THRESHOLD_MET' "$candidate_output"
grep -Fx 'production.change=NONE' "$candidate_output"

mixed_output="$tmp_root/mixed.txt"
GRAPHITE_UPSTREAM_COMPOSE_JSON="$compose_new" GRAPHITE_UPSTREAM_SKIKO_JSON="$skiko_old" GRAPHITE_UPSTREAM_OUTPUT="$mixed_output"   bash "$probe"

grep -Fx 'readiness.state=FROZEN_WAITING_FOR_STABLE_BASELINE' "$mixed_output"
grep -Fx 'latest.stable.compose.threshold=THRESHOLD_MET' "$mixed_output"
grep -Fx 'latest.stable.skiko.threshold=BELOW_THRESHOLD' "$mixed_output"

echo 'GRAPHITE_UPSTREAM_BASELINE_CONTRACT_TEST_PASS'
