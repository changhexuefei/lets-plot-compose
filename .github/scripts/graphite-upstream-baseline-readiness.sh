#!/usr/bin/env bash
set -euo pipefail

output_file="${GRAPHITE_UPSTREAM_OUTPUT:-build/graphite-readiness/graphite-upstream-baseline-readiness.txt}"
compose_api="${GRAPHITE_UPSTREAM_COMPOSE_API:-https://api.github.com/repos/JetBrains/compose-multiplatform/releases/latest}"
skiko_api="${GRAPHITE_UPSTREAM_SKIKO_API:-https://api.github.com/repos/JetBrains/skiko/releases/latest}"
compose_json_file="${GRAPHITE_UPSTREAM_COMPOSE_JSON:-}"
skiko_json_file="${GRAPHITE_UPSTREAM_SKIKO_JSON:-}"
minimum_compose="${GRAPHITE_UPSTREAM_MIN_COMPOSE:-1.13.0}"
minimum_skiko="${GRAPHITE_UPSTREAM_MIN_SKIKO:-0.153.0}"
github_token="${GITHUB_TOKEN:-}"

fail() {
  echo "::error::$*" >&2
  exit 1
}

fetch_release_json() {
  local api="$1"
  local file_override="$2"

  if [[ -n "$file_override" ]]; then
    [[ -s "$file_override" ]] || fail "Release JSON override is missing: $file_override"
    cat "$file_override"
    return
  fi

  local -a args=(
    --fail
    --silent
    --show-error
    --location
    --retry 2
    --retry-all-errors
    --connect-timeout 15
    --max-time 60
    -H 'Accept: application/vnd.github+json'
    -H 'X-GitHub-Api-Version: 2022-11-28'
  )

  if [[ -n "$github_token" ]]; then
    args+=( -H "Authorization: Bearer $github_token" )
  fi

  curl "${args[@]}" "$api"
}

normalize_version() {
  local tag="$1"
  tag="${tag#v}"
  printf '%s' "$tag"
}

version_ge() {
  local actual="$1"
  local minimum="$2"
  [[ "$(printf '%s\n%s\n' "$minimum" "$actual" | sort -V | tail -n 1)" == "$actual" ]]
}

compose_json="$(fetch_release_json "$compose_api" "$compose_json_file")"
skiko_json="$(fetch_release_json "$skiko_api" "$skiko_json_file")"

compose_tag="$(jq -r '.tag_name // empty' <<< "$compose_json")"
compose_prerelease="$(jq -r '.prerelease // empty' <<< "$compose_json")"
compose_published="$(jq -r '.published_at // empty' <<< "$compose_json")"
compose_url="$(jq -r '.html_url // empty' <<< "$compose_json")"

skiko_tag="$(jq -r '.tag_name // empty' <<< "$skiko_json")"
skiko_prerelease="$(jq -r '.prerelease // empty' <<< "$skiko_json")"
skiko_published="$(jq -r '.published_at // empty' <<< "$skiko_json")"
skiko_url="$(jq -r '.html_url // empty' <<< "$skiko_json")"

[[ -n "$compose_tag" ]] || fail "Compose latest release tag is missing"
[[ -n "$skiko_tag" ]] || fail "Skiko latest release tag is missing"
[[ "$compose_prerelease" == "false" ]] || fail "Compose /releases/latest unexpectedly returned a prerelease"
[[ "$skiko_prerelease" == "false" ]] || fail "Skiko /releases/latest unexpectedly returned a prerelease"

compose_version="$(normalize_version "$compose_tag")"
skiko_version="$(normalize_version "$skiko_tag")"

compose_candidate="BELOW_THRESHOLD"
skiko_candidate="BELOW_THRESHOLD"

if version_ge "$compose_version" "$minimum_compose"; then
  compose_candidate="THRESHOLD_MET"
fi

if version_ge "$skiko_version" "$minimum_skiko"; then
  skiko_candidate="THRESHOLD_MET"
fi

if [[ "$compose_candidate" == "THRESHOLD_MET" && "$skiko_candidate" == "THRESHOLD_MET" ]]; then
  state="REVIEW_UPSTREAM_BASELINE"
  next_required="RUN_NEW_GRAPHITE_COMPATIBILITY_PROBE"
else
  state="FROZEN_WAITING_FOR_STABLE_BASELINE"
  next_required="NONE"
fi

mkdir -p "$(dirname "$output_file")"

cat > "$output_file" <<EOF
schema=1
result=GRAPHITE_UPSTREAM_BASELINE_READINESS_PASS
baseline.current.compose=1.13.0-alpha01
baseline.current.skiko=0.153.0
baseline.current.scope=EXPERIMENTAL_WINDOWS_X64_JDK21
baseline.production.renderer=NATIVE_CANVAS
baseline.production.switch=NOT_REQUESTED
minimum.stable.compose=$minimum_compose
minimum.stable.skiko=$minimum_skiko
latest.stable.compose=$compose_version
latest.stable.compose.published_at=$compose_published
latest.stable.compose.url=$compose_url
latest.stable.compose.threshold=$compose_candidate
latest.stable.skiko=$skiko_version
latest.stable.skiko.published_at=$skiko_published
latest.stable.skiko.url=$skiko_url
latest.stable.skiko.threshold=$skiko_candidate
readiness.state=$state
readiness.next_required=$next_required
production.change=NONE
workflow.sha=${GITHUB_SHA:-LOCAL}
workflow.run_id=${GITHUB_RUN_ID:-LOCAL}
EOF

cat "$output_file"
