package com.jjktbf.model.combat;

import com.jjktbf.model.character.CombatStats;
import com.jjktbf.model.move.*;

import java.util.*;

/**
 * The heart of the combat engine.
 *
 * CombatResolver drives the RESOLUTION phase of a round:
 *   1. Determines the max tick count for this round (max AP bar of both combatants)
 *   2. Sweeps the action counter tick by tick
 *   3. At each tick:
 *      a. If a segment's startTick is reached → drain CE (move "begins")
 *      b. If a segment's fireTick is reached  → resolve the move
 *         - Hit roll
 *         - Full block check on defender
 *         - Damage calculation
 *         - Black Flash check and BFS state updates
 *         - Status effect application
 *         - Interrupt resolution
 *   4. After all ticks → ROUND_END processing
 *
 * Tie-breaking at the same fireTick:
 *   - Instant moves (unleashPoint == 1) fire before all others.
 *   - Among ties at the same fireTick: higher Speed wins.
 *   - Identical Speed: random resolution (coin flip).
 *
 * All effects are reported as CombatEvents collected in a list.
 * The resolver never touches I/O — events are returned to the controller.
 */
public class CombatResolver {

    private final Random rng;

    public CombatResolver(Random rng) {
        this.rng = rng;
    }

    public CombatResolver() {
        this(new Random());
    }

    // -------------------------------------------------------------------------
    // Round planning helpers
    // -------------------------------------------------------------------------

    /**
     * Compute the actual CE cost for a move for a given combatant
     * and verify the combatant has enough CE. Returns -1 if CE is insufficient.
     */
    public int computeCostIfAffordable(BattleCombatant combatant, Move move) {
        int cost = CeEfficiencyCalculator.computeActualCost(
            move,
            combatant.getEffectiveStats().getCursedEnergyEfficiency(),
            combatant.getAbilityFlags()
        );
        return (combatant.getCurrentCe() >= cost) ? cost : -1;
    }

    // -------------------------------------------------------------------------
    // Resolution phase
    // -------------------------------------------------------------------------

    /**
     * Execute the full resolution phase for one round. Convenience method that
     * resolves every tick at once. Equivalent to calling
     * {@link #beginResolution(BattleState)} then looping
     * {@link #resolveTick(BattleState)} until {@link #hasMoreTicks()} is false.
     *
     * @param state     the current battle state (Phase must be RESOLUTION)
     * @return          ordered list of all events that occurred this resolution
     */
    public List<CombatEvent> resolveRound(BattleState state) {
        List<CombatEvent> events = new ArrayList<>();
        beginResolution(state);
        while (hasMoreTicks()) {
            events.addAll(resolveTick(state));
            if (state.isBattleOver()) break;
        }
        return events;
    }

    // -------------------------------------------------------------------------
    // Per-tick resolution (driver steps the engine tick by tick)
    // -------------------------------------------------------------------------

    private static final class ResolutionCursor {
        int tick;
        int maxTick;
        boolean sustainedDrained;
    }

    private final ThreadLocal<ResolutionCursor> cursor = ThreadLocal.withInitial(ResolutionCursor::new);

    /**
     * Prepare a resolution sweep. Drains sustained CE once and records the tick
     * range to sweep. Must be called before {@link #resolveTick(BattleState)}.
     */
    public List<CombatEvent> beginResolution(BattleState state) {
        List<CombatEvent> events = new ArrayList<>();
        BattleCombatant player = state.getPlayerCombatant();
        BattleCombatant enemy  = state.getEnemyCombatant();

        Timeline playerTimeline = player.getTimeline();
        Timeline enemyTimeline  = enemy.getTimeline();

        int maxTick = Math.max(
            playerTimeline != null ? playerTimeline.getGridLength() : 0,
            enemyTimeline  != null ? enemyTimeline.getGridLength()  : 0
        );

        ResolutionCursor c = cursor.get();
        c.tick = 0;
        c.maxTick = maxTick;
        c.sustainedDrained = true;

        drainSustainedCe(player, events);
        drainSustainedCe(enemy, events);
        return events;
    }

