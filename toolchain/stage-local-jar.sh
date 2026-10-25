#!/usr/bin/env bash
# Builds the KiteConfig plugin jar from this checkout as version 0.0.0-dev and
# installs it into ~/.m2, where toolchain/kiteconfig/module.yaml resolves it.
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_root"
./gradlew stageUnsignedReleaseCandidate \
  -PkiteConfig.version=0.0.0-dev -PkiteConfig.releaseTag=v0.0.0-dev \
  --no-configuration-cache --quiet
target="$HOME/.m2/repository/io/github/yuroyami/kiteconfig"
mkdir -p "$target"
rm -rf "$target/0.0.0-dev"
cp -R build/unsigned-release-candidate/io/github/yuroyami/kiteconfig/0.0.0-dev "$target/"
echo "Installed io.github.yuroyami:kiteconfig:0.0.0-dev into $target"
