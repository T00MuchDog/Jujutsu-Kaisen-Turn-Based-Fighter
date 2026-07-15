#!/usr/bin/env bash
#
# package.sh — build a self-contained, installable release package for the
# CURRENT operating system using Maven + jlink + jpackage.
#
# Run it on each target OS to produce that OS's package:
#   macOS   -> a .dmg (containing a .app)        (-XstartOnFirstThread added for macOS only)
#   Windows -> an .msi installer
#   Linux   -> a .deb / .rpm (best-effort; not an official target)
#
# Output is renamed to a clear, version/arch-stamped filename in dist/:
#   JujutsuKaisenFighter-<ver>-macos-arm64.dmg
#   JujutsuKaisenFighter-<ver>-macos-x64.dmg
#   JujutsuKaisenFighter-<ver>-windows-x64.msi
#
# Usage:
#   packaging/package.sh [version]          # version defaults to the POM <revision>
#
# Environment overrides:
#   REVISION   the version to build (e.g. 1.2.0)
#   SKIP_TESTS set to 1 to run `package` instead of `verify` (skips tests)
#
set -euo pipefail

# Resolve repo root (this script lives in packaging/).
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

# --------------------------------------------------------------------------- #
# Configuration — no per-OS edits needed for ordinary version bumps.
# --------------------------------------------------------------------------- #
APP_NAME="Jujutsu Kaisen Fighter"
APP_IDENTIFIER="com.jjktbf.jjkfighter"
VENDOR="JJKTBF"
MAIN_CLASS="com.jjktbf.graphics.GraphicsMain"

# Fixed Windows upgrade UUID so a newer MSI upgrades an older install in place.
# Do NOT change this between releases or Windows will install side-by-side copies.
WIN_UPGRADE_UUID="8f1c4f2a-3b6e-4c9a-9d5a-2e7f0b1a3c4d"

# Version: explicit arg > $REVISION env > POM default.
REVISION="${1:-${REVISION:-}}"

# --------------------------------------------------------------------------- #
# Detect OS + arch.
# --------------------------------------------------------------------------- #
OS_NAME="$(uname -s)"
case "$OS_NAME" in
    Darwin) PLATFORM="macos" ;;
    MINGW*|MSYS*|CYGWIN*) PLATFORM="windows" ;;
    Linux) PLATFORM="linux" ;;
    *) echo "ERROR: unsupported OS '$OS_NAME'" >&2; exit 1 ;;
esac

ARCH_RAW="$(uname -m)"
case "$ARCH_RAW" in
    arm64|aarch64) ARCH="arm64" ;;
    x86_64|amd64)  ARCH="x64" ;;
    *) ARCH="$ARCH_RAW" ;;
esac

if [ "$PLATFORM" = "windows" ] && [ "$ARCH" = "arm64" ]; then
    echo "ERROR: Windows ARM64 packaging is not configured." >&2; exit 1
fi

echo "==> Platform: $PLATFORM ($ARCH)"

# --------------------------------------------------------------------------- #
# Toolchain checks.
# --------------------------------------------------------------------------- #
need() { command -v "$1" >/dev/null 2>&1 || { echo "ERROR: '$1' not found on PATH." >&2; exit 1; }; }
need mvn
need java
need jpackage
need jlink
need jdeps

# JDK 17 is required (jpackage + module path). Warn if older.
JAVA_MAJOR="$(java -version 2>&1 | awk -F[\".] '/version/ {print $2; exit}')"
if [ "${JAVA_MAJOR:-0}" -lt 17 ]; then
    echo "ERROR: JDK 17+ required (found ${JAVA_MAJOR})." >&2; exit 1
fi

# --------------------------------------------------------------------------- #
# Determine the version if not given: read the POM <revision> property.
# --------------------------------------------------------------------------- #
if [ -z "$REVISION" ]; then
    REVISION="$(mvn -q -pl graphics help:evaluate -Dexpression=revision -DforceStdout 2>/dev/null | tr -d '[:space:]')"
fi
if [ -z "$REVISION" ]; then
    echo "ERROR: could not determine version. Pass it: package.sh 1.2.0" >&2; exit 1
