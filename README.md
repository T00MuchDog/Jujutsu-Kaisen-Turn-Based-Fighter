# Jujutsu Kaisen — Turn Based Fighter

A turn-based combat game built in Java with a multi-module Maven architecture.
Characters, moves, and abilities are all data-driven (JSON) and authored through
dedicated CLI editors. A LibGDX graphics front-end is in development alongside
the existing terminal mode.

---

## Module Structure

```
JJKTBF/
├── core/      Domain model + combat engine. No I/O, no rendering. All other modules depend on this.
├── text/      Terminal front-end. TextMain is the entry point for CLI play.
├── graphics/  LibGDX graphics front-end. GraphicsMain is the entry point for windowed play.
├── editor/    CLI data-authoring tools (Move, Character, Ability editors).
└── data/      JSON data files shared by all modules at runtime.
    ├── moves/all_moves.json
    ├── characters/all_characters.json
    └── abilities/all_abilities.json
```

---

## How to Build and Run

### Build everything
```bash
mvn compile
```

### Run text mode (CLI)
```bash
mvn package -pl text -am -DskipTests
java -jar text/target/text-1.0-SNAPSHOT.jar
```

### Run graphics mode (macOS — ALWAYS include -XstartOnFirstThread)
```bash
mvn package -pl graphics -am -DskipTests
java -XstartOnFirstThread -jar graphics/target/graphics-1.0-SNAPSHOT.jar
```

> **macOS rule:** Every time you run the graphics JAR on macOS, the
> `-XstartOnFirstThread` flag is required. GLFW (the windowing library) must
> run on the main thread on Mac. This is permanent and non-negotiable.

> **Rebuild rule:** Always repackage before running. Editing a `.java` file does
> not update the JAR automatically.

### Run CLI editors
```bash
# Move editor
mvn exec:java -pl editor -Dexec.mainClass=com.jjktbf.editor.MoveEditorMain

# Character editor
mvn exec:java -pl editor -Dexec.mainClass=com.jjktbf.editor.CharacterEditorMain

# Ability editor
mvn exec:java -pl editor -Dexec.mainClass=com.jjktbf.editor.AbilityEditorMain
```

### Run tests
```bash
mvn test
```
Tests live in `core/src/test/java/com/jjktbf/StatVerificationTest.java`.
There are 14 tests covering HP, AP bar, hit chance, CE efficiency, move slots,
Black Flash chance, damage range, block timing, block tag filters, and block damage ordering.

---

## Core Design Principles

These were established deliberately and should be maintained throughout development.

### Recent engineering update — Action Segment terminology

1. What changed: AP timeline occupancy is now called **Action Segment**. Defensive block terminology remains unchanged for `PERCENTAGE_BLOCK`, `FLAT_BLOCK`, `MOVE_BLOCKED`, `MOVE_BLOCK_REDUCED`, and block reduction fields.
2. Architectural/data/API implications: the AP occupancy class is `ActionSegment`; timeline APIs now use `segmentAt()`, `nextSegmentAfter()`, and `getSegments()`. Interrupt enum values that target queued timeline occupancy are now `KNOCK_CURRENT_SEGMENT` and `KNOCK_NEXT_SEGMENT`.
3. Important files touched: `ActionSegment.java`, `Timeline.java`, `CombatResolver.java`, `DamageCalculator.java`, `Move.java`, `InterruptType.java`, editor labels, and `GLOSSARY.txt`.
4. Follow-up tasks or risks: existing external move data using legacy interrupt strings must be migrated.

### Recent engineering update — Combat pipeline fixes

1. What changed: defensive blocks now activate from `fireTick`, respect `blockDuration` and `blockAffectedTags`, and apply before Defense using `basePower × Power → block reduction → Defense → scale/roll`. Basic Block JSON now uses `PERCENTAGE_BLOCK` with 50% reduction.
2. Architectural/data/API implications: `Timeline.activeBlockAt(tick, incomingMove)` owns block-window/tag lookup. `CeEfficiencyCalculator` has an ability-aware overload. `Character` now carries resolved abilities, and text/graphics character loading resolves `abilityIds` through `AbilityRepository`.
3. Important files touched: `DamageCalculator.java`, `Timeline.java`, `CombatResolver.java`, `BattleController.java`, `BattleCombatant.java`, `AbilityApplicator.java`, `CharacterData.java`, `Character.java`, text/graphics battle entry points, `data/moves/all_moves.json`, `GLOSSARY.txt`, and `StatVerificationTest.java`.
4. Follow-up tasks or risks: status-effect behavior remains intentionally unimplemented. Existing saved data with legacy interrupt names or invalid defense types still needs migration if present outside the current JSON files.

### Recent engineering update — Scene2D graphical editors

1. What changed: Replaced the three read-only, keyboard-only graphical editor screens and the keyboard-only main menu with full mouse-driven, pixel-art (Gen 3 FireRed/LeafGreen style) CRUD editors running under LibGDX Scene2D. Added `PixelSkin` (code-generated 9-patch textures), `EditorScreenBase<D>` (master-detail chrome), and reusable field widgets (`StatField`, `TagPicker`, `EnumSelectBox`, `AssignmentPanel`, `EffectListEditor`). All screens share a `#CDDCFA` light-blue background; button text highlights bright yellow (`#FFE32E`) on hover.
2. Architectural/data/API implications: The graphics module now depends on Scene2D (`Stage`, `Skin`, `ScrollPane`, `DragAndDrop`). All UI textures are generated in code — no new binary assets. The `EditorScreenBase` owns the draft lifecycle (new/edit/save/cancel) with explicit save validation. The `core` module is untouched — editors reuse `SlotBudgetEnforcer`, `CombatStats`, `StatKey`, repos, and DTO `toMove()`/`toCharacter()` validation. `PixelSkin` now generates three fonts (small 8, body 10, large 18); the large font drives the `"title"` label style and main-menu title.
3. Important files touched:
   - New: `graphics/.../ui/pixel/PixelSkin.java`, `graphics/.../ui/editor/{EditorScreenBase, ValidationResult, StatField, TagPicker, EnumSelectBox, EffectListEditor, AssignmentPanel}.java` (8 files)
   - Rewritten: `screens/MainMenuScreen.java`, `screens/editors/{MoveEditorScreen, CharacterEditorScreen, AbilityEditorScreen}.java` (4 files)
   - Edited: `AssetLoader.java` (load/dispose `editorSkin`), `CharacterSelectScreen.java` + `BattleScreen.java` (shared background colour)
