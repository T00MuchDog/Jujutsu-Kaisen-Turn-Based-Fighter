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
mvn -Drevision=1.0.0 -pl core,graphics -am clean verify
java -XstartOnFirstThread -jar graphics/target/graphics-1.0.0.jar
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

### Recent engineering update — character dossier selection

1. What changed: Character selection is now a master-detail page: the selectable roster is on the left, while the selected character's sprite, full HP/CE bars, raw base stats, and flavour description render on the right. Selection remains keyboard-driven and can also be changed by clicking a roster row.
2. Architectural/data/API implications: `CharacterData` now persists `description` and a relative `spriteAsset` path. `AssetLoader.characterSprite()` resolves and caches these textures, falling back to the player placeholder when an authored path is absent. The Character Editor exposes both fields. The domain `Character` remains combat-only; presentation metadata stays on the DTO.
3. Important files touched:
   - Core/data: `CharacterData.java`, `CharacterRepository.java`, `data/characters/all_characters.json`
   - Graphics: `CharacterSelectScreen.java`, `CharacterEditorScreen.java`, `AssetLoader.java`, `assets/characters/`
4. Follow-up task: the initial files under `assets/characters/` are replaceable placeholders. Replace them with authored per-character pixel art as the roster expands.

### Recent engineering update — character dossier layout pass

1. What changed: The dossier (right-hand) page of the character selection screen was re-laid out. The character name now sits in the top-left corner at a larger title size; the profile sprite is roughly three times larger and paired with HP/CE bars scaled to match it; base stats now use full words ("Strength", "Jujutsu Skill", "CE Reserves", …) instead of shorthand ("STR", "JS", …); and the description header and body render at the medium font size for legibility.
2. Architectural/data/API implications: `AssetLoader` exposes a new `fontXLarge` (size 26) generated from the same bundled TTF and disposed alongside the other fonts. No DTO or persistence changes.
3. Important files touched:
   - Graphics: `CharacterSelectScreen.java` (page layout), `AssetLoader.java` (new font).

### Recent engineering update — paced execution and manual round advance

1. What changed: Resolution events now advance at a deliberate 520 ms cadence instead of appearing almost instantly. At round end, the battle waits on a pixel-styled **Next Round** button before returning to planning. The execution HUD now uses the planner's pixel-frame texture kit: larger framed combatant sprites, in-frame HP/CE bars, a top-left battle log, a top-right enemy portrait, a bottom-left player portrait, and a bottom-right round button.
2. Architectural/data/API implications: `BattleView` gained blocking `awaitNextRound(BattleState)`, called by `BattleController` after round-end effects. `BattleScreen` owns that interaction on the render thread while the controller continues to block on its battle thread. `GraphicsMain` starts in its normal resizable macOS window; execution geometry is rebuilt in `Screen.resize`, with both portrait sizes derived from the same viewport-height scale when the system window changes size.
3. Important files touched:
   - Core: `BattleView.java`, `BattleController.java`
   - Graphics: `BattleScreen.java`, `CombatantPanel.java`, `StatusBar.java`
4. Follow-up task: event pacing currently advances the resolved event log rather than animating the AP cursor tick-by-tick. A future execution pass can expose resolver tick snapshots for per-segment animation.

### Recent engineering update — 150-dot timeline + planning UI polish

1. What changed: The planning timeline is now 150 ticks instead of 300. The planner renders all 150 AP dots at the correct spacing, draws AP-sized segments at their snapped position, removes instructional copy, wraps move-card text within its card, and wraps narrow segment names. Cards now use separate ACC/PWR and AP/CE left-right rows; move descriptions occupy the middle of the card. The CE planner readout now reports remaining CE over total CE (`400/400` at a fresh round), rather than CE spent.
2. Architectural/data/API implications: `Timeline.DEFAULT_GRID_LENGTH` is now 150 and `BattlePlan.GRID_LENGTH` derives from it, so the offensive, defensive, and legacy execution timelines share one source of truth. `Move.hasCeCost` distinguishes a zero-cost CE move from a move with no CE cost; old JSON without the field infers it from a positive base CE cost. `neverMiss` continues to represent an absent accuracy stat and renders as `ACC N/A`. Planning CE remains a prediction only and does not drain the combatant until resolution.
3. Important files touched:
   - Core: `Timeline.java`, `BattlePlan.java`, `Move.java`, `MoveData.java`, `CeEfficiencyCalculator.java`
   - Graphics: `PlanningPanel.java`, `TimelineBar.java`, `ActionSegmentView.java`, `MoveCardView.java`, `MoveEditorScreen.java`, `BattleUiAssets.java`
   - Docs: `GLOSSARY.txt`, this README