    /** True while there are still ticks left to resolve in the current sweep. */
    public boolean hasMoreTicks() {
        ResolutionCursor c = cursor.get();
        return c.sustainedDrained && c.tick < c.maxTick;
    }

    /**
     * Advance the action counter by one tick and resolve everything that fires
     * on it. Returns the events produced by this tick only (empty if nothing
     * happened). The sustained-CE drain happens once, in beginResolution.
     */
    public List<CombatEvent> resolveTick(BattleState state) {
        ResolutionCursor c = cursor.get();
        if (!c.sustainedDrained || c.tick >= c.maxTick) return List.of();

        c.tick++;
        int tick = c.tick;
        List<CombatEvent> events = new ArrayList<>();

        BattleCombatant player = state.getPlayerCombatant();
        BattleCombatant enemy  = state.getEnemyCombatant();

        state.advanceTick();

        // --- CE drain when a segment starts ---
        drainCeForStartingSegments(player, tick, events);
        drainCeForStartingSegments(enemy,  tick, events);

        // --- Collect all moves firing this tick ---
        List<FiringEntry> firing = collectFiringMoves(player, enemy, tick);

        // --- Sort by priority ---
        sortFiringEntries(firing, player, enemy);

        // --- Resolve each firing move ---
        for (FiringEntry entry : firing) {
            if (entry.segment.isStunned()) continue;
            if (state.checkAndResolveBattleOver()) {
                events.add(CombatEvent.of(CombatEvent.Type.BATTLE_OVER)
                    .tick(tick)
                    .message("Battle ended during resolution!").build());
                return events;
            }
            resolveMove(entry, player, enemy, state, tick, events);
        }

        return events;
    }

    // -------------------------------------------------------------------------
    // CE draining
    // -------------------------------------------------------------------------

    private void drainCeForStartingSegments(BattleCombatant combatant, int tick, List<CombatEvent> events) {
        Timeline tl = combatant.getTimeline();
        if (tl == null) return;

        for (ActionSegment segment : tl.getSegments()) {
            if (segment.isStunned()) continue;
            if (segment.getStartTick() == tick && segment.getActualCeCost() > 0) {
                if (!combatant.hasCe(segment.getActualCeCost())) {
                    segment.stun();
                    events.add(CombatEvent.of(CombatEvent.Type.CE_DEPLETED)
                        .source(combatant)
                        .move(segment.getMove())
                        .tick(tick)
                        .message(combatant.getCharacter().getName() + " does not have enough CE for "
                            + segment.getMove().getName() + "!")
                        .build());
                    continue;
                }
                int drained = combatant.drainCe(segment.getActualCeCost());
                events.add(CombatEvent.of(CombatEvent.Type.CE_DRAINED)
                    .source(combatant)
                    .move(segment.getMove())
                    .intValue(drained)
                    .tick(tick)
                    .message(combatant.getCharacter().getName() + " uses " + drained
                             + " CE for " + segment.getMove().getName())
                    .build());

                if (!combatant.hasAnyCe()) {
                    events.add(CombatEvent.of(CombatEvent.Type.CE_DEPLETED)
                        .source(combatant)
                        .tick(tick)
                        .message(combatant.getCharacter().getName() + " has exhausted all Cursed Energy!")
                        .build());
                }
            }
        }
    }

    private void drainSustainedCe(BattleCombatant combatant, List<CombatEvent> events) {
        int cost = combatant.getAbilityFlags().ceCostPerRound;
        if (cost <= 0) return;
        int drained = combatant.drainCe(cost);
        events.add(CombatEvent.of(CombatEvent.Type.CE_DRAINED)
            .source(combatant)
            .intValue(drained)
            .message(combatant.getCharacter().getName() + " spends " + drained + " CE to sustain abilities.")
            .build());
        if (!combatant.hasAnyCe()) {
            events.add(CombatEvent.of(CombatEvent.Type.CE_DEPLETED)
                .source(combatant)
                .message(combatant.getCharacter().getName() + " has exhausted all Cursed Energy!")
                .build());
        }
    }

