#!/usr/bin/env bash
# Small project-owned Maven launcher, not a generated Apache Maven Wrapper.
# Uses installed Maven, otherwise bootstraps a checksum-verified distribution into .tools/.
set -euo pipefail
project_dir="$(cd "$(dirname "$0")" && pwd)"
if command -v mvn >/dev/null 2>&1; then exec mvn "$@"; fi
version=3.9.11
dist="apache-maven-$version-bin.tar.gz"
base="https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$version"
cache="$project_dir/.tools"
launcher="$cache/apache-maven-$version/bin/mvn"
if [[ ! -x "$launcher" ]]; then
  mkdir -p "$cache"
  curl --fail --location --connect-timeout 10 --max-time 120 "$base/$dist" -o "$cache/$dist"
  curl --fail --location --connect-timeout 10 --max-time 30 "$base/$dist.sha512" -o "$cache/$dist.sha512"
  expected="$(awk '{print $1}' "$cache/$dist.sha512")"
  if command -v sha512sum >/dev/null 2>&1; then
    actual="$(sha512sum "$cache/$dist" | awk '{print $1}')"
  else
    actual="$(shasum -a 512 "$cache/$dist" | awk '{print $1}')"
  fi
  [[ "$expected" =~ ^[a-fA-F0-9]{128}$ && "$actual" == "$expected" ]] || { echo "Maven checksum mismatch" >&2; exit 1; }
  tar -xzf "$cache/$dist" -C "$cache"
fi
exec "$launcher" "$@"
