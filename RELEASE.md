# Release Process

This document describes how to cut a release of **Jujutsu Kaisen Fighter** —
from bumping the version to publishing signed (or unsigned) installers for
macOS and Windows, including how to test an upgrade and how to roll back.

The ordinary release process is intentionally short:

1. Commit your finished game changes.
2. Bump the version in **one place**.
3. Run the tests.
4. Create and push a Git tag `v<version>`.
5. GitHub Actions builds all platform packages and publishes them.

Everything below is detail, edge cases, and local-build instructions.

---

## 1. Where the version lives

There is exactly **one** authoritative version: the `<revision>` property in the
root `pom.xml`:

```xml
<properties>
    <revision>1.0.0</revision>
    ...
</properties>
```

This single value propagates automatically to:

| Consumer                              | How it gets the version            |
|---------------------------------------|------------------------------------|
| `core` / `graphics` module JARs       | Maven `${revision}` (CI-friendly)  |
| The shaded fat JAR name               | `graphics-<revision>.jar`          |
| `jpackage --app-version`              | passed by `package.sh`             |
| macOS `CFBundleShortVersionString`    | from `--app-version`               |
| Windows MSI `ProductVersion`          | from `--app-version`               |
| Distribution filenames                | `JujutsuKaisenFighter-<ver>-...`   |
| The GitHub Release name/tag           | derived from the pushed tag        |

You can also override it per-invocation without editing the file by passing
`-Drevision=1.2.0` to Maven, or by passing the version to `release.sh` /
`package.sh`. **Never** hand-edit a child POM's `<version>` — it is
`${revision}` and is resolved from the parent.

---

## 2. How to create a release locally

`release.sh` builds the installer for the **current** operating system only
(jpackage produces platform-specific packages; you must build each OS on its
own OS). From the repo root:

```bash
./release.sh 1.2.0          # build version 1.2.0, run tests first
./release.sh 1.2.0 fast     # same, but skip tests (SKIP_TESTS=1)
./release.sh                # use the version currently in pom.xml
```

`release.sh` is a thin wrapper around `packaging/package.sh`, which:

1. Runs `mvn -Drevision=<ver> -pl core,graphics -am clean verify`.
2. Detects required JDK modules with `jdeps`.
3. Builds a minimal bundled Java runtime with `jlink`.
4. Generates `.ico` / `.icns` icons from `packaging/icon.png`.
5. Runs `jpackage` to produce a `.dmg` (macOS) or `.msi` (Windows).
6. Copies the result to `dist/` with a clear name.

### Prerequisites (local build)

- **JDK 17+** with `jpackage`, `jlink`, `jdeps` on `PATH` (JDK 17 is the
  build target; a full JDK, not just a JRE).
- **Maven 3.6+**.
- **Python 3** (stdlib only — used for icon generation).
- **macOS only:** the Xcode command-line tools (`iconutil`, `sips`) — install
  with `xcode-select --install`.
- **Windows only:** [WiX Toolset 3.x](https://wixtoolset.org/) (required by
  `jpackage` to build `.msi`). On a GitHub Actions `windows-latest` runner this
  is preinstalled.

### Where generated files appear

| Path                        | Contents                                  |
|-----------------------------|-------------------------------------------|
| `graphics/target/graphics-<ver>.jar` | The shaded fat JAR (intermediate) |
| `build/`                    | jlink runtime, icons, jpackage work dirs  |
| `dist/`                     | **Final release artifacts**               |

Final artifacts are named:

- `JujutsuKaisenFighter-<ver>-macos-arm64.dmg`  (Apple Silicon)
- `JujutsuKaisenFighter-<ver>-macos-x64.dmg`    (Intel)
- `JujutsuKaisenFighter-<ver>-windows-x64.msi`

`build/` and `dist/` are gitignored — they are build outputs, not sources.

---

## 3. How to publish a release through GitHub

This is the normal path — it builds **all four** artifacts automatically and
attaches them to a GitHub Release.

### One-time setup
- The workflow (`.github/workflows/release.yml`) needs no configuration to run
  **unsigned**. Just push a tag.
