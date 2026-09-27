#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
if command -v jenv >/dev/null 2>&1; then
  export JAVA_HOME="$(jenv prefix)"
  export PATH="$JAVA_HOME/bin:$PATH"
fi
mvn -B -ntp verify
exec java -jar target/eu-codelist-normalizer-all.jar "$@"