4. Follow-up task: planner chrome is currently generated as nearest-neighbour pixel textures at runtime. Replace the texture kit with checked-in PNG assets when final art direction is ready.

### Recent engineering update — dead code removal + repository refactor

1. What changed: Removed the obsolete `editor` (CLI editors) and `text` (CLI battler) modules, the orphaned `EditorView` interface, four unused UI textures (`panel`/`button_*`) + their loader fields, the orphaned `uiskin.json`, and a redundant font-fallback line in `AssetLoader`. Extracted `BaseRepository<D>` so the three repositories share load/save/CRUD/ID-resequence behaviour (each now ~55 lines, down from ~170). Documented `MoveData.fromMove()` as lossy-for-editor-drafts to prevent the tag-collapse bug recurring.
2. Architectural/data/API implications: Two modules gone — the build is now `core` + `graphics` only. `graphics` no longer depends on `editor`. The three `*Repository` classes are now thin `BaseRepository` subclasses; their public API (load/save/getAll/findById/exists/add/update/delete/nextId/formatId/size/getDataFile) is unchanged. Repository `add()` now uniformly assigns the canonical next id when blank (previously `CharacterRepository`/`AbilityRepository` always overwrote; in practice identical since editors clear the id before add).
3. Important files touched:
   - Removed: `editor/` (7 files + pom), `text/` (2 files + pom), `view/EditorView.java`, `assets/skin/uiskin.json`, `assets/ui/{panel,button_normal,button_hover,button_pressed}.png`
   - New: `core/.../model/repo/BaseRepository.java`
   - Rewritten: `MoveRepository.java`, `CharacterRepository.java`, `AbilityRepository.java` (now extend `BaseRepository`)
   - Edited: `pom.xml` (modules), `graphics/pom.xml` (dropped editor dep), `AssetLoader.java` (dropped unused fields + redundant fallback), `MoveData.java` (`fromMove` Javadoc)
4. Follow-up tasks or risks: The battle UI (`BattleScreen`, `CharacterSelectScreen`) still uses per-screen hand-rolled `SpriteBatch`/`ShapeRenderer`; planned to migrate to the same Scene2D system as the editors. `CursedSpiritCharacter` remains as a documented future-type stub.

### Recent engineering update — Innate Technique foundation

1. What changed: Introduced innate techniques as first-class entities with their own class, repository (`data/techniques/all_techniques.json`), and graphical editor. A technique owns only `{id, name, description}`; its move/ability progression is **discovered** at runtime (query repos for entries referencing its name, filter by mastery, sort ascending). Made `UNLOCK_TECHNIQUE` actually functional — a character's accessible techniques now = innate technique ∪ techniques granted by applied abilities (was a documented no-op). Added a save-validated `Move.Builder` invariant: technique-tagged moves must declare their governing mastery prereq (INNATE→cursedTechniqueMastery, NON_INNATE→jujutsuSkill) and innate moves must name their technique. Added `AbilityData.masteryThreshold` (auto-grant ordering for technique abilities). The Character editor's move panel now shows technique moves in three states: **locked** (CTM-insufficient, greyed + unclickable), unlocked, learned — surfacing the progression ladder.
2. Architectural/data/API implications: New `model/technique/` package (`InnateTechnique`, `InnateTechniqueData`, `TechniqueRepository`). `Character` gained an 8-arg constructor taking a resolved `Set<String> accessibleTechniques`; move validation checks set membership instead of a single `equalsIgnoreCase`. `CharacterData.toCharacter` (existing overloads) is unchanged — the constructor resolves the access set from `innateTechniqueName` + ability `UNLOCK_TECHNIQUE` effects. Single-source-of-truth: editing a move's CTM prereq automatically re-sorts its technique progression (no stored id-lists to sync). Technique names remain string-matched case-insensitively during this transition.
3. Important files touched:
   - New (core): `model/technique/{InnateTechnique, InnateTechniqueData, TechniqueRepository}.java`; `data/techniques/all_techniques.json` (seeded: Shrine, Limitless)
   - New (graphics): `screens/editors/TechniqueEditorScreen.java`
   - Edited (core): `Character.java` (accessibleTechniques ctor + membership check), `AbilityApplicator.java` (UNLOCK_TECHNIQUE comment), `AbilityData.java` (+masteryThreshold), `Move.java` (Builder invariant)
   - Edited (graphics): `JJKGame.java` + `MainMenuScreen.java` (technique editor wiring), `AssignmentPanel.java` (locked row state), `CharacterEditorScreen.java` (CTM-locked move rows), `MoveEditorScreen.java` (technique-exists hint), `AbilityEditorScreen.java` (masteryThreshold field)
   - Test: `StatVerificationTest.java` (invariant-compliant test move)
