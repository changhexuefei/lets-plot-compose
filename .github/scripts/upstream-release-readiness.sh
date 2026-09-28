#!/usr/bin/env bash
set -euo pipefail

properties_file="${UPSTREAM_READINESS_PROPERTIES_FILE:-gradle.properties}"
output_file="${UPSTREAM_READINESS_OUTPUT:-build/release-readiness/3.2.3-upstream-release-readiness.txt}"
central_base="${UPSTREAM_READINESS_CENTRAL_BASE_URL:-https://repo1.maven.org/maven2}"

fail() {
  echo "::error::$*" >&2
  exit 1
}

property_value() {
  local key="$1"
  sed -n "s/^${key}=//p" "$properties_file" | tail -n 1
}

probe_maven_pom() {
  local artifact="$1"
  local version="$2"
  local url="${central_base}/org/jetbrains/lets-plot/${artifact}/${version}/${artifact}-${version}.pom"
  local http_code

  if ! http_code="$(curl --silent --show-error --location       --retry 2 --retry-all-errors --connect-timeout 15 --max-time 60       --output /dev/null --write-out '%{http_code}' "$url")"; then
    fail "Unable to query Maven Central for ${artifact}:${version}"
  fi

  case "$http_code" in
    200) printf 'AVAILABLE' ;;
    404) printf 'MISSING' ;;
    *) fail "Unexpected Maven Central HTTP status ${http_code} for ${artifact}:${version}" ;;
  esac
}

[[ -s "$properties_file" ]] || fail "Missing properties file: $properties_file"

project_version="$(property_value 'letsPlotCompose.version')"
core_current="$(property_value 'letsPlot.version')"
kotlin_current="$(property_value 'letsPlotKotlin.version')"

[[ -n "$project_version" ]] || fail "letsPlotCompose.version is missing"
[[ -n "$core_current" ]] || fail "letsPlot.version is missing"
[[ -n "$kotlin_current" ]] || fail "letsPlotKotlin.version is missing"

core_target="${core_current%-SNAPSHOT}"
kotlin_target="${kotlin_current%-SNAPSHOT}"

[[ -n "$core_target" ]] || fail "Unable to derive core target release"
[[ -n "$kotlin_target" ]] || fail "Unable to derive Kotlin API target release"

core_status="$(probe_maven_pom 'lets-plot-common' "$core_target")"
kotlin_status="$(probe_maven_pom 'lets-plot-kotlin' "$kotlin_target")"

if [[ "$core_status" == "AVAILABLE" && "$kotlin_status" == "AVAILABLE" ]]; then
  state="UPSTREAM_RELEASES_AVAILABLE"
  if [[ "$project_version" == *-SNAPSHOT ]]; then
    next_required="UPDATE_DEPENDENCIES_AND_RUN_FINALIZATION_CI"
  else
    next_required="RUN_FINAL_RELEASE_CI"
  fi
else
  state="WAITING_FOR_UPSTREAM_RELEASES"
  next_required="NO_ACTION"
fi

mkdir -p "$(dirname "$output_file")"

cat > "$output_file" <<EOF
schema=1
result=UPSTREAM_RELEASE_READINESS_PASS
release.line=3.2.3
release.state=$state
release.next_required=$next_required
project.version=$project_version
lets_plot_core.current=$core_current
lets_plot_core.target=$core_target
lets_plot_core.maven_central=$core_status
lets_plot_kotlin.current=$kotlin_current
lets_plot_kotlin.target=$kotlin_target
lets_plot_kotlin.maven_central=$kotlin_status
check.scope=UPSTREAM_RELEASE_AVAILABILITY_ONLY
full_release_ci.executed=FALSE
external_upload.executed=FALSE
workflow.sha=${GITHUB_SHA:-LOCAL}
workflow.run_id=${GITHUB_RUN_ID:-LOCAL}
EOF

cat "$output_file"
