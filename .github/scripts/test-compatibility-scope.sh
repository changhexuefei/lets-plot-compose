#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
scope_script="$repo_root/.github/scripts/compatibility-scope.sh"
tmp_root="$(mktemp -d)"
trap 'rm -rf "$tmp_root"' EXIT

empty="$tmp_root/empty.txt"
: > "$empty"

empty_result="$(bash "$scope_script" "$empty")"
grep -Fx 'run_full=true' <<< "$empty_result"
grep -Fx 'reason=non-diff-trigger' <<< "$empty_result"

readiness="$tmp_root/readiness.txt"
cat > "$readiness" <<EOF
.github/scripts/upstream-release-readiness.sh
.github/scripts/test-upstream-release-readiness.sh
.github/workflows/upstream-release-readiness.yml
docs/release/3.2.3-release-checklist.md
CHANGELOG.md
EOF

readiness_result="$(bash "$scope_script" "$readiness")"
grep -Fx 'run_full=false' <<< "$readiness_result"
grep -Fx 'reason=readiness-only-change' <<< "$readiness_result"

mixed="$tmp_root/mixed.txt"
cat > "$mixed" <<EOF
.github/scripts/upstream-release-readiness.sh
gradle.properties
EOF

mixed_result="$(bash "$scope_script" "$mixed")"
grep -Fx 'run_full=true' <<< "$mixed_result"
grep -Fx 'reason=full-ci-relevant-change:gradle.properties' <<< "$mixed_result"

echo 'COMPATIBILITY_SCOPE_CONTRACT_TEST_PASS'
