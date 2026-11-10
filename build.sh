#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
api='/home/server/main/libraries/cn/dreeam/leaf/leaf-api/26.3.build.2-alpha/leaf-api-26.3.build.2-alpha.jar'
engine='/home/server/shared-plugin-sync/plugins/[C][材质引擎]craft-engine-paper-plugin-26.9.2.jar'
gson='/home/server/main/libraries/com/google/code/gson/gson/2.13.2/gson-2.13.2.jar'
adventure='/home/server/main/libraries/net/kyori/adventure-api/5.2.0/adventure-api-5.2.0.jar'
key='/home/server/main/libraries/net/kyori/adventure-key/5.2.0/adventure-key-5.2.0.jar'
annotations='/home/server/main/libraries/org/jetbrains/annotations/26.0.2/annotations-26.0.2.jar'
packets='/home/server/shared-plugin-sync/plugins/[P][前置-发包]packetevents-spigot-2.14.0.jar'
bungee='/home/server/main/libraries/net/md-5/bungeecord-chat/1.21-R0.2-deprecated+build.21/bungeecord-chat-1.21-R0.2-deprecated+build.21.jar'
cp="$api:$engine:$gson:$adventure:$key:$annotations:$packets:$bungee"
cp="$cp:/home/server/main/libraries/com/google/guava/guava/33.6.0-jre/guava-33.6.0-jre.jar"
for module in netty-buffer netty-common netty-transport netty-resolver; do
  cp="$cp:/home/server/main/libraries/io/netty/$module/4.2.17.Final/$module-4.2.17.Final.jar"
done
mkdir -p build/classes build/dist
printf '%s' "$cp" > build/classpath.txt
javac --release 25 -cp "$cp" -d build/classes $(find src/main/java -name '*.java')
jar --create --file build/dist/CraftEnginePackOptimizer-1.5.2.jar -C build/classes . -C src/main/resources .
if [[ "${1:-}" == test ]]; then
  cp="$cp:/home/server/main/libraries/it/unimi/dsi/fastutil/8.5.18/fastutil-8.5.18.jar"
  mkdir -p build/test-classes
  javac --release 25 -cp "build/classes:$cp" -d build/test-classes $(find src/test/java -name '*.java')
  java -cp "build/classes:build/test-classes:$cp" net.sicilia.pack.PackOptimizerTest
  java -cp "build/classes:build/test-classes:$cp" net.sicilia.pack.NamespaceShardsTest
  java -cp "build/classes:build/test-classes:$cp" net.sicilia.pack.FontProviderOptimizerTest
  java -cp "build/classes:build/test-classes:$cp" net.sicilia.pack.NamespaceMergerTest
  java -cp "build/classes:build/test-classes:$cp" net.sicilia.pack.NamespaceCompatibilityTest
  java -cp "build/classes:build/test-classes:$cp" net.sicilia.pack.StrictNamespacePackTest
  java -cp "build/classes:build/test-classes:$cp" net.sicilia.pack.ClientResourceMappingsTest
  java -cp "build/classes:build/test-classes:$cp" net.sicilia.pack.ClientResourceProtocolTest "${2:-}"
  java -cp "build/classes:build/test-classes:$cp" net.sicilia.pack.RuntimeHostBridgeTest
  python3 -m unittest -v test_verify_strict_pack.py
fi
