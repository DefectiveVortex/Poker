#!/usr/bin/env bash
# Build Poker inside the cached JDK 25 image, capped so it can't starve the live Minecraft server.
# Serialised across all worktrees by /home/vortex/Poker-ops/build.lock (the Pi can afford one build at a time).
# Usage: ./build.sh [maven args...]   (default: -q -B package, which also runs the tests)
# Run it from the repo root or any worktree; it builds the tree it lives in.
set -euo pipefail
REPO="$(cd "$(dirname "$0")" && pwd)"
OPS=/home/vortex/Poker-ops
IMAGE=ghcr.io/pterodactyl/yolks:java_25
ARGS=("$@")
[ ${#ARGS[@]} -eq 0 ] && ARGS=(-q -B package)

exec flock "$OPS/build.lock" docker run --rm \
  --name "poker-build-$(basename "$REPO")" \
  --user "$(id -u):$(id -g)" \
  --cpus 2 --memory 1g --memory-swap 1g \
  --dns 172.17.0.1 \
  -e HOME=/tmp \
  -e MAVEN_OPTS="-Xmx448m -XX:+UseSerialGC -XX:TieredStopAtLevel=1" \
  -v "$REPO":/work -w /work \
  -v "$OPS/maven":/opt/maven:ro \
  -v "$OPS/m2":/m2 \
  --entrypoint /opt/maven/bin/mvn \
  "$IMAGE" \
  -Dmaven.repo.local=/m2/repository "${ARGS[@]}"
