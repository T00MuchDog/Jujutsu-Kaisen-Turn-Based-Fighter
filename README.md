# Jujutsu Kaisen — Turn Based Fighter

A turn-based combat game built in Java with a multi-module Maven architecture.
Characters, moves, and abilities are all data-driven (JSON) and authored through
the graphical editors inside the LibGDX front-end.

---

## Module Structure

```
JJKTBF/
├── core/      Domain model + combat engine. No I/O, no rendering. All other modules depend on this.
├── graphics/  LibGDX graphics front-end. GraphicsMain is the entry point for windowed play.
│              Hosts the battle UI plus the Scene2D graphical editors (Move/Character/Ability).
└── data/      JSON data files shared by all modules at runtime.
    ├── moves/all_moves.json
    ├── characters/all_characters.json
    ├── abilities/all_abilities.json
    └── techniques/all_techniques.json
```

---

## How to Build and Run

### Build everything
```bash
mvn compile
```

### Run graphics mode (macOS — ALWAYS include -XstartOnFirstThread)
```bash
mvn -Drevision=1.1.1 -pl core,graphics -am clean verify
java -XstartOnFirstThread -jar graphics/target/graphics-1.1.1.jar
```

> **macOS rule:** Every time you run the graphics JAR on macOS, the
> `-XstartOnFirstThread` flag is required. GLFW (the windowing library) must
> run on the main thread on Mac. This is permanent and non-negotiable.
> (The packaged `.app`/`.dmg` sets this automatically — see Packaging below.)

> **Rebuild rule:** Always repackage before running. Editing a `.java` file does
> not update the JAR automatically.

> The graphical editors are launched from the main menu inside the graphics
> front-end — there is no separate editor command anymore.