    // -------------------------------------------------------------------------
    // Firing collection and sorting
    // -------------------------------------------------------------------------

    private record FiringEntry(ActionSegment segment, BattleCombatant attacker, BattleCombatant defender) {}

    private List<FiringEntry> collectFiringMoves(BattleCombatant player, BattleCombatant enemy, int tick) {
        List<FiringEntry> firing = new ArrayList<>();

        if (player.getTimeline() != null) {
            for (ActionSegment segment : player.getTimeline().firingAt(tick)) {
                firing.add(new FiringEntry(segment, player, enemy));
            }
        }
        if (enemy.getTimeline() != null) {
            for (ActionSegment segment : enemy.getTimeline().firingAt(tick)) {
                firing.add(new FiringEntry(segment, enemy, player));
            }
        }
        return firing;
    }

    /**
     * Sort firing entries:
     *  1. Instant moves (unleashPoint == 1) first
     *  2. Higher Speed first
     *  3. Random tiebreak
     */
    private void sortFiringEntries(List<FiringEntry> firing, BattleCombatant player, BattleCombatant enemy) {
        firing.sort((a, b) -> {
            // Instant moves have top priority
            int aInstant = a.segment.isInstant() ? 1 : 0;
            int bInstant = b.segment.isInstant() ? 1 : 0;
            if (aInstant != bInstant) return bInstant - aInstant; // higher = first

            // Speed tiebreak
            int aSpeed = a.attacker.getCharacter().getBaseStats().getSpeed();
            int bSpeed = b.attacker.getCharacter().getBaseStats().getSpeed();
            if (aSpeed != bSpeed) return bSpeed - aSpeed; // higher speed first

            // Random
            return rng.nextBoolean() ? -1 : 1;
        });
    }

    // -------------------------------------------------------------------------
    // Move resolution
    // -------------------------------------------------------------------------