4. Follow-up tasks or risks: Legacy on-disk move data (`data/moves/all_moves.json`) still uses old field names (`defenseBuffDuration`, `isGuaranteedMove`) — first Move-editor Save rewrites cleanly. Scene2D `DragAndDrop` inside `ScrollPane` may need tuning. Point-buy budget logic is ported from `StatEntryFlow` into the GUI (not shared code).

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

- `BattleView` interface: the graphics layer and the text layer both implement this interface. The `core` module never knows which renderer is active.
- `AIStrategy` interface: AI logic is pluggable. `GreedyAIStrategy` is the default.
- `SlotBudgetEnforcer`: slot budget logic lives in one place in `core`. Both the domain (`Character.validateAndBuildMoveList`) and the editor (`MoveAssignmentFlow`) delegate to it.
- `StatKey` enum: stat name mapping (string aliases, display labels, read/write on `CharacterData`) lives in one place. `CharacterStats.getByName()` and `AbilityApplicator` both delegate to it. Adding a new stat means touching one enum.
- `EditorIO`: all CLI prompt/display utilities for editors live in this one class. The three editor mains (`MoveEditorMain`, `CharacterEditorMain`, `AbilityEditorMain`) delegate to it.

### 3. Abstraction — callers don't need to know implementation details

Good example: `Timeline.hasActiveBlockAt(tick)` calls `move.isActiveBlock()` — it does not compare `DefenseType` enum values directly. If a third block type is added, only `Move` changes.

Bad pattern to avoid: raw string comparisons like `"PERCENTAGE_BLOCK".equals(md.defenseType)`. These are invisible to the compiler and silently break on rename. Always use the helper methods on `MoveData` instead.

### 4. The BattleView contract

`BattleController` only ever calls methods on `BattleView`. It never calls `System.out`, never references `TextBattleView` or `BattleScreen`. This is what makes swapping renderers zero-cost to core.

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
TextBattleView / BattleScreen (render)
```

---

## Known Pending Work

### High priority
- **Technique class**: `requiredTechniqueId` on moves and `innateTechniqueName` on characters are currently plain strings matched case-insensitively. A proper `InnateTechiqueEntry` class needs to be built with: `id`, `name`, `List<Move> availableMoves`, `List<Ability> techniqueAbilities`. Until then the string matching works but is fragile.

### Medium priority
- **Graphics Phase 4 — full editor forms**: The three graphical editor screens (`MoveEditorScreen`, `CharacterEditorScreen`, `AbilityEditorScreen`) currently display read-only summaries. Full edit forms (text fields, dropdowns, tag pickers) need to be built using LibGDX Scene2D widgets.
- **`MoveData.derivedCategory()` fragility**: The tag-to-category mapping is a manually maintained if/else ladder. If a new `MoveCategory` is added, this method must be updated manually — there is no exhaustiveness check. A future refactor should move this logic onto `MoveCategory` itself.
- **`CombatEvent.Type` in `TextBattleView`**: The switch on event types for display prefixes has 10 named cases. Any new event type silently falls through to the default prefix. Consider a `displayPrefix()` method on `CombatEvent.Type`.

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
- **No core changes required**: `BattleScreen` implements `BattleView`. `JJKGame` wires it to `BattleController`. The entire `core` module is untouched.
- **Thread model**: `BattleController.runBattle()` runs on a daemon background thread (`battle-thread`). All LibGDX render-thread mutations go through `Gdx.app.postRunnable()`.
- **Editors (Scene2D)**: The three editors (`MoveEditorScreen`, `CharacterEditorScreen`, `AbilityEditorScreen`) and the main menu are Scene2D Stage-based UIs with full CRUD. UI textures are generated in code by `PixelSkin` (no binary assets) in a Gen-3 FireRed/LeafGreen pixel style. The shared chrome lives in `EditorScreenBase<D>`; reusable field widgets (`StatField`, `TagPicker`, `EnumSelectBox`, `AssignmentPanel`, `EffectListEditor`) live in `com.jjktbf.graphics.ui.editor`. Edits are held in an in-memory draft; Save validates and persists, Cancel discards.
- **Screen background**: All screens clear to `#CDDCFA` (light blue). Button text turns bright yellow (`#FFE32E`) on hover to signal clickability.

---

## Java / Maven Notes

- Java 17 (source + target in both `<properties>` and `maven-compiler-plugin`)
- Jackson 2.15.2 (JSON serialization for all data DTOs)
- JUnit Jupiter 5.10.0 (test scope, core module only)
- LibGDX 1.12.1 (graphics module only — `gdx`, `gdx-backend-lwjgl3`, `gdx-platform natives-desktop`, `gdx-freetype`, `gdx-freetype-platform natives-desktop`)
- The graphics module uses `maven-shade-plugin` to produce a fat JAR with all dependencies bundled.
