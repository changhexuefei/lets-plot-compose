#!/usr/bin/env bash
set -euo pipefail

checklist_file="${CONSUMER_CONTRACT_CHECKLIST:-docs/release/consumer-contract-readiness-checklist.md}"
rc_evidence_file="${CONSUMER_CONTRACT_RC_EVIDENCE:-evidence/readiness/3.2.3-rc-readiness.txt}"
output_file="${CONSUMER_CONTRACT_EVIDENCE_OUTPUT:-evidence/readiness/consumer-contract-evidence.txt}"

require_line() {
  local expected="$1"
  local file="$2"
  tr -d '\r' < "$file" | grep -Fqx -- "$expected"
}

require_text() {
  local expected="$1"
  local file="$2"
  grep -Fq -- "$expected" "$file"
}

for required_file in "$checklist_file" "$rc_evidence_file"; do
  if [[ ! -s "$required_file" ]]; then
    echo "::error::Required consumer-contract input is missing or empty: $required_file"
    exit 1
  fi
done

# Keep this mapping next to the human-facing checklist.  These checks prove the
# already-supported boundary without silently upgrading unimplemented lifecycle
# scenarios to release approval.
require_text 'Consumers depend only on Compose-facing APIs.' "$checklist_file"
require_text 'Renderer implementations remain internal.' "$checklist_file"
require_text 'Skiko and backend-specific APIs are not exposed.' "$checklist_file"
require_text '| Desktop JVM | Consumer smoke required |' "$checklist_file"
require_text '| Android | Published variant resolution and runtime smoke required |' "$checklist_file"
require_text '| WasmJS | Production bundle and browser smoke required |' "$checklist_file"

require_line 'result=RELEASE_CANDIDATE_READINESS_PASS' "$rc_evidence_file"
require_line 'consumer.boundary=PUBLISHED_MAVEN_ONLY' "$rc_evidence_file"
require_line 'desktop.consumer=PASS' "$rc_evidence_file"
require_line 'android.consumer=PASS' "$rc_evidence_file"
require_line 'android.emulator_runtime=PASS' "$rc_evidence_file"
require_line 'wasm.consumer=PASS' "$rc_evidence_file"
require_line 'wasm.browser_runtime=PASS' "$rc_evidence_file"
require_line 'wasm.browser_interactions=RENDER_TOOLTIP_ZOOM_PAN_PASS' "$rc_evidence_file"
require_line 'publication.metadata_targets=PASS' "$rc_evidence_file"
require_line 'desktop.required_screenshots=9' "$rc_evidence_file"
require_line 'desktop.figure_model_reconnect_after_replace=PASS' "$rc_evidence_file"
require_line 'android.figure_model_reconnect_after_replace=PASS' "$rc_evidence_file"
require_line 'wasm.figure_model_reconnect_after_replace=PASS' "$rc_evidence_file"
require_line 'production.renderer.default=NATIVE_CANVAS' "$rc_evidence_file"
require_line 'graphite.production=DISABLED' "$rc_evidence_file"

mkdir -p "$(dirname "$output_file")"
cat > "$output_file" <<EOF
schema=1
result=CONSUMER_CONTRACT_EVIDENCE_RECORDED
checklist.status=BASELINE_RECORDED_NOT_RELEASE_APPROVED
consumer.boundary=PUBLISHED_MAVEN_ONLY
public_api.compose_facing=DOCUMENTED
public_api.renderer_internal=DOCUMENTED
public_api.backend_not_exposed=DOCUMENTED
desktop.consumer_smoke=PASS
android.published_variant_and_runtime=PASS
wasm.production_bundle_and_browser=PASS
release_gate.external_consumer_build=PASS
release_gate.published_artifact_resolution=PASS
release_gate.rendering_and_interaction=PASS
release_gate.screenshot_artifacts=PASS
lifecycle.plot_replacement=PASS
lifecycle.recomposition=REQUIRES_DEDICATED_EVIDENCE
lifecycle.window_recreation=REQUIRES_DEDICATED_EVIDENCE
lifecycle.resource_disposal=REQUIRES_DEDICATED_EVIDENCE
lifecycle.configuration_changes=REQUIRES_DEDICATED_EVIDENCE
release_gate.lifecycle_regression=PARTIAL
production.renderer.default=NATIVE_CANVAS
graphite.production=DISABLED
graphite.readiness=SEPARATE_EXPERIMENTAL_GATE
workflow.sha=${GITHUB_SHA:-LOCAL}
workflow.run_id=${GITHUB_RUN_ID:-LOCAL}
EOF

cat "$output_file"