    private void resolveMove(
        FiringEntry       entry,
        BattleCombatant   player,
        BattleCombatant   enemy,
        BattleState       state,
        int               tick,
        List<CombatEvent> events
    ) {
        ActionSegment   segment  = entry.segment;
        Move            move     = segment.getMove();
        BattleCombatant attacker = entry.attacker;
        BattleCombatant defender = entry.defender;

        events.add(CombatEvent.of(CombatEvent.Type.MOVE_FIRED)
            .source(attacker)
            .move(move)
            .tick(tick)
            .message(attacker.getCharacter().getName() + " unleashes " + move.getName() + "!")
            .build());

        // --- Defensive moves: apply buff or register full block ---
        if (move.isDefensive()) {
            resolveDefensiveMove(attacker, move, tick, events);
            return; // defensive moves don't attack
        }

        // --- Non-damaging utility moves ---
        if (move.getCategory() == MoveCategory.UTILITY) {
            applySelfEffects(attacker, move, tick, events);
            return;
        }

        // --- Damaging moves ---
        DamageCalculator.DamageResult result = DamageCalculator.resolve(
            attacker, defender, move, tick, rng, state.getRoundNumber()
        );

        if (result.isMiss()) {
            events.add(CombatEvent.of(CombatEvent.Type.MOVE_MISSED)
                .source(attacker).target(defender).move(move)
                .tick(tick)
                .message(move.getName() + " missed " + defender.getCharacter().getName() + "!")
                .build());

        } else if (result.isBlocked()) {
            events.add(CombatEvent.of(CombatEvent.Type.MOVE_BLOCKED)
                .source(attacker).target(defender).move(move)
                .tick(tick)
                .message(defender.getCharacter().getName() + " blocked " + move.getName() + "!")
                .build());

        } else {
            // Hit — check whether a block softened it.
            // GUARD_BREAK moves ignore blocking defensive moves entirely.
            boolean wasBlocked = !move.isGuardBreak()
                && defender.getTimeline() != null
                && defender.getTimeline().activeBlockAt(tick, move) != null;

            defender.applyDamage(result.getFinalDamage());

            if (wasBlocked) {
                events.add(CombatEvent.of(CombatEvent.Type.MOVE_BLOCK_REDUCED)
                    .source(attacker).target(defender).move(move)
                    .tick(tick)
                    .message(defender.getCharacter().getName()
                             + " blocked " + move.getName() + "! (damage reduced)")
                    .build());
            }

            events.add(CombatEvent.of(CombatEvent.Type.DAMAGE_DEALT)
                .source(attacker).target(defender).move(move)
                .intValue(result.getFinalDamage())
                .tick(tick)
                .message(attacker.getCharacter().getName() + "'s " + move.getName()
                         + " hits " + defender.getCharacter().getName()
                         + " for " + result.getFinalDamage() + " damage!")
                .build());

            // Black Flash
            if (result.isBlackFlash()) {
                int ceRestored = (int) Math.round(
                    attacker.getEffectiveCombatStats().getMaxCursedEnergy()
                    * CombatStats.BF_CE_RESTORE_FRACTION
                );
                attacker.restoreCeFraction(CombatStats.BF_CE_RESTORE_FRACTION);
                boolean wasInBfs = attacker.isInBlackFlashState();
                attacker.enterBlackFlashState(state.getRoundNumber());
                if (wasInBfs) attacker.recordBfsHit();

                events.add(CombatEvent.of(CombatEvent.Type.BLACK_FLASH)
                    .source(attacker).target(defender).move(move)
                    .intValue(result.getFinalDamage())
                    .tick(tick)
                    .message("*** BLACK FLASH! *** " + attacker.getCharacter().getName()
                             + " lands a Black Flash! +" + ceRestored + " CE restored!")
                    .build());
                events.add(CombatEvent.of(CombatEvent.Type.CE_RESTORED)
                    .source(attacker).intValue(ceRestored)
                    .tick(tick)
                    .message(attacker.getCharacter().getName() + " recovered " + ceRestored + " CE!")
                    .build());
            }

            // On-hit status effects
            applyOnHitEffects(attacker, defender, move, tick, events);

            // Interrupt resolution
            if (move.hasInterrupt()) {
                resolveInterrupt(attacker, defender, move, tick, events);
            }

            // Stun tag: stun the defender's action segment(s) on the current tick.
            // Segments that already fired this tick are unaffected (the loop passed
            // them); segments queued later this tick are skipped at the firing loop's
            // isStunned() check, so they don't fire — emerging as "<defender> was
            // stunned and could not move."
            if (move.isStun()) {
                resolveStunTag(defender, tick, events);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Stun-tag resolution
    // -------------------------------------------------------------------------

    /**
     * Apply the STUN move tag's on-hit effect: stun every non-stunned action
     * segment of the defender that is on the current tick — both the segment
     * currently being occupied and any segment firing this tick (the "moves
     * second" case).
     *
     * <p>This sweeps the defender's (merged legacy) timeline, which already
     * flattens both the offensive and defensive boards, so both are covered.
     *
     * <p>Segments whose move is HEAVY are immune and are skipped — heavy moves
     * cannot be stunned by a STUN-tagged hit. Interrupts are unaffected.
     *
     * <p>No special handling is needed for already-fired segments: stunning them
     * is a harmless no-op for the rest of this tick.
     */
    private void resolveStunTag(
        BattleCombatant   defender,
        int               tick,
        List<CombatEvent> events
    ) {
        Timeline defenderTimeline = defender.getTimeline();
        if (defenderTimeline == null) return;

        boolean stunnedAny = false;
        for (ActionSegment segment : defenderTimeline.getSegments()) {
            if (segment.isStunned()) continue;
            if (segment.getMove().isHeavy()) continue; // HEAVY moves resist the stun tag
            boolean onCurrentTick =
                (tick >= segment.getStartTick() && tick <= segment.getEndTick())
                || segment.getFireTick() == tick;
            if (onCurrentTick) {
                segment.stun();
                stunnedAny = true;
            }
        }

        if (stunnedAny) {
            events.add(CombatEvent.of(CombatEvent.Type.MOVE_STUNNED)
                .target(defender)
                .tick(tick)
                .message(defender.getCharacter().getName()
                         + " was stunned and could not move.")
                .build());
        }
    }

    // -------------------------------------------------------------------------
    // Defensive move resolution
    // -------------------------------------------------------------------------

    private void resolveDefensiveMove(BattleCombatant combatant, Move move, int tick, List<CombatEvent> events) {
        String msg = move.blockActivationMessage(combatant.getCharacter().getName());
        if (msg != null) {
            events.add(CombatEvent.of(CombatEvent.Type.STATUS_APPLIED)
                .source(combatant).move(move)
                .tick(tick)
                .message(msg)
                .build());
        }
        applySelfEffects(combatant, move, tick, events);
    }

    // -------------------------------------------------------------------------
    // Status effect application
    // -------------------------------------------------------------------------

    private void applyOnHitEffects(
        BattleCombatant attacker,
        BattleCombatant defender,
        Move            move,
        int             tick,
        List<CombatEvent> events
    ) {
        for (StatusEffect effect : move.getOnHitEffects()) {
            defender.addStatusEffect(effect);
            events.add(CombatEvent.of(CombatEvent.Type.STATUS_APPLIED)
                .source(attacker).target(defender).move(move)
                .tick(tick)
                .message(defender.getCharacter().getName()
                         + " is afflicted with " + effect.getType() + "!")
                .build());
        }
    }

    private void applySelfEffects(BattleCombatant combatant, Move move, int tick, List<CombatEvent> events) {
        for (StatusEffect effect : move.getSelfEffects()) {
            combatant.addStatusEffect(effect);
            events.add(CombatEvent.of(CombatEvent.Type.STATUS_APPLIED)
                .source(combatant).move(move)
                .tick(tick)
                .message(combatant.getCharacter().getName()
                         + " applies " + effect.getType() + " to themselves!")
                .build());
        }
    }

    // -------------------------------------------------------------------------
    // Interrupt resolution
    // -------------------------------------------------------------------------

    private void resolveInterrupt(
        BattleCombatant   attacker,
        BattleCombatant   defender,
        Move              move,
        int               tick,
        List<CombatEvent> events
    ) {
        Timeline defenderTimeline = defender.getTimeline();
        if (defenderTimeline == null) return;

        ActionSegment targetSegment = move.resolveInterruptOn(tick, defenderTimeline);

        if (targetSegment != null) {
            events.add(CombatEvent.of(CombatEvent.Type.MOVE_STUNNED)
                .source(attacker).target(defender).move(targetSegment.getMove())
                .tick(tick)
                .message(attacker.getCharacter().getName() + "'s " + move.getName()
                          + " stuns " + defender.getCharacter().getName()
                         + ", interrupting " + targetSegment.getMove().getName() + "!")
                .build());
        }
    }

    // -------------------------------------------------------------------------
    // Round end processing
    // -------------------------------------------------------------------------

    /**
     * Process end-of-round: tick status effects, expire BFS, clear round buffs.
     */
    public List<CombatEvent> processRoundEnd(BattleState state) {
        List<CombatEvent> events = new ArrayList<>();
        int round = state.getRoundNumber();

        for (BattleCombatant combatant : new BattleCombatant[]{ state.getPlayerCombatant(), state.getEnemyCombatant() }) {
            combatant.tickStatusEffects();
            combatant.clearBlock();

            boolean wasBfs = combatant.isInBlackFlashState();
            combatant.tickBfsExpiry(round);
            if (wasBfs && !combatant.isInBlackFlashState()) {
                events.add(CombatEvent.of(CombatEvent.Type.BFS_EXPIRED)
                    .source(combatant)
                    .message(combatant.getCharacter().getName() + "'s Black Flash State has ended.")
                    .build());
            }
        }

        state.endRound();
        events.add(CombatEvent.of(CombatEvent.Type.ROUND_END)
            .message("--- Round " + round + " complete. Starting Round " + state.getRoundNumber() + " ---")
            .build());

        return events;
    }
}
