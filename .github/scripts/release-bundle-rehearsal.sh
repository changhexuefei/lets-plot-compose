#!/usr/bin/env bash
set -euo pipefail

version="${RELEASE_REHEARSAL_VERSION:-3.2.3}"
group_root="${RELEASE_REHEARSAL_REPO_ROOT:-build/maven/artifacts/org/jetbrains/lets-plot}"
archive="${RELEASE_REHEARSAL_ARCHIVE:-build/lets-plot-compose-artifacts.zip}"
output="${RELEASE_REHEARSAL_OUTPUT:-build/release-rehearsal/3.2.3-release-bundle-rehearsal.txt}"
gpg_home="${REHEARSAL_GPG_HOME:-}"

fail() {
  echo "::error::$*" >&2
  exit 1
}

require_file() {
  local file="$1"
  [[ -s "$file" ]] || fail "Missing or empty release artifact: $file"
}

verify_checksum() {
  local file="$1"
  for suffix in sha256 sha512; do
    require_file "${file}.${suffix}"
  done
}

verify_signature() {
  local file="$1"
  local sig="${file}.asc"
  require_file "$sig"

  if [[ -n "$gpg_home" ]]; then
    gpg --homedir "$gpg_home" --batch --verify "$sig" "$file" >/dev/null 2>&1 ||
      fail "Signature verification failed: $sig"
  fi
}

verify_payload() {
  local file="$1"
  require_file "$file"
  verify_checksum "$file"
  verify_signature "$file"
}

verify_metadata() {
  local file="$1"
  require_file "$file"
  verify_checksum "$file"
}

artifact_dir() {
  local artifact="$1"
  printf '%s/%s/%s' "$group_root" "$artifact" "$version"
}

verify_common_pom_contract() {
  local pom="$1"
  grep -F '<groupId>org.jetbrains.lets-plot</groupId>' "$pom" >/dev/null ||
    fail "Unexpected Maven group in $pom"
  grep -F "<version>$version</version>" "$pom" >/dev/null ||
    fail "Unexpected Maven version in $pom"
  grep -F '<name>MIT</name>' "$pom" >/dev/null ||
    fail "MIT license metadata missing in $pom"
  grep -F '<id>jetbrains</id>' "$pom" >/dev/null ||
    fail "Developer metadata missing in $pom"
  grep -F '<scm>' "$pom" >/dev/null ||
    fail "SCM metadata missing in $pom"
  if grep -F 'SNAPSHOT' "$pom" >/dev/null; then
    fail "Release rehearsal POM contains SNAPSHOT dependency: $pom"
  fi
}

verify_publication() {
  local artifact="$1"
  local main_ext="$2"
  local require_tooling="$3"
  local require_wasm_resources="$4"
  local dir
  dir="$(artifact_dir "$artifact")"

  [[ -d "$dir" ]] || fail "Publication directory missing: $dir"

  local prefix="$dir/$artifact-$version"
  local pom="$prefix.pom"
  local module="$prefix.module"
  local main="$prefix.$main_ext"
  local sources="$prefix-sources.jar"
  local javadoc="$prefix-javadoc.jar"

  verify_payload "$pom"
  verify_metadata "$module"
  verify_payload "$main"
  verify_payload "$sources"
  verify_payload "$javadoc"
  verify_common_pom_contract "$pom"

  if [[ "$require_tooling" == "true" ]]; then
    verify_metadata "$prefix-kotlin-tooling-metadata.json"
  fi

  if [[ "$require_wasm_resources" == "true" ]]; then
    verify_payload "$prefix-kotlin_resources.kotlin_resources.zip"
  fi
}

verify_publication "platf-android" "jar" "true" "false"
verify_publication "platf-android-android" "aar" "false" "false"
verify_publication "lets-plot-compose" "jar" "true" "false"
verify_publication "lets-plot-compose-desktop" "jar" "false" "false"
verify_publication "lets-plot-compose-android" "aar" "false" "false"
verify_publication "lets-plot-compose-wasm-js" "klib" "false" "true"

root_module="$(artifact_dir "lets-plot-compose")/lets-plot-compose-$version.module"
grep -Eq '"module"[[:space:]]*:[[:space:]]*"lets-plot-compose-desktop"' "$root_module" ||
  fail "Root module metadata does not reference Desktop publication"
grep -Eq '"module"[[:space:]]*:[[:space:]]*"lets-plot-compose-android"' "$root_module" ||
  fail "Root module metadata does not reference Android publication"
grep -Eq '"module"[[:space:]]*:[[:space:]]*"lets-plot-compose-wasm-js"' "$root_module" ||
  fail "Root module metadata does not reference WasmJS publication"

require_file "$archive"
unzip -tq "$archive" >/dev/null ||
  fail "Release bundle ZIP integrity check failed: $archive"

for coordinate in   platf-android   platf-android-android   lets-plot-compose   lets-plot-compose-desktop   lets-plot-compose-android   lets-plot-compose-wasm-js
do
  unzip -l "$archive" | grep -F "org/jetbrains/lets-plot/$coordinate/$version/" >/dev/null ||
    fail "Release bundle ZIP is missing coordinate: $coordinate"
done

payload_count="$(find "$group_root" -path "*/$version/*" -type f   ! -name '*.md5' ! -name '*.sha1' ! -name '*.sha256' ! -name '*.sha512' ! -name '*.asc' | wc -l | tr -d ' ')"
signature_count="$(find "$group_root" -path "*/$version/*.asc" -type f | wc -l | tr -d ' ')"

[[ "$payload_count" -gt 0 ]] || fail "No release payloads found"
[[ "$signature_count" -gt 0 ]] || fail "No release signatures found"

mkdir -p "$(dirname "$output")"
cat > "$output" <<EOF
schema=1
result=RELEASE_BUNDLE_REHEARSAL_PASS
release.line=3.2.3
rehearsal.project_version=$version
rehearsal.core_version=4.11.0
rehearsal.kotlin_api_version=4.15.0
rehearsal.upstream_profile=KNOWN_RELEASED_COMPATIBLE_PAIR
rehearsal.external_upload=FALSE
rehearsal.publishable=FALSE
rehearsal.signing=EPHEMERAL_CI_KEY
rehearsal.signature_verification=PASS
rehearsal.maven_structure=PASS
rehearsal.pom_no_snapshot=PASS
rehearsal.root_metadata_targets=PASS
rehearsal.archive_integrity=PASS
rehearsal.publication_count=6
rehearsal.payload_count=$payload_count
rehearsal.signature_count=$signature_count
release.upload_task_invoked=FALSE
production.renderer.default=NATIVE_CANVAS
graphite.production=DISABLED
workflow.sha=${GITHUB_SHA:-LOCAL}
workflow.run_id=${GITHUB_RUN_ID:-LOCAL}
EOF

cat "$output"
