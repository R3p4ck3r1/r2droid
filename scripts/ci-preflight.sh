#!/usr/bin/env bash
set -euo pipefail

echo "== R2Droid CI preflight =="
command -v git >/dev/null || { echo "git is required"; exit 1; }
command -v java >/dev/null || { echo "Java is required"; exit 1; }

java -version
echo
if [[ -x ./gradlew ]]; then
  ./gradlew --version
else
  echo "gradlew is missing or not executable."
  echo "Run: chmod +x gradlew"
  exit 1
fi

echo
echo "Preflight OK."
