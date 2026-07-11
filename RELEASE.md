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

This is the normal path — it builds **all three** targets automatically and
attaches them to a GitHub Release.

### One-time setup
- The workflow (`.github/workflows/release.yml`) needs no configuration to run
  **unsigned**. Just push a tag.
- (Optional, later) To enable signing, add repository secrets — see
  [§7 Code signing & notarization](#7-code-signing--notarization).

### Cutting the release

```bash
# 1. Make sure your changes are committed and tests pass.
mvn -Drevision=1.2.0 -pl core,graphics -am clean verify

# 2. Bump the version (edit the one value).
#    pom.xml: <revision>1.2.0</revision>
git commit -am "Release 1.2.0"

# 3. Create and push the tag. The tag name MUST be v<version>.
git tag v1.2.0
git push origin v1.2.0
```

Pushing the tag triggers `.github/workflows/release.yml`, which:

- Runs on three runners in parallel: `macos-latest` (arm64), `macos-15-intel`
  (x64), `windows-latest` (x64).
- Each checks out the code, sets up JDK 17 (Temurin), and runs
  `packaging/package.sh` with the tag's version.
- A final `release` job downloads all three artifacts and creates a GitHub
  Release named `<version>` with auto-generated notes, attaching the `.dmg`
  and `.msi` files.

You can watch progress under **Actions** in the GitHub UI. When it finishes,
the release appears under **Releases** with the installers attached.

> You can also trigger a build manually from the Actions tab
> (**Run workflow** → enter a version) without creating a tag. This is useful
> for testing the pipeline; it will not produce a public release until you
> push a real tag.

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
launch existing files are **never** overwritten, so installing a newer version
preserves player data. To verify an upgrade manually:

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

## 7. Code signing & notarization

> Builds are **unsigned by default**. Unsigned apps work, but:
> - **macOS:** Gatekeeper blocks first launch. Users must right-click →
>   **Open** (or `xattr -d com.apple.quarantine /path/to.app`) the first time.
> - **Windows:** SmartScreen shows a "Windows protected your PC" warning on
>   first run; users click **More info → Run anyway**.

To enable signing later, add these **repository secrets** (GitHub → Settings →
Secrets and variables → Actions). The workflow activates signing automatically
when they are present — **no code changes are required**.

### macOS (Apple Developer ID + notarization)
| Secret                       | Value                                              |
|------------------------------|----------------------------------------------------|
| `APPLE_DEVELOPER_ID`         | "Developer ID Application: Your Name (TEAMID)"    |
| `APPLE_APPLE_ID`             | your Apple ID email                                |
| `APPLE_APP_SPECIFIC_PASSWORD`| an app-specific password (appleid.apple.com)       |
| `APPLE_KEYCHAIN`             | keychain name holding the signing identity         |

The signing identity must be installed on the runner; for CI this usually
means importing a `.p12` into a temporary keychain in a preceding step
(add that step to the workflow when you have the certificate). Notarization
(stapling the ticket) is performed after signing.

### Windows (code-signing certificate)
| Secret              | Value                                        |
|---------------------|----------------------------------------------|
| `WIN_CERT_P12`      | base64-encoded `.pfx`/`.p12` certificate     |
| `WIN_CERT_PASSWORD` | the certificate's export password            |

The MSI is then signed with `signtool` in a post-build step.

Until these secrets exist, the workflow produces ad-hoc/unsigned builds and
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
