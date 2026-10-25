#!/usr/bin/env bash
# Installs or updates the KiteConfig plugin module in a Kotlin Toolchain project.
# The Kotlin Toolchain loads plugins only from modules inside the project, so the
# module lives in ./kiteconfig and gets the KiteConfig jar from the Gradle Plugin Portal.
#
# Run it in the folder that holds project.yaml:
#   install-kiteconfig.sh            the latest release
#   install-kiteconfig.sh 3.0.0      one release
#
# KITECONFIG_PLUGIN_DIR=<folder> installs that plugin module folder instead of downloading
# the release. CI uses it to check the plugin module of a checkout.
set -euo pipefail

repo="https://github.com/yuroyami/KiteConfig"

fail() {
  echo "$1" >&2
  exit 1
}

[ -f project.yaml ] || fail "Run this script in the folder that holds project.yaml."

# Replace only a folder that this script installed before.
if [ -e kiteconfig ] && ! grep -qs "jvm/amper-plugin" kiteconfig/module.yaml; then
  fail "./kiteconfig exists and is not the KiteConfig plugin module. Move it, then run the script again."
fi

version="${1:-}"
if [ -z "$version" ]; then
  latest="$(curl -fsSLI -o /dev/null -w '%{url_effective}' "$repo/releases/latest")" ||
    fail "Could not find the latest KiteConfig release."
  version="${latest##*/}"
fi
version="${version#v}"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$ ]] ||
  fail "\"$version\" is not a KiteConfig version, such as 3.0.0."

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
module="$tmp/kiteconfig"

if [ -n "${KITECONFIG_PLUGIN_DIR:-}" ]; then
  cp -R "$KITECONFIG_PLUGIN_DIR" "$module"
else
  curl -fsSL "$repo/archive/refs/tags/v$version.tar.gz" |
    tar -xz -C "$tmp" "KiteConfig-$version/toolchain/kiteconfig" 2>/dev/null ||
    fail "Could not download the plugin module of KiteConfig $version. It needs KiteConfig 3.0.0 or newer."
  mv "$tmp/KiteConfig-$version/toolchain/kiteconfig" "$module"
fi
rm -rf "$module/build" "$module/test" "$module/testResources"

# The repository copy builds against a local dev jar. Point it at the released jar.
awk -v version="$version" '
  BEGIN {
    print "# Installed by install-kiteconfig.sh from KiteConfig " version "."
    print "# Run the script again to update this folder. Do not edit the files in it."
    print ""
  }
  /^# / { next }
  $0 == "  - mavenLocal" {
    print "  - id: gradlePluginPortal"
    print "    url: https://plugins.gradle.org/m2"
    next
  }
  $0 == "  - io.github.yuroyami:kiteconfig:0.0.0-dev" {
    print "  - io.github.yuroyami:kiteconfig:" version
    next
  }
  { print }
' "$module/module.yaml" > "$tmp/module.yaml"

if grep -qE "mavenLocal|0\.0\.0-dev" "$tmp/module.yaml" ||
  ! grep -qxF "  - io.github.yuroyami:kiteconfig:$version" "$tmp/module.yaml" ||
  ! grep -qxF "    url: https://plugins.gradle.org/m2" "$tmp/module.yaml"; then
  fail "The plugin module of KiteConfig $version has a module.yaml that this script does not know. Nothing was changed."
fi
mv "$tmp/module.yaml" "$module/module.yaml"

rm -rf kiteconfig
cp -R "$module" kiteconfig
echo "Installed the plugin module of KiteConfig $version in ./kiteconfig."

# project.yaml must list the module under both modules and plugins.
if ! awk '
  /^[^[:space:]#-][^:]*:/ { section = $1 }
  section == "modules:" && /^[[:space:]]*-[[:space:]]*(\.\/)?kiteconfig[[:space:]]*$/ { listed = 1 }
  section == "plugins:" && /^[[:space:]]*-[[:space:]]*\.\/kiteconfig[[:space:]]*$/ { registered = 1 }
  END { exit !(listed && registered) }
' project.yaml; then
  cat <<'HINT'

Add the module to project.yaml, under both modules and plugins:

modules:
  - kiteconfig

plugins:
  - ./kiteconfig
HINT
fi