4. Follow-up tasks or risks: **Domains** deferred (sure-hit, clash, arena-lock — needs a `MoveData.sureHit` flag + clash resolver). **Copy stat substitution** deferred (foundation enables it; the "substitute CTM with a copy stat" effect flag is a later addition). CTM does double-duty (unlock gate + slot budget, both `/20`); a baseline character (CTM 80) cannot use a technique whose cheapest move costs >80 CTM — an authoring guideline, not a code fix.

### Recent engineering update — battle planning v2 (timeline creation draft)

1. What changed: Rebuilt the round-planning model and UI around a fixed 150-dot two-board timeline (offensive above defensive). `Timeline` is now a free-placement 150-wide spatial board (gaps allowed; no contiguous-packing rule); AP-budget enforcement lifted to a new `BattlePlan` that owns both timelines + shared AP/CE budgets. `BattleView.promptBattlePlan(BattleCombatant, BattleCombatant)` replaces `promptMoveSelection` as the planning contract — the view owns the drag-place UI and returns the finished plan. A new `PlanningPanel` (graphics) renders the two bars + bottom move palette + Lock In button, with drag-to-place, snap-to-grid, attack/defense board gating, and live AP/CE budget greying.
2. Architectural/data/API implications: **`maxApBar` repurposed** — it no longer sets grid length (now a fixed `BattlePlan.GRID_LENGTH = 150`); it is the *AP budget* spendable across both timelines. CE pool stays a planning budget (the agreed predictor): placing a segment deducts its CE cost; the real `currentCe` is untouched and carries into execution. `hasTag("ATTACK")` (basePower+category heuristic) drives the offensive/defensive board split. **Execution is unchanged** — `BattleController` converts each plan to the legacy single `Timeline` via `BattlePlan.toLegacyTimeline()` so today's `CombatResolver` runs as-is.
3. Important files touched:
   - New (core): `model/combat/BattlePlan.java`
   - New (graphics): `ui/battle/{PlanningPanel, TimelineBar, ActionSegmentView, MoveCardView}.java`
   - Edited (core): `Timeline.java` (free-placement 150-board; queries preserved), `BattleCombatant.java` (+plan field/accessors), `BattleView.java` (+promptBattlePlan), `BattleController.java` (builds BattlePlan for player + AI, converts to legacy timeline), `CombatResolver.java` (getMaxApBar → getGridLength)
   - Edited (graphics): `BattleScreen.java` (real PlanningPanel flow replaces legacy delegate; planning panel drawn during planning phase)
   - Test: `StatVerificationTest.java` (addMove → placeAt at the three timeline test sites)
4. Follow-up tasks or risks: **Execution phase** (cross-timeline ticker, same-tick speed tie-breaking, offensive-before-defensive ordering, left-side battle log) is the next major task — `toLegacyTimeline()` is a stopgap. The `PlanningPanel` drag-ghost text rendering is minimal (shapes + snap glow work; full drag-avatar text polish pending). Planning input is raw `InputAdapter` (not Scene2D), consistent with the existing battle render loop. The legacy `promptMoveSelection` is deprecated but retained so the old `MoveCard` flow stays compilable.

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
