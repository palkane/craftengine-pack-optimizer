#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if (( $# != 0 )); then
  printf 'Usage: bash build.sh\n' >&2
  exit 2
fi
cp="${CEPO_CLASSPATH:-$PWD/libs/*}"
version=$(sed -n 's/^version: //p' src/main/resources/plugin.yml)
rm -rf build/classes
mkdir -p build/classes build/dist
printf '%s' "$cp" > build/classpath.txt
find src/main/java -name '*.java' -print > build/sources.txt
javac --release 25 -cp "$cp" -d build/classes @build/sources.txt
jar --create --file "build/dist/CraftEnginePackOptimizer-$version.jar" \
  -C build/classes . -C src/main/resources . -C . LICENSE
