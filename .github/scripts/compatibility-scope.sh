#!/usr/bin/env bash
set -euo pipefail

changed_file="${1:-}"
if [[ -z "$changed_file" ]]; then
  echo "usage: $0 <changed-files-list>" >&2
  exit 2
fi

run_full=true
reason="non-diff-trigger"

if [[ -s "$changed_file" ]]; then
  run_full=false

  while IFS= read -r path; do
    [[ -n "$path" ]] || continue

    case "$path" in
      .github/scripts/upstream-release-readiness.sh|.github/scripts/test-upstream-release-readiness.sh|.github/workflows/upstream-release-readiness.yml|.github/scripts/graphite-upstream-baseline-readiness.sh|.github/scripts/test-graphite-upstream-baseline-readiness.sh|.github/workflows/graphite-upstream-baseline-readiness.yml|docs/release/3.2.3-release-checklist.md|CHANGELOG.md)
        ;;
      *)
        run_full=true
        reason="full-ci-relevant-change:$path"
        break
        ;;
    esac
  done < "$changed_file"

  if [[ "$run_full" == "false" ]]; then
    reason="readiness-only-change"
  fi
fi

echo "run_full=$run_full"
echo "reason=$reason"
