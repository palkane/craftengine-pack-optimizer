#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
cp="${CEPO_CLASSPATH:-$PWD/libs/*}"
version=$(sed -n 's/^version: //p' src/main/resources/plugin.yml)
rm -rf build/classes build/test-classes
mkdir -p build/classes build/dist
printf '%s' "$cp" > build/classpath.txt
find src/main/java -name '*.java' -print > build/sources.txt
javac --release 25 -cp "$cp" -d build/classes @build/sources.txt
jar --create --file "build/dist/CraftEnginePackOptimizer-$version.jar" \
  -C build/classes . -C src/main/resources . -C . LICENSE
if [[ "${1:-}" == test ]]; then
  mkdir -p build/test-classes
  find src/test/java -name '*.java' -print > build/test-sources.txt
  javac --release 25 -cp "build/classes:$cp" -d build/test-classes @build/test-sources.txt
  java -cp "build/classes:build/test-classes:$cp" net.sicilia.pack.OfflineCliTest
  java -cp "build/classes:build/test-classes:$cp" net.sicilia.pack.PackOptimizerTest
  java -cp "build/classes:build/test-classes:$cp" net.sicilia.pack.NamespaceShardsTest
  java -cp "build/classes:build/test-classes:$cp" net.sicilia.pack.FontProviderOptimizerTest
  java -cp "build/classes:build/test-classes:$cp" net.sicilia.pack.NamespaceMergerTest
  java -cp "build/classes:build/test-classes:$cp" net.sicilia.pack.NamespaceCompatibilityTest
  java -cp "build/classes:build/test-classes:$cp" net.sicilia.pack.StrictNamespacePackTest
  java -cp "build/classes:build/test-classes:$cp" net.sicilia.pack.ProtectedNamespacePackTest
  java -cp "build/classes:build/test-classes:$cp" net.sicilia.pack.ClientResourceMappingsTest
  java -cp "build/classes:build/test-classes:$cp" net.sicilia.pack.ClientResourceProtocolTest "${2:-}"
  java -cp "build/classes:build/test-classes:$cp" net.sicilia.pack.ShowDialogPacketTest "${3:-}"
  java -cp "build/classes:build/test-classes:$cp" net.sicilia.pack.RuntimeHostBridgeTest
  python3 -m unittest -v test_verify_strict_pack.py
fi