fi
# Reject SNAPSHOT versions for installers (jpackage dislikes them on Windows).
case "$REVISION" in
    *-SNAPSHOT) echo "ERROR: version '$REVISION' is a SNAPSHOT. Use a release version like 1.0.0." >&2; exit 1 ;;
esac
echo "==> Version: $REVISION"

# --------------------------------------------------------------------------- #
# Build the shaded fat JAR.
# --------------------------------------------------------------------------- #
BUILD_DIR="$REPO_ROOT/build"
DIST_DIR="$REPO_ROOT/dist"
WORK_DIR="$BUILD_DIR/jpackage-$PLATFORM-$ARCH"
rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR" "$DIST_DIR"

GOAL="verify"
if [ "${SKIP_TESTS:-0}" = "1" ]; then GOAL="package"; fi
echo "==> mvn -Drevision=$REVISION -pl core,graphics -am clean $GOAL"
mvn -Drevision="$REVISION" -pl core,graphics -am clean "$GOAL"

FAT_JAR="$REPO_ROOT/graphics/target/graphics-${REVISION}.jar"
if [ ! -f "$FAT_JAR" ]; then
    echo "ERROR: expected fat JAR not found: $FAT_JAR" >&2; exit 1
fi
echo "==> Fat JAR: $FAT_JAR ($(du -h "$FAT_JAR" | cut -f1))"

# --------------------------------------------------------------------------- #
# Generate icons from the master PNG.
# --------------------------------------------------------------------------- #
echo "==> Generating icons"
python3 "$SCRIPT_DIR/make-icons.py" --png "$SCRIPT_DIR/icon.png" --outdir "$BUILD_DIR/icons" || {
    echo "ERROR: icon generation failed." >&2; exit 1
}

# --------------------------------------------------------------------------- #
# Determine required JDK modules via jdeps, build a minimal runtime via jlink.
# --------------------------------------------------------------------------- #
echo "==> Detecting JDK modules (jdeps)"
MODULES="$(jdeps --multi-release "$JAVA_MAJOR" \
    --print-module-deps \
    --ignore-missing-deps \
    --class-path "$FAT_JAR" \
    "$FAT_JAR" 2>/dev/null || true)"
# LWJGL/libGDX use some modules jdeps sometimes misses; add defensively.
for m in java.desktop java.management jdk.unsupported java.sql; do
    case ",$MODULES," in
        *",$m,"*) : ;;
        *) MODULES="${MODULES:+$MODULES,}$m" ;;
    esac
done
echo "==> jlink modules: $MODULES"

JRE_DIR="$WORK_DIR/jre"
echo "==> jlink -> $JRE_DIR"
jlink --no-header-files --no-man-pages \
    --compress=2 \
    --add-modules "$MODULES" \
    --output "$JRE_DIR"

# --------------------------------------------------------------------------- #
# Assemble jpackage arguments (shared across platforms).
# --------------------------------------------------------------------------- #
JPKG=(jpackage
    --name "$APP_NAME"
    --app-version "$REVISION"
    --vendor "$VENDOR"
    --copyright "© $(date +%Y) $VENDOR"
    --dest "$WORK_DIR"
    --input "$WORK_DIR/input"
    --main-jar "graphics-${REVISION}.jar"
    --main-class "$MAIN_CLASS"
    --runtime-image "$JRE_DIR"
    --verbose
)
mkdir -p "$WORK_DIR/input"
cp "$FAT_JAR" "$WORK_DIR/input/"