> **Where data lives:** On first launch the game copies its bundled default
> data into a per-user directory (`~/Library/Application Support/JujutsuKaisenFighter/`
> on macOS, `%APPDATA%\JujutsuKaisenFighter\` on Windows). The in-game editors
> read and write there, so your edits persist across launches and survive
> upgrades. On launch, newly bundled move definitions are appended by name
> without overwriting existing player moves.

### Run tests
```bash
mvn test
```
Tests live in `core/src/test/java/com/jjktbf/StatVerificationTest.java`.
There are 14 tests covering HP, AP bar, hit chance, CE efficiency, move slots,
Black Flash chance, damage range, block timing, block tag filters, and block damage ordering.

---

## Packaging & Releases

The game ships as self-contained installers with a bundled Java runtime —
players need no JDK, Maven, or source. See **[`RELEASE.md`](RELEASE.md)** for
the full process; the short version:

- The version lives in **one place**: `<revision>` in the root `pom.xml`.
- Local build for the current OS:
  ```bash
  ./release.sh 1.0.0          # or: ./release.sh 1.0.0 fast   (skips tests)
  ```
  Output: `dist/JujutsuKaisenFighter-<ver>-<os>-<arch>.dmg` (macOS) / `.msi` (Windows).
- Automated cross-platform release: push a tag `v1.0.0`. GitHub Actions builds
  macOS (arm64 + x64) and Windows (x64) installers and attaches them to a
  GitHub Release.
- Rebranding: replace `packaging/icon.png` with a 1024×1024 master icon and
  rebuild — no packaging config edits needed.
- Player data (saves, edits, settings) is stored outside the app, so upgrades
  never delete it.

### How to cut a new release

```bash
# 1. Bump the version (edit this one value):
#    pom.xml → <revision>1.1.0</revision>
git commit -am "Release 1.1.0"

# 2. Run tests to be safe:
mvn -Drevision=1.1.0 -pl core,graphics -am clean verify

# 3. Tag and push:
git tag v1.1.0
git push origin v1.1.0
```

Pushing the tag triggers GitHub Actions, which builds macOS (arm64 + x64) and
Windows (x64) installers and attaches them to a new GitHub Release. Players
download them from the repo's **Releases** page; their saved data from earlier
versions carries over automatically.

Key files: `release.sh`, `packaging/package.sh`, `packaging/make-icons.py`,
`.github/workflows/release.yml`.

---

## Core Design Principles

These were established deliberately and should be maintained throughout development.

### 1. Loose coupling — changes touch the minimum number of files

The key test: if you add a new `DefenseType`, `InterruptType`, or `StatusEffectType`,
how many files need to change?

The answer should be: **the enum itself + the one class that owns that behaviour**.

Current mechanisms that enforce this:
- `Move.applyBlockTo(int damage)` — block reduction logic lives on `Move`, not scattered across `DamageCalculator`, `Timeline`, and `CombatResolver`.
- `Move.resolveInterruptOn(tick, timeline)` — interrupt dispatch lives on `Move`.
- `Move.blockActivationMessage()`, `Move.blockDisplayInfo()` — display strings live on `Move`.
- `MoveData.isPercentageBlock()`, `isFlatBlock()`, `isAnyBlock()` — string comparisons in the editor are compile-safe helper methods, not raw `"PERCENTAGE_BLOCK".equals(...)` literals.

### 2. Encapsulation — internals stay internal

- `BattleView` interface: the graphics layer implements this interface. The `core` module never knows which renderer is active.
- `AIStrategy` interface: AI logic is pluggable. `GreedyAIStrategy` is the default.
- `SlotBudgetEnforcer`: slot budget logic lives in one place in `core`. Both the domain (`Character.validateAndBuildMoveList`) and the graphical Character editor (`AssignmentPanel`) delegate to it.
- `StatKey` enum: stat name mapping (string aliases, display labels, read/write on `CharacterData`) lives in one place. `CharacterStats.getByName()` and `AbilityApplicator` both delegate to it. Adding a new stat means touching one enum.
- `BaseRepository<D>`: the three repositories (`MoveRepository`, `CharacterRepository`, `AbilityRepository`) share their load/save/CRUD/ID-resequence behaviour via this abstract base. Each subclass supplies only id accessors, a Jackson `TypeReference`, a seed hook, and an entity name.

### 3. Abstraction — callers don't need to know implementation details

Good example: `Timeline.hasActiveBlockAt(tick)` calls `move.isActiveBlock()` — it does not compare `DefenseType` enum values directly. If a third block type is added, only `Move` changes.

Bad pattern to avoid: raw string comparisons like `"PERCENTAGE_BLOCK".equals(md.defenseType)`. These are invisible to the compiler and silently break on rename. Always use the helper methods on `MoveData` instead.

### 4. The BattleView contract

`BattleController` only ever calls methods on `BattleView`. It never calls `System.out`, never references `BattleScreen`. This is what makes swapping renderers zero-cost to core.

When the graphics `BattleScreen` implements `BattleView`, it runs the controller on a background thread and uses `Gdx.app.postRunnable()` to push state updates back to the LibGDX render thread. `promptMoveSelection()` blocks the controller thread via a `volatile boolean` flag until the player confirms their queue.

---

## Glossary

**`GLOSSARY.txt` in the project root is the canonical reference for all game terms.**

Before writing any display text, editor labels, or code comments, check there first.
Key clarifications:

| Term | Meaning |
|---|---|
| **Defense** | A *computed combat stat* derived from Durability + CE Reserves. Applied *after* defensive moves. Not a raw base stat. |
| **DEFENSIVE** | A MoveTag applied to blocking moves (PERCENTAGE_BLOCK, FLAT_BLOCK). Applied *before* Defense. |
| **blockDamageReduction** | The percentage reduction property of a PERCENTAGE_BLOCK move. Not the same as Defense. |
| **PERCENTAGE_BLOCK** | DefenseType for percentage-based damage reduction (0–100%). |
| **FLAT_BLOCK** | DefenseType for flat damage subtraction. |
| **MOVE_BLOCK_REDUCED** | CombatEvent emitted when a block reduces but does not fully negate damage. |
| **isFreeMove** | A move that does not consume a move slot. Still respects prereqs and technique restrictions. |
| **requiredTechniqueId** | Stored as a plain string (e.g. "SHRINE") pending implementation of the Technique class. |
| **AP tick** | One unit on the AP timeline. "Game tick" and "AP tick" are both acceptable. |
| **Fire tick** | The tick at which a move's effect resolves. `fireTick = startTick + unleashPoint - 1`. |

---

## Architecture: Data Flow

```
JSON file
    ↓ Repository.load()
DTO (MoveData / CharacterData / AbilityData)
    ↓ .toMove() / .toCharacter(moveRepo) / AbilityRepository
Domain object (Move / Character / Ability)   ← immutable
    ↓ BattleCombatant constructor
AbilityApplicator.apply()
    → effectiveStats (CharacterStats, ability-modified)
    → AbilityFlags (non-stat runtime effects)
    ↓ BattleController
BattleState + Timeline
    ↓ CombatResolver.resolveRound()
List<CombatEvent>
    ↓ BattleView.displayCombatEvents()
BattleScreen (render)
```

---

## Known Pending Work

### High priority
- **Technique class**: `requiredTechniqueId` on moves and `innateTechniqueName` on characters are currently plain strings matched case-insensitively. A proper `InnateTechiqueEntry` class needs to be built with: `id`, `name`, `List<Move> availableMoves`, `List<Ability> techniqueAbilities`. Until then the string matching works but is fragile.

### Medium priority
- **`MoveData.derivedCategory()` fragility**: The tag-to-category mapping is a manually maintained if/else ladder. If a new `MoveCategory` is added, this method must be updated manually — there is no exhaustiveness check. A future refactor should move this logic onto `MoveCategory` itself.
- **`CombatEvent.Type` display prefixes**: The battle UI's switch on event types for display prefixes has 10 named cases. Any new event type silently falls through to the default prefix. Consider a `displayPrefix()` method on `CombatEvent.Type`.

### Low priority / future sessions
- **Parry and dodge**: `DefenseType` was deliberately left with only PERCENTAGE_BLOCK and FLAT_BLOCK. Parry and dodge are planned for a future session.
- **Cursed Spirit character type**: `CursedSpiritCharacter` exists as a stub but `CharacterData.toCharacter()` always creates a `SorcererCharacter`. Needs dispatch on a stored `characterType` field once the type is differentiated.
- **AI difficulty**: Only `GreedyAIStrategy` exists. The `AIStrategy` interface is ready for new implementations (defensive AI, random AI, etc.).
- **Ability runtime enforcement**: `AbilityFlags.lockedMoveTags` and `autoStatusEffects` are populated but not yet read during combat resolution. The data model is complete; the engine hooks are not.

---

## Graphics Module Notes

- **Library**: LibGDX 1.12.1, LWJGL3 backend
- **Font**: Press Start 2P (FreeType, generated at runtime from `assets/fonts/PressStart2P-Regular.ttf`)
- **Placeholder sprites**: `assets/sprites/player_placeholder.png` and `enemy_placeholder.png` — 64×96 solid colour PNGs. Replace with real pixel art when ready.
- **Battle planner**: `BattleScreen` implements `BattleView`; the 150-dot planning UI uses runtime-generated nearest-neighbour pixel textures from `ui/battle/BattleUiAssets`.
- **Thread model**: `BattleController.runBattle()` runs on a daemon background thread (`battle-thread`). All LibGDX render-thread mutations go through `Gdx.app.postRunnable()`.
- **Editors (Scene2D)**: The three editors (`MoveEditorScreen`, `CharacterEditorScreen`, `AbilityEditorScreen`) and the main menu are Scene2D Stage-based UIs with full CRUD. UI textures are generated in code by `PixelSkin` (no binary assets) using the battle planner's framed navy, parchment-card, and green-action visual language. The shared chrome lives in `EditorScreenBase<D>`; reusable field widgets (`StatField`, `TagPicker`, `EnumSelectBox`, `AssignmentPanel`, `EffectListEditor`) live in `com.jjktbf.graphics.ui.editor`. Edits are held in an in-memory draft; Save validates and persists, Cancel discards.
- **Screen background**: All screens clear to `#CDDCFA` (light blue). Button text turns bright yellow (`#FFE32E`) on hover to signal clickability.

---

## Java / Maven Notes

- Java 17 (source + target in both `<properties>` and `maven-compiler-plugin`)
- Jackson 2.15.2 (JSON serialization for all data DTOs)
- JUnit Jupiter 5.10.0 (test scope, core module only)
- LibGDX 1.12.1 (graphics module only — `gdx`, `gdx-backend-lwjgl3`, `gdx-platform natives-desktop`, `gdx-freetype`, `gdx-freetype-platform natives-desktop`)
- The graphics module uses `maven-shade-plugin` to produce a fat JAR with all dependencies bundled.
