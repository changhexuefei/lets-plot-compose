#!/usr/bin/env bash
set -euo pipefail

properties_file="${RELEASE_PROPERTIES_FILE:-gradle.properties}"
build_file="${RELEASE_BUILD_FILE:-build.gradle.kts}"
readme_file="${RELEASE_README_FILE:-README.md}"
changelog_file="${RELEASE_CHANGELOG_FILE:-CHANGELOG.md}"
output_file="${RELEASE_PREFLIGHT_OUTPUT:-build/release-preflight/3.2.3-release-preflight.txt}"
central_base="${RELEASE_CENTRAL_BASE_URL:-https://repo1.maven.org/maven2}"

property_value() {
  local key="$1"
  sed -n "s/^${key}=//p" "$properties_file" | tail -n 1
}

require_line() {
  local needle="$1"
  local file="$2"
  grep -F -- "$needle" "$file" >/dev/null
}

verify_maven_pom() {
  local artifact="$1"
  local version="$2"
  local url="${central_base}/org/jetbrains/lets-plot/${artifact}/${version}/${artifact}-${version}.pom"

  if ! curl --fail --silent --show-error --location --retry 2 --retry-delay 2       --output /dev/null "$url"; then
    echo "::error::Released dependency is not available from Maven Central: ${artifact}:${version}"
    echo "::error::Checked: ${url}"
    return 1
  fi
}

for required_file in "$properties_file" "$build_file" "$readme_file" "$changelog_file"; do
  if [[ ! -s "$required_file" ]]; then
    echo "::error::Required release metadata file is missing or empty: $required_file"
    exit 1
  fi
done

project_version="$(property_value 'letsPlotCompose.version')"
core_version="$(property_value 'letsPlot.version')"
kotlin_api_version="$(property_value 'letsPlotKotlin.version')"

for value_name in project_version core_version kotlin_api_version; do
  if [[ -z "${!value_name}" ]]; then
    echo "::error::Missing required release property: $value_name"
    exit 1
  fi
done

case "$project_version" in
  3.2.3|3.2.3-SNAPSHOT) ;;
  *)
    echo "::error::Unexpected 3.2.3 release-line version: $project_version"
    exit 1
    ;;
esac

require_line 'val letsPlotComposeVersion = providers.gradleProperty("letsPlotCompose.version").get()' "$build_file"
require_line 'version = letsPlotComposeVersion' "$build_file"
require_line 'val mavenReleasePublishUrl by extra { layout.buildDirectory.dir("maven/artifacts").get().toString() }' "$build_file"
require_line 'val uploadMavenArtifacts by tasks.registering' "$build_file"
require_line '## [Unreleased]' "$changelog_file"
require_line '3.2.3' "$readme_file"

blockers=()
[[ "$project_version" == *-SNAPSHOT ]] && blockers+=("PROJECT_VERSION_IS_SNAPSHOT")
[[ "$core_version" == *-SNAPSHOT ]] && blockers+=("LETS_PLOT_CORE_IS_SNAPSHOT")
[[ "$kotlin_api_version" == *-SNAPSHOT ]] && blockers+=("LETS_PLOT_KOTLIN_API_IS_SNAPSHOT")

if [[ "$project_version" != *-SNAPSHOT ]] &&
   { [[ "$core_version" == *-SNAPSHOT ]] || [[ "$kotlin_api_version" == *-SNAPSHOT ]]; }; then
  echo "::error::Release version $project_version must not depend on SNAPSHOT Lets-Plot artifacts."
  echo "::error::letsPlot.version=$core_version"
  echo "::error::letsPlotKotlin.version=$kotlin_api_version"
  exit 1
fi

core_central_check="SKIPPED_SNAPSHOT"
if [[ "$core_version" != *-SNAPSHOT ]]; then
  verify_maven_pom "lets-plot-common" "$core_version"
  core_central_check="PASS"
fi

kotlin_central_check="SKIPPED_SNAPSHOT"
if [[ "$kotlin_api_version" != *-SNAPSHOT ]]; then
  verify_maven_pom "lets-plot-kotlin" "$kotlin_api_version"
  kotlin_central_check="PASS"
fi

if [[ "$project_version" == *-SNAPSHOT ]]; then
  if [[ "$core_version" == *-SNAPSHOT || "$kotlin_api_version" == *-SNAPSHOT ]]; then
    state="BLOCKED_EXPECTED_PRE_RELEASE"
    publishable="FALSE"
    next_required="WAIT_FOR_RELEASED_UPSTREAM_DEPENDENCIES"
  else
    state="READY_FOR_VERSION_FINALIZATION"
    publishable="FALSE"
    next_required="FINALIZE_PROJECT_VERSION_AND_RELEASE_NOTES"
  fi
else
  state="READY_FOR_RELEASE_BUILD"
  publishable="TRUE"
  next_required="RUN_SIGNED_RELEASE_BUILD_THEN_UPLOAD"
fi

blocker_csv="NONE"
if ((${#blockers[@]})); then
  blocker_csv="$(IFS=,; echo "${blockers[*]}")"
fi

mkdir -p "$(dirname "$output_file")"
cat > "$output_file" <<EOF
schema=2
result=RELEASE_DEPENDENCY_PREFLIGHT_PASS
release.line=3.2.3
release.state=$state
release.publishable=$publishable
release.blockers=$blocker_csv
release.next_required=$next_required
project.version=$project_version
lets_plot_core.version=$core_version
lets_plot_core.central_check=$core_central_check
lets_plot_kotlin.version=$kotlin_api_version
lets_plot_kotlin.central_check=$kotlin_central_check
version.source=gradle.properties
release.maven_bundle_path=build/maven/artifacts
release.upload_task=uploadMavenArtifacts
release.external_upload_executed=FALSE
release.signing_required_when_final=TRUE
production.renderer.default=NATIVE_CANVAS
graphite.production=DISABLED
workflow.sha=${GITHUB_SHA:-LOCAL}
workflow.run_id=${GITHUB_RUN_ID:-LOCAL}
EOF

cat "$output_file"
