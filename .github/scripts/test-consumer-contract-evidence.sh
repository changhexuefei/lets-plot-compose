#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="$repo_root/.github/scripts/consumer-contract-evidence.sh"
tmp_root="$(mktemp -d)"
trap 'rm -rf "$tmp_root"' EXIT

checklist="$tmp_root/checklist.md"
evidence="$tmp_root/rc-readiness.txt"
output="$tmp_root/consumer-contract-evidence.txt"

cp "$repo_root/docs/release/consumer-contract-readiness-checklist.md" "$checklist"
cat > "$evidence" <<'EOF'
result=RELEASE_CANDIDATE_READINESS_PASS
consumer.boundary=PUBLISHED_MAVEN_ONLY
desktop.consumer=PASS
android.consumer=PASS
android.emulator_runtime=PASS
wasm.consumer=PASS
wasm.browser_runtime=PASS
wasm.browser_interactions=RENDER_TOOLTIP_ZOOM_PAN_PASS
publication.metadata_targets=PASS
desktop.required_screenshots=9
desktop.figure_model_reconnect_after_replace=PASS
android.figure_model_reconnect_after_replace=PASS
wasm.figure_model_reconnect_after_replace=PASS
production.renderer.default=NATIVE_CANVAS
graphite.production=DISABLED
EOF

CONSUMER_CONTRACT_CHECKLIST="$checklist" \
CONSUMER_CONTRACT_RC_EVIDENCE="$evidence" \
CONSUMER_CONTRACT_EVIDENCE_OUTPUT="$output" \
  bash "$script"

grep -Fx 'result=CONSUMER_CONTRACT_EVIDENCE_RECORDED' "$output"
grep -Fx 'lifecycle.plot_replacement=PASS' "$output"
grep -Fx 'lifecycle.recomposition=REQUIRES_DEDICATED_EVIDENCE' "$output"
grep -Fx 'release_gate.lifecycle_regression=PARTIAL' "$output"

if sed -i '/wasm.browser_runtime=PASS/d' "$evidence" && \
  CONSUMER_CONTRACT_CHECKLIST="$checklist" \
  CONSUMER_CONTRACT_RC_EVIDENCE="$evidence" \
  CONSUMER_CONTRACT_EVIDENCE_OUTPUT="$output" \
  bash "$script"; then
  echo 'expected missing Wasm evidence to fail' >&2
  exit 1
fi