- (Optional, later) To enable signing, add repository secrets and complete the
  signing steps — see [§7 Verifying downloads & code signing](#7-verifying-downloads--code-signing).

### Cutting the release

```bash
# 1. Make sure your changes are committed and tests pass.
mvn -Drevision=1.2.0 clean verify

# 2. Bump the version (edit the one value).
#    pom.xml: <revision>1.2.0</revision>
git commit -am "Release 1.2.0"

# 3. Create and push the tag. The tag name MUST be v<version>.
git tag v1.2.0
git push origin v1.2.0
```

Pushing the tag triggers `.github/workflows/release.yml`, which:

- Builds the server JAR on Ubuntu while three packaging runners build
  `macos-latest` (arm64), `macos-15-intel` (x64), and `windows-latest` (x64).
- Each checks out the code and sets up JDK 17 (Temurin); desktop jobs run
  `packaging/package.sh` with the tag's version.
- A final `release` job (tag pushes only) downloads all four artifacts,
  generates a `SHA256SUMS` checksum file, and creates a GitHub Release named
  `<version>` with auto-generated notes, attaching the server JAR, `.dmg`, and
  `.msi` files plus the checksums.

You can watch progress under **Actions** in the GitHub UI. When it finishes,
the release appears under **Releases** with the installers and `SHA256SUMS`
attached.

> You can also trigger a build manually from the Actions tab (**Run workflow**
> → enter a version) without creating a tag. This is useful for testing the
> pipeline. **Manual runs are build-only**: they build the installers and
> attach them to the Actions run for download, but the release job is skipped
> — no GitHub Release is published until you push a real `v*` tag. The
> version you enter must be strict [SemVer](https://semver.org)
> (`MAJOR.MINOR.PATCH`, e.g. `1.2.0`); anything else fails the run.

---

## 4. How to increment the version

Use [Semantic Versioning](https://semver.org/): `MAJOR.MINOR.PATCH`.

- `1.0.0 → 1.0.1`: bugfix only (compatible).
- `1.0.0 → 1.1.0`: new features (compatible).
- `1.0.0 → 2.0.0`: breaking changes (e.g. new save format that old builds
  can't read).

The version **must not** contain `-SNAPSHOT` for a release — `package.sh`
rejects it because Windows MSI versioning disallows it.

Steps: edit `<revision>` in `pom.xml`, commit, tag `v<version>`, push the tag.

---

## 5. How to test an update over an older installation

Player data (edited characters/moves/abilities/techniques, and crash logs)
lives **outside** the app, in a per-user directory:

| OS      | Location                                                |
|---------|---------------------------------------------------------|
| macOS   | `~/Library/Application Support/JujutsuKaisenFighter/`   |
| Windows | `%APPDATA%\JujutsuKaisenFighter\`                       |
| Linux   | `~/.jujutsukaisenfighter/` (or `$XDG_DATA_HOME`)        |

On first launch the bundled defaults are copied there; on every subsequent
launch, bundled moves, abilities, and characters with a matching name are
replaced by the definitions shipped in the installed release. Player-created
records without a bundled match remain. Bundled technique-tree metadata is also
synchronized so authored release layouts carry forward. To verify an upgrade
manually:

1. Install and run version N (e.g. `1.0.0`). Make a change in an editor and
   save it — or just note the existing data.
2. Install version N+1 over it (drag the new `.app` over the old one on macOS,
   or run the new `.msi` on Windows — same install location).
3. Launch version N+1 and confirm your change/data is still present.

This exact scenario is verified during development (see the "tests performed"
section of the packaging change). On Windows, the MSI uses a fixed upgrade
UUID, so a newer MSI upgrades the older one in place rather than installing
side-by-side.

> If you ever need a truly fresh state (e.g. to re-test first-run seeding),
> delete the per-user directory above. The app will re-seed it from the
> bundled defaults on next launch.

---

## 6. How to roll back a failed release

If a release is broken:

1. **Delete the tag and release** (if it was already published):
   ```bash
   gh release delete v1.2.0 --yes
   git push origin :refs/tags/v1.2.0
   git tag -d v1.2.0
   ```
   (Or use the GitHub UI: Releases → … → Delete, then delete the tag.)
2. **Revert the version bump** in `pom.xml` back to the last good version (or
   fix the issue and prepare a new version, e.g. `1.2.1`).
3. **Cut a new release** by pushing a new tag once the fix is in.

Installed apps are not auto-updated by a rollback — users who already
installed the bad version should uninstall it and install the good one. Their
per-user data is unaffected (it lives outside the app).

---

## 7. Verifying downloads & code signing

### 7a. Verifying a download (always available)

Every release includes a `SHA256SUMS` file listing the SHA-256 hash of each
installer. **Verify your download before installing** — this confirms the file
wasn't corrupted or tampered with in transit. Download both the installer and
`SHA256SUMS` from the release, then from the directory containing both:

**macOS / Linux:**

```bash
# Check just the file you downloaded:
shasum -a 256 JujutsuKaisenFighter-1.2.0-macos-arm64.dmg

# Or verify against the published list (expects all files present):
shasum -a 256 -c SHA256SUMS
```

**Windows (Command Prompt):**

```cmd
certutil -hashfile JujutsuKaisenFighter-1.2.0-windows-x64.msi SHA256
```

Compare the printed hash to the matching line in `SHA256SUMS`. They must match
exactly.

> **Why checksums and not a signature?** A SHA-256 hash proves integrity (the
> file is unchanged) but not authenticity (it doesn't prove *who* made it),
> because both the installer and the checksums travel through the same GitHub
> release. Full authenticity requires code signing (below). Until signing is
> set up, treat checksums as the primary verification mechanism.

### 7b. Code signing status (unsigned by default)

> Builds are **currently unsigned**. Unsigned apps work, but:
> - **macOS:** Gatekeeper blocks first launch. Users must right-click →
>   **Open** (or `xattr -d com.apple.quarantine /path/to.app`) the first time.
> - **Windows:** SmartScreen shows a "Windows protected your PC" warning on
>   first run; users click **More info → Run anyway**.

The scaffolding exists to enable signing later without code changes, but it is
**not yet complete**:

- **macOS:** `jpackage --mac-sign` is wired in `packaging/package.sh` and
  activates when the `APPLE_*` secrets are present, **but notarization and
  stapling are not implemented yet** — signing alone still trips Gatekeeper
  until the build is notarized.
- **Windows:** the `WIN_CERT_*` secrets are accepted for forward
  compatibility, **but no `signtool` step runs yet** — the MSI is always
  unsigned today.

To complete signing later, add these **repository secrets** (GitHub → Settings
→ Secrets and variables → Actions), and then implement the missing notarization
(macOS) and `signtool` (Windows) steps:

#### macOS (Apple Developer ID + notarization)
| Secret                       | Value                                              |
|------------------------------|----------------------------------------------------|
| `APPLE_DEVELOPER_ID`         | "Developer ID Application: Your Name (TEAMID)"    |
| `APPLE_APPLE_ID`             | your Apple ID email                                |
| `APPLE_APP_SPECIFIC_PASSWORD`| an app-specific password (appleid.apple.com)       |
| `APPLE_KEYCHAIN`             | keychain name holding the signing identity         |

The signing identity must be installed on the runner; for CI this usually
means importing a `.p12` into a temporary keychain in a preceding step, then
running `xcrun notarytool submit` + `xcrun stapler staple` on the `.app`
inside the `.dmg` after `jpackage` completes.

#### Windows (code-signing certificate)
| Secret              | Value                                        |
|---------------------|----------------------------------------------|
| `WIN_CERT_P12`      | base64-encoded `.pfx`/`.p12` certificate     |
| `WIN_CERT_PASSWORD` | the certificate's export password            |

This requires decoding `WIN_CERT_P12` to a `.pfx` on the runner and signing the
MSI with `signtool sign /f cert.pfx /p <password> /fd sha256 ...` after
`jpackage` produces it. Until then, the workflow produces unsigned builds and
the warnings above apply.

---

## 8. How rebranding works

To change the app name, icon, or bundle id, edit **only** these (no per-OS
packaging config for ordinary updates):

- **Icon:** replace `packaging/icon.png` (1024×1024 PNG). Re-run the build;
  `make-icons.py` regenerates `.ico`/`.icns` automatically.
- **App name / bundle id / vendor:** the constants at the top of
  `packaging/package.sh` (`APP_NAME`, `APP_IDENTIFIER`, `VENDOR`). Also update
  `APP_NAME` in `core/src/main/java/com/jjktbf/AppPaths.java` (window title +
  data-folder name) to match.

Changing the bundle id or the Windows upgrade UUID will break upgrade-on-
install for existing users (their data is still safe in the per-user dir).
Only change those deliberately, for a major version.
