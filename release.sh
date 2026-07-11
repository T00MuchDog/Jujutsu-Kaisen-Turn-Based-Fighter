#!/usr/bin/env bash
#
# release.sh — one-command local release for the CURRENT operating system.
#
# Usage:
#   ./release.sh              # build using the POM <revision> version
#   ./release.sh 1.2.0        # build version 1.2.0 (overrides POM value)
#   ./release.sh 1.2.0 fast   # as above, but skip tests (SKIP_TESTS=1)
#
# This is a thin convenience wrapper around packaging/package.sh, which does
# the real work (Maven build -> jlink runtime -> jpackage installer). The
# version you pass here is the single source of truth for that build: it sets
# the JAR name, the installer's internal version, and the output filename.
#
# To produce BOTH macOS and Windows installers automatically and attach them
# to a GitHub Release, just push a tag like v1.2.0 — see RELEASE.md and
# .github/workflows/release.yml. You only need this script for a local build
# of the OS you're currently on.
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$REPO_ROOT"

VERSION="${1:-}"
if [ -n "$VERSION" ]; then
    echo "==> Local release build for version $VERSION"
else
    echo "==> Local release build (version from POM <revision>)"
fi

if [ "${2:-}" = "fast" ]; then
    export SKIP_TESTS=1
    echo "==> SKIP_TESTS=1 (tests will be skipped)"
fi

exec "$REPO_ROOT/packaging/package.sh" "$VERSION"