# Platform-specific options + package type.
case "$PLATFORM" in
    macos)
        ICON="$BUILD_DIR/icons/icon.icns"
        [ -f "$ICON" ] || { echo "ERROR: missing $ICON" >&2; exit 1; }
        JPKG+=(--type dmg
               --icon "$ICON"
               --mac-package-name "$APP_NAME"
               --mac-package-identifier "$APP_IDENTIFIER"
               # -XstartOnFirstThread is REQUIRED on macOS for LWJGL/libGDX to
               # access Cocoa/OpenGL, but it is a macOS-only flag — on Windows
               # and Linux it is UNRECOGNIZED and makes the JVM exit with
               # "Could not create the Java Virtual Machine" before any window
               # appears. So it must be macOS-only, never global.
               --java-options -XstartOnFirstThread)
        # Code signing (gated on secrets; ad-hoc/unsigned by default).
        # NOTE: jpackage --mac-sign is wired here, but notarization/stapling is
        # NOT yet implemented. Signing only takes effect if you have a Developer
        # ID certificate in the runner's keychain AND the APPLE_* secrets set;
        # otherwise the build is unsigned. See RELEASE.md §7. Until then,
        # releases rely on published SHA-256 checksums (see the release workflow).
        if [ -n "${APPLE_DEV_ID:-}" ] && [ -n "${APPLE_DEV_PASSWORD:-}" ] && [ -n "${APPLE_DEVELOPER_ID:-}" ]; then
            JPKG+=(--mac-sign
                   --mac-signing-key-user-name "$APPLE_DEVELOPER_ID"
                   --mac-signing-keychain "$APPLE_KEYCHAIN")
        fi
        ;;
    windows)
        ICON="$BUILD_DIR/icons/icon.ico"
        [ -f "$ICON" ] || { echo "ERROR: missing $ICON" >&2; exit 1; }
        JPKG+=(--type msi
               --icon "$ICON"
               --win-per-user-install
               --win-shortcut
               --win-menu
               --win-menu-group "$APP_NAME"
               --win-upgrade-uuid "$WIN_UPGRADE_UUID")
        if [ -n "${WIN_CERT_P12:-}" ] && [ -n "${WIN_CERT_PASSWORD:-}" ]; then
            # NOTE: MSI signing is NOT yet implemented. The WIN_CERT_* secrets
            # are accepted for forward compatibility, but no signtool step runs
            # today. Until a signing step is added here, Windows builds are
            # unsigned and rely on published SHA-256 checksums. See RELEASE.md §7.
            : # signing intentionally a no-op until signtool is wired in
        fi
        ;;
    linux)
        JPKG+=(--type deb
               --linux-package-name jjktbf
               --linux-shortcut
               --linux-menu-group Game)
        ;;
esac

# --------------------------------------------------------------------------- #
# Run jpackage.
# --------------------------------------------------------------------------- #
echo "==> jpackage (${JPKG[*]:0:1} ...)"
"${JPKG[@]}"

# --------------------------------------------------------------------------- #
# Locate the produced artifact and rename it to a clear, stamped filename.
# --------------------------------------------------------------------------- #
case "$PLATFORM" in
    macos)
        ARTIFACT="$WORK_DIR/${APP_NAME}-${REVISION}.dmg"
        [ -f "$ARTIFACT" ] || ARTIFACT="$(ls "$WORK_DIR"/*.dmg 2>/dev/null | head -1 || true)"
        OUT="$DIST_DIR/JujutsuKaisenFighter-${REVISION}-${PLATFORM}-${ARCH}.dmg"
        cp "$ARTIFACT" "$OUT"
        ;;
    windows)
        ARTIFACT="$WORK_DIR/${APP_NAME}-${REVISION}.msi"
        [ -f "$ARTIFACT" ] || ARTIFACT="$(ls "$WORK_DIR"/*.msi 2>/dev/null | head -1 || true)"
        OUT="$DIST_DIR/JujutsuKaisenFighter-${REVISION}-${PLATFORM}-${ARCH}.msi"
        cp "$ARTIFACT" "$OUT"
        ;;
    linux)
        ARTIFACT="$(ls "$WORK_DIR"/*.deb 2>/dev/null | head -1 || true)"
        OUT="$DIST_DIR/JujutsuKaisenFighter-${REVISION}-${PLATFORM}-${ARCH}.deb"
        cp "$ARTIFACT" "$OUT"
        ;;
esac

echo
echo "============================================================"
echo "BUILD COMPLETE"
echo "  package: $OUT"
echo "  size:    $(du -h "$OUT" | cut -f1)"
echo "============================================================"
