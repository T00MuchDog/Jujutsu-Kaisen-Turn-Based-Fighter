## AI rewrite — greedy timeline-filling with placement intelligence

### 1. `AIStrategy` interface (contract change)
- Replace `List<Move> selectMoves(BattleCombatant ai, BattleCombatant opponent)` with `BattlePlan selectPlan(BattleCombatant ai, BattleCombatant opponent, Random rng)`.
- Strategy now owns BOTH selection and placement (required for cross-board tick alignment).

### 2. `GreedyAIStrategy` rewrite (kept as default; name still applies)
**Selection — single greedy loop (keeps adding until no move is affordable):**
- Partition affordable, non-ability-locked known moves into offensive (`hasTag("ATTACK")`), defensive (`isDefensive()`), utility (rest).
- Each iteration: collect moves still fitting remaining AP/CE budgets and not yet used this round. If none → stop.
- **Weighted-random pick** via `rng`: offensive high weight, defensive medium (only once ≥1 offensive is placed), utility low. Gives variety + offense preference.
- Each move used at most once per round; removed from candidates after any attempt (guarantees termination).

**Placement:**
- *Offensive/utility* → grouped + early: place right after the last segment on that board (tick 1 if none), with a small random 0–2 tick gap biased toward 0. Satisfies "earlier", "grouped", "placement randomness".
- *Defensive* → align **fire tick** with a random placed offensive segment's fire tick (`startTick = offensiveFireTick - move.unleashPoint + 1`, clamped ≥ 1). If occupied/out of bounds on the defensive board, fall back to grouped-early. Satisfies "defensive on same ticks as offensive".

CE costing via existing `CeEfficiencyCalculator.computeActualCost(move, efficiency, abilityFlags)`.

### 3. `BattleController.buildAiPlan` — thin delegate
- Becomes `return aiStrategy.selectPlan(ai, opponent, rng);`
- Ability-lock check moves into the strategy; the "locked"/"could not place" displayMessage warnings are removed (internal noise).

### Edge cases
- No offensive moves → defensives use grouped-early fallback.
- Tiny AP bar → still fills greedily (fixes single-strike bug; loops until nothing's affordable).
- Placement failure → candidate removed, loop continues.

### Defaults chosen (flag if wrong)
- "Same ticks" = fire-tick alignment (when moves resolve).
- Each move usable once per round (matches player card UX).
- Randomness tuned subtle (per project's "subtlety and emergent behaviour" principle).

### Files touched
- `core/.../controller/AIStrategy.java` (interface)
- `core/.../controller/GreedyAIStrategy.java` (rewrite)
- `core/.../controller/BattleController.java` (`buildAiPlan` thinning)
- No data/DTO/graphics changes; no test changes.