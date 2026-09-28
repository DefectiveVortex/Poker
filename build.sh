#!/usr/bin/env bash
# Build Poker inside the cached JDK 25 image, capped so it can't starve the live Minecraft server.
# Serialised across all worktrees by /home/vortex/Poker-ops/build.lock (the Pi can afford one build at a time).
# Usage: ./build.sh [maven args...]   (default: -q -B package, which also runs the tests)
# Run it from the repo root or any worktree; it builds the tree it lives in.
# Refuses while any *-test server container is up or MemAvailable < 1200 MB (checked again after the lock is taken, so a
# queued build never starts unattended into a busy Pi). FORCE=1 overrides.
set -euo pipefail
REPO="$(cd "$(dirname "$0")" && pwd)"
OPS=/home/vortex/Poker-ops
IMAGE=ghcr.io/pterodactyl/yolks:java_25
ARGS=("$@")
[ ${#ARGS[@]} -eq 0 ] && ARGS=(-q -B package)

guard() {
  [ "${FORCE:-0}" = 1 ] && return 0
  local servers avail_mb
  servers=$(docker ps --format '{{.Names}}' | grep -E -- '-test$' || true)
  if [ -n "$servers" ]; then
    echo "build.sh: test server running ($servers); stop it before building (a build + a server don't fit). FORCE=1 to override." >&2
    exit 1
  fi
  avail_mb=$(awk '/MemAvailable/ {print int($2/1024)}' /proc/meminfo)
  if [ "$avail_mb" -lt 1200 ]; then
    echo "build.sh: only ${avail_mb} MB available (need 1200); not building. FORCE=1 to override." >&2
    exit 1
  fi
}

if [ "${POKER_BUILD_LOCKED:-0}" != 1 ]; then
  guard  # fail fast before queueing
  POKER_BUILD_LOCKED=1 exec flock "$OPS/build.lock" "$0" "$@"
fi
guard  # again: the Pi may have changed while we waited for the lock

exec docker run --rm \
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
