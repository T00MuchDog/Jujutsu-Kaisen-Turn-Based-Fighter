package com.jjktbf.model.combat;

import com.jjktbf.model.character.CombatStats;
import com.jjktbf.model.character.AbilityEffectData;
import com.jjktbf.model.character.AbilityEffectTarget;
import com.jjktbf.model.character.AbilityEffectTiming;
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
 *   - Identical Speed: random resolution using stable precomputed tie keys.
 *
 * All effects are reported as CombatEvents collected in a list.
 * The resolver never touches I/O — events are returned to the controller.
 */
public class CombatResolver {

    private final RandomSource rng;
    private final PassiveAbilityEngine passiveAbilities;

    public CombatResolver(RandomSource rng) {
        this.rng = rng;
        this.passiveAbilities = new PassiveAbilityEngine(rng);
    }

    /** Compatibility constructor for callers that still supply {@link Random}. */
    public CombatResolver(Random rng) {
        this(new SeededRandomSource(rng));
    }

    public CombatResolver() {
        this(new SeededRandomSource());
    }

    // -------------------------------------------------------------------------
    // Round planning helpers
    // -------------------------------------------------------------------------

    /**
     * Compute the actual CE cost for a move for a given combatant
     * and verify the combatant has enough CE. Returns -1 if CE is insufficient.
     */
    public int computeCostIfAffordable(BattleCombatant combatant, Move move) {
        int cost = combatant.computeMoveCeCost(move);
        return (combatant.getCurrentCe() >= cost) ? cost : -1;
    }

    /** Charge passive per-round CE costs before either side plans the round. */
    public List<CombatEvent> processRoundStart(BattleState state) {
        List<CombatEvent> events = new ArrayList<>();
        boolean battleStart = state.getPlayerCombatant().beginPassiveFightStart()
            | state.getEnemyCombatant().beginPassiveFightStart();
        if (battleStart) {
            events.addAll(passiveAbilities.process(
                state, AbilityTrigger.simple(AbilityTrigger.Type.BATTLE_START)));
            if (finishBattleIfNeeded(state, events, 0)) return events;
        }
        boolean roundStart = state.getPlayerCombatant().beginPassiveRoundStart(state.getRoundNumber())
            | state.getEnemyCombatant().beginPassiveRoundStart(state.getRoundNumber());
        if (roundStart) {
            appendAutomaticStatusEvents(state, events);
            if (finishBattleIfNeeded(state, events, 0)) return events;
            events.addAll(passiveAbilities.process(state, AbilityTrigger.simple(AbilityTrigger.Type.ROUND_START)));
            if (finishBattleIfNeeded(state, events, 0)) return events;
            events.addAll(passiveAbilities.process(state, AbilityTrigger.phase(BattleState.Phase.PLANNING)));
            if (finishBattleIfNeeded(state, events, 0)) return events;
            applyPoisonDamage(state, events);
            if (finishBattleIfNeeded(state, events, 0)) return events;
        }
        drainRoundAbilityCost(state, state.getPlayerCombatant(), state.getRoundNumber(), events);
        if (finishBattleIfNeeded(state, events, 0)) return events;
        drainRoundAbilityCost(state, state.getEnemyCombatant(), state.getRoundNumber(), events);
        finishBattleIfNeeded(state, events, 0);
        return events;
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
        events.addAll(beginResolution(state));
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
        boolean roundCostsProcessed;
        /**
         * Block segments currently inside their defensive AP window, keyed by
         * identity and mapped to their owning combatant. Carried tick to tick so
         * the resolver can detect the active→inactive transition (a defensive
         * move "running out") and log it exactly once per block — whether it ends
         * naturally or is broken/stunned mid-window.
         */
        final Map<ActionSegment, BattleCombatant> activeBlocks = new IdentityHashMap<>();
    }

    private final ThreadLocal<ResolutionCursor> cursor = ThreadLocal.withInitial(ResolutionCursor::new);

    /**
     * Prepare a resolution sweep. Processes round-start ability costs once and records the tick
     * range to sweep. Must be called before {@link #resolveTick(BattleState)}.
     */
    public List<CombatEvent> beginResolution(BattleState state) {
        List<CombatEvent> events = new ArrayList<>(processRoundStart(state));
        if (state.isBattleOver()) {
            cursor.get().roundCostsProcessed = false;
            return events;
        }
        BattleCombatant player = state.getPlayerCombatant();
        BattleCombatant enemy  = state.getEnemyCombatant();

        Timeline playerTimeline = player.getTimeline();
        Timeline enemyTimeline  = enemy.getTimeline();

        // The round ends once the last placed segment finishes: sweep only as
        // many ticks as the latest segment's AP window actually needs, rather
        // than always running out to the full grid length.
        int maxTick = 0;
        if (playerTimeline != null) {
            for (ActionSegment s : playerTimeline.getSegments()) {
                maxTick = Math.max(maxTick, s.getEndTick());
            }
        }
        if (enemyTimeline != null) {
            for (ActionSegment s : enemyTimeline.getSegments()) {
                maxTick = Math.max(maxTick, s.getEndTick());
            }
        }

        ResolutionCursor c = cursor.get();
        c.tick = 0;
        c.maxTick = maxTick;
        c.roundCostsProcessed = true;
        c.activeBlocks.clear();

        events.addAll(passiveAbilities.process(state, AbilityTrigger.phase(BattleState.Phase.RESOLUTION)));
        finishBattleIfNeeded(state, events, 0);
        return events;
    }

    /** True while there are still ticks left to resolve in the current sweep. */
    public boolean hasMoreTicks() {
        ResolutionCursor c = cursor.get();
        return c.roundCostsProcessed && c.tick < c.maxTick;
    }

    /**
     * Advance the action counter by one tick and resolve everything that fires
     * on it. Returns the events produced by this tick only (empty if nothing
     * happened). Round-start ability costs are guarded against duplicate charging.
     */
    public List<CombatEvent> resolveTick(BattleState state) {
        ResolutionCursor c = cursor.get();
        if (!c.roundCostsProcessed || c.tick >= c.maxTick) return List.of();

        c.tick++;
        int tick = c.tick;
        List<CombatEvent> events = new ArrayList<>();

        BattleCombatant player = state.getPlayerCombatant();
        BattleCombatant enemy  = state.getEnemyCombatant();

        state.advanceTick();

        events.addAll(passiveAbilities.process(state, AbilityTrigger.tick(tick)));
        if (finishBattleIfNeeded(state, events, tick)) return events;

        // --- CE drain when a segment starts ---
        drainCeForStartingSegments(state, player, tick, events);
        if (finishBattleIfNeeded(state, events, tick)) return events;
        drainCeForStartingSegments(state, enemy,  tick, events);
        if (finishBattleIfNeeded(state, events, tick)) return events;

        // --- Collect all moves firing this tick ---
        List<FiringEntry> firing = collectFiringMoves(player, enemy, tick);

        // --- Sort by priority ---
        sortFiringEntries(firing);

        // --- Resolve each firing move ---
        for (FiringEntry entry : firing) {
            if (entry.segment.isStunned()) continue;
            if (finishBattleIfNeeded(state, events, tick)) return events;
            resolveMove(entry, player, enemy, state, tick, events);
            if (finishBattleIfNeeded(state, events, tick)) return events;
        }

        // --- Detect defensive blocks whose AP window just ended (active → inactive) ---
        detectExpiredBlocks(player, tick, events);
        detectExpiredBlocks(enemy,  tick, events);

        return events;
    }

    // -------------------------------------------------------------------------
    // CE draining
    // -------------------------------------------------------------------------

    private void drainCeForStartingSegments(
        BattleState state,
        BattleCombatant combatant,
        int tick,
        List<CombatEvent> events
    ) {
        Timeline tl = combatant.getTimeline();
        if (tl == null) return;

        for (ActionSegment segment : tl.getSegments()) {
            if (segment.isStunned()) continue;
            if (segment.getStartTick() == tick) {
                if (combatant.consumeMoveCancellation()) {
                    segment.stun();
                    events.add(CombatEvent.of(CombatEvent.Type.MOVE_STUNNED)
                        .target(combatant).move(segment.getMove()).tick(tick)
                        .message(combatant.getCharacter().getName() + "'s "
                            + segment.getMove().getName() + " was cancelled by an ability!")
                        .build());
                    continue;
                }
                if (segment.getActualCeCost() <= 0) continue;
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
                events.addAll(passiveAbilities.process(state, AbilityTrigger.amount(
                    AbilityTrigger.Type.CE_SPENT, combatant, null, drained, tick)));
                if (finishBattleIfNeeded(state, events, tick)) return;

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

    private void drainRoundAbilityCost(
        BattleState state,
        BattleCombatant combatant,
        int roundNumber,
        List<CombatEvent> events
    ) {
        if (!combatant.beginAbilityRoundCost(roundNumber)) return;
        int cost = combatant.getAbilityFlags().ceCostPerRound;
        if (cost <= 0) return;
        int drained = combatant.drainCe(cost);
        events.add(CombatEvent.of(CombatEvent.Type.CE_DRAINED)
            .source(combatant)
            .intValue(drained)
            .message(combatant.getCharacter().getName() + " spends " + drained + " CE on passive abilities.")
            .build());
        if (drained > 0) {
            events.addAll(passiveAbilities.process(state, AbilityTrigger.amount(
                AbilityTrigger.Type.CE_SPENT, combatant, null, drained, 0)));
        }
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

    private record TieBreak(double randomKey, int insertionOrder) {}

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
     *  3. Precomputed random tiebreak
     *  4. Original order if random keys collide
     */
    private void sortFiringEntries(List<FiringEntry> firing) {
        firing.sort(this::comparePriority);

        int groupStart = 0;
        while (groupStart < firing.size()) {
            int groupEnd = groupStart + 1;
            while (groupEnd < firing.size()
                && comparePriority(firing.get(groupStart), firing.get(groupEnd)) == 0) {
                groupEnd++;
            }
            if (groupEnd - groupStart > 1) {
                Map<FiringEntry, TieBreak> tieBreaks = new IdentityHashMap<>();
                for (int index = groupStart; index < groupEnd; index++) {
                    tieBreaks.put(
                        firing.get(index),
                        new TieBreak(rng.nextDouble(), index - groupStart)
                    );
                }
                firing.subList(groupStart, groupEnd).sort((a, b) -> {
                    TieBreak aTieBreak = tieBreaks.get(a);
                    TieBreak bTieBreak = tieBreaks.get(b);
                    int randomComparison = Double.compare(
                        aTieBreak.randomKey(), bTieBreak.randomKey());
                    return randomComparison != 0
                        ? randomComparison
                        : Integer.compare(
                            aTieBreak.insertionOrder(), bTieBreak.insertionOrder());
                });
            }
            groupStart = groupEnd;
        }
    }

    private int comparePriority(FiringEntry a, FiringEntry b) {
        int instantComparison = Boolean.compare(b.segment.isInstant(), a.segment.isInstant());
        if (instantComparison != 0) return instantComparison;
        int aSpeed = a.attacker.getEffectiveStats().getSpeed();
        int bSpeed = b.attacker.getEffectiveStats().getSpeed();
        return Integer.compare(bSpeed, aSpeed);
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

        // This segment's move is now actually executing. Recording it as fired
        // makes it immune to retro-stunning for the rest of the round — a stun
        // or interrupt landing later this tick (or a later tick still inside a
        // block's window) can't cancel a move whose effects are already in play.
        segment.markFired();

        events.add(CombatEvent.of(CombatEvent.Type.MOVE_FIRED)
            .source(attacker)
            .move(move)
            .tick(tick)
            .message(attacker.getCharacter().getName() + " unleashes " + move.getName() + "!")
            .build());
        events.addAll(passiveAbilities.process(state, AbilityTrigger.move(
            AbilityTrigger.Type.MOVE_USED, attacker, defender, move, tick)));
        if (finishBattleIfNeeded(state, events, tick)) return;

        // --- Self-effects apply on unleash, for every move type (damaging,
        // defensive, and utility alike). A move that buffs its user when cast
        // (e.g. a CE strike that raises Power) fires the buff here, regardless
        // of whether the attack later hits, misses, or is blocked.
        applySelfEffects(state, attacker, move, tick, events);
        if (finishBattleIfNeeded(state, events, tick)) return;
        events.addAll(attacker.getCodedAbilities().onMoveUnleashed(state, move, tick));
        if (finishBattleIfNeeded(state, events, tick)) return;

        // --- Defensive moves: apply buff or register full block ---
        if (move.isDefensive()) {
            resolveDefensiveMove(attacker, move, tick, events);
            return; // defensive moves don't attack
        }

        // --- Non-damaging utility moves ---
        if (move.getCategory() == MoveCategory.UTILITY) {
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
            events.addAll(passiveAbilities.process(state, AbilityTrigger.move(
                AbilityTrigger.Type.ATTACK_MISSED, attacker, defender, move, tick)));
            finishBattleIfNeeded(state, events, tick);

        } else if (result.isBlocked()) {
            events.add(CombatEvent.of(CombatEvent.Type.MOVE_BLOCKED)
                .source(attacker).target(defender).move(move)
                .tick(tick)
                .message(defender.getCharacter().getName() + " blocked " + move.getName() + "!")
                .build());
            events.addAll(passiveAbilities.process(state, AbilityTrigger.move(
                AbilityTrigger.Type.MOVE_BLOCKED, attacker, defender, move, tick)));
            finishBattleIfNeeded(state, events, tick);

        } else {
            // Hit — check whether a block softened it.
            // GUARD_BREAK moves ignore blocking defensive moves entirely.
            boolean wasBlocked = !move.isGuardBreak()
                && defender.getTimeline() != null
                && defender.getTimeline().activeBlockAt(tick, move) != null;

            int appliedDamage = defender.receiveDamage(result.getFinalDamage());
            appendConsumedStatusEvents(state, defender, tick, events);
            events.addAll(defender.getCodedAbilities().drainPendingEvents(tick));

            if (wasBlocked) {
                events.add(CombatEvent.of(CombatEvent.Type.MOVE_BLOCK_REDUCED)
                    .source(attacker).target(defender).move(move)
                    .tick(tick)
                    .message(defender.getCharacter().getName()
                             + " blocked " + move.getName() + "! (damage reduced)")
                    .build());
            }

            events.add(CombatEvent.of(appliedDamage == 0
                    ? CombatEvent.Type.DAMAGE_IGNORED : CombatEvent.Type.DAMAGE_DEALT)
                .source(attacker).target(defender).move(move)
                .intValue(appliedDamage)
                .tick(tick)
                .message(appliedDamage == 0
                    ? defender.getCharacter().getName() + " ignores all damage from " + move.getName() + "!"
                    : attacker.getCharacter().getName() + "'s " + move.getName()
                        + " hits " + defender.getCharacter().getName()
                        + " for " + appliedDamage + " damage!")
                .build());
            events.addAll(passiveAbilities.process(state, AbilityTrigger.move(
                AbilityTrigger.Type.ATTACK_HIT, attacker, defender, move, tick)));
            if (appliedDamage > 0) {
                events.addAll(passiveAbilities.process(state, AbilityTrigger.amount(
                    AbilityTrigger.Type.DAMAGE, attacker, defender, appliedDamage, tick)));
            }
            // Black Flash
            if (result.isBlackFlash()) {
                int requestedCe = (int) Math.round(
                    attacker.getMaxCursedEnergy() * CombatStats.BF_CE_RESTORE_FRACTION);
                int ceRestored = attacker.restoreCe(requestedCe);
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
                events.addAll(passiveAbilities.process(state, AbilityTrigger.move(
                    AbilityTrigger.Type.BLACK_FLASH, attacker, defender, move, tick)));
                if (ceRestored > 0) {
                    events.addAll(passiveAbilities.process(state, AbilityTrigger.amount(
                        AbilityTrigger.Type.CE_RESTORED, attacker, null, ceRestored, tick)));
                }
            }

            // A lethal hit still completes its intrinsic Black Flash effects.
            // Stop before applying statuses/interrupts to a defeated target.
            if (finishBattleIfNeeded(state, events, tick)) return;

            // On-hit status effects
            applyOnHitEffects(state, attacker, defender, move, tick, events);
            applyAbilityOnHitEffects(state, attacker, defender, move, tick, events);
            if (finishBattleIfNeeded(state, events, tick)) return;

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
     * <p>Segments that have already fired are skipped: a stun stops a move from
     * occurring, it does not deactivate one whose effects are already in play.
     * In particular a defensive block that already fired keeps protecting for
     * the rest of its AP window and cannot be cancelled by a stun landing on the
     * same tick. (The {@link ActionSegment#stun()} choke-point guards this too,
     * but filtering here keeps the "was stunned and could not move" event from
     * firing spuriously for already-resolved moves.)
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
            if (segment.hasFired()) continue;        // can't un-fire an already-resolved move
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
        // Self-effects are applied by the caller (resolveMove) on unleash for
        // all move types, so are not re-applied here.
    }

    /**
     * Detect defensive blocks on this combatant's timeline whose AP window has
     * just ended, and log one "drops their guard" expiry per block.
     *
     * <p>A block is tracked the moment it enters its window and logged once when
     * it leaves — whether naturally (the counter passed the window's end tick)
     * or because the segment was stunned/broken out from under it. The end-tick
     * math mirrors {@link Timeline#activeBlockAt} so the two never disagree about
     * when a block is protective.
     *
     * <p>Stunned blocks are considered ended: once interrupted, a defensive move
     * is no longer protecting its user, so an expiry line correctly reflects that
     * their guard is down for the rest of the round.
     */
    private void detectExpiredBlocks(BattleCombatant combatant, int tick, List<CombatEvent> events) {
        Timeline tl = combatant.getTimeline();
        if (tl == null) {
            // No timeline means nothing to track; clear any stale carry so a
            // prior round's blocks can't resurface as spurious expiries.
            cursor.get().activeBlocks.entrySet().removeIf(e -> e.getValue() == combatant);
            return;
        }

        int gridLength = tl.getGridLength();

        // First pass: note every block that is STILL active this tick. This both
        // refreshes the carry and tells us which previously-tracked blocks fell out.
        IdentityHashMap<ActionSegment, Boolean> stillActive = new IdentityHashMap<>();
        for (ActionSegment segment : tl.getSegments()) {
            if (!segment.getMove().isActiveBlock()) continue;
            int start = segment.getFireTick();
            int end = blockWindowEnd(segment.getMove(), start, gridLength);
            boolean activeNow = !segment.isStunned() && tick >= start && tick <= end;
            if (activeNow) stillActive.put(segment, Boolean.TRUE);
        }

        ResolutionCursor c = cursor.get();
        // Expire: previously tracked, no longer active this tick.
        Iterator<Map.Entry<ActionSegment, BattleCombatant>> tracked = c.activeBlocks.entrySet().iterator();
        while (tracked.hasNext()) {
            Map.Entry<ActionSegment, BattleCombatant> entry = tracked.next();
            if (entry.getValue() != combatant) continue;
            if (stillActive.containsKey(entry.getKey())) continue; // still up — leave it tracked

            Move move = entry.getKey().getMove();
            String msg = move.blockExpiryMessage(combatant.getCharacter().getName());
            tracked.remove();
            if (msg != null) {
                events.add(CombatEvent.of(CombatEvent.Type.STATUS_EXPIRED)
                    .source(combatant).move(move)
                    .tick(tick)
                    .message(msg)
                    .build());
            }
        }

        // Register: newly active blocks that weren't tracked before.
        for (ActionSegment segment : stillActive.keySet()) {
            if (!c.activeBlocks.containsKey(segment)) {
                c.activeBlocks.put(segment, combatant);
            }
        }
    }

    /**
     * End tick of a block's defensive window, mirroring the computation in
     * {@link Timeline#activeBlockAt}: {@code -1} lasts the whole grid, {@code 0}
     * uses the move's AP width, otherwise the explicit duration from the fire tick.
     */
    private static int blockWindowEnd(Move move, int fireTick, int gridLength) {
        return switch (move.getBlockDuration()) {
            case -1 -> gridLength;
            case 0  -> fireTick + move.getApCost() - 1;
            default -> fireTick + move.getBlockDuration() - 1;
        };
    }

    /**
     * Log and clear any blocks for this combatant still tracked as active at the
     * end of the round. Called from {@link #processRoundEnd} after the tick sweep
     * so a round-long block (blockDuration -1) or one whose window out-ran the
     * sweep still gets its "drops their guard" line exactly once.
     */
    private void flushRemainingBlocks(BattleCombatant combatant, List<CombatEvent> events) {
        ResolutionCursor c = cursor.get();
        if (c.activeBlocks.isEmpty()) return;
        Iterator<Map.Entry<ActionSegment, BattleCombatant>> tracked = c.activeBlocks.entrySet().iterator();
        while (tracked.hasNext()) {
            Map.Entry<ActionSegment, BattleCombatant> entry = tracked.next();
            if (entry.getValue() != combatant) continue;
            Move move = entry.getKey().getMove();
            String msg = move.blockExpiryMessage(combatant.getCharacter().getName());
            tracked.remove();
            if (msg != null) {
                events.add(CombatEvent.of(CombatEvent.Type.STATUS_EXPIRED)
                    .source(combatant).move(move)
                    .message(msg)
                    .build());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Status effect application
    // -------------------------------------------------------------------------

    private void applyOnHitEffects(
        BattleState state,
        BattleCombatant attacker,
        BattleCombatant defender,
        Move            move,
        int             tick,
        List<CombatEvent> events
    ) {
        for (StatusEffect effect : move.getOnHitEffects()) {
            defender.addStatusEffect(effect, state.getCurrentPhase());
            events.add(CombatEvent.of(CombatEvent.Type.STATUS_APPLIED)
                .source(attacker).target(defender).move(move)
                .tick(tick)
                .message(defender.getCharacter().getName()
                         + " is afflicted with " + effect.getType() + "!")
                .build());
            events.addAll(passiveAbilities.process(state, AbilityTrigger.status(
                AbilityTrigger.Type.STATUS_APPLIED, defender, effect.getType(), tick)));
        }
    }

    private void applySelfEffects(
        BattleState state,
        BattleCombatant combatant,
        Move move,
        int tick,
        List<CombatEvent> events
    ) {
        for (StatusEffect effect : move.getSelfEffects()) {
            combatant.addStatusEffect(effect, state.getCurrentPhase());
            events.add(CombatEvent.of(CombatEvent.Type.STATUS_APPLIED)
                .source(combatant).move(move)
                .tick(tick)
                .message(combatant.getCharacter().getName()
                         + " applies " + effect.getType() + " to themselves!")
                .build());
            events.addAll(passiveAbilities.process(state, AbilityTrigger.status(
                AbilityTrigger.Type.STATUS_APPLIED, combatant, effect.getType(), tick)));
        }
    }

    private void applyAbilityOnHitEffects(
        BattleState state,
        BattleCombatant attacker,
        BattleCombatant defender,
        Move move,
        int tick,
        List<CombatEvent> events
    ) {
        for (AbilityEffectData effect : attacker.getAbilityFlags().autoStatusEffects) {
            if (!AbilityEffectTiming.ON_HIT.name().equals(effect.timing)) continue;
            List<BattleCombatant> targets = AbilityEffectTarget.BOTH.name().equals(effect.target)
                ? List.of(attacker, defender)
                : List.of(AbilityEffectTarget.ENEMY.name().equals(effect.target) ? defender : attacker);
            for (BattleCombatant target : targets) {
                if (!target.addAutomaticStatusEffect(effect, state.getCurrentPhase())) continue;
                events.add(CombatEvent.of(CombatEvent.Type.STATUS_APPLIED)
                    .source(attacker).target(target).move(move)
                    .tick(tick)
                    .message(target.getCharacter().getName()
                        + " receives " + effect.stringValue + " from an ability!")
                    .build());
                try {
                    events.addAll(passiveAbilities.process(state, AbilityTrigger.status(
                        AbilityTrigger.Type.STATUS_APPLIED,
                        target,
                        StatusEffectType.valueOf(effect.stringValue),
                        tick)));
                } catch (IllegalArgumentException ignored) { }
            }
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
        Map<BattleCombatant, List<StatusEffect>> expiredByCombatant = new LinkedHashMap<>();

        events.addAll(passiveAbilities.process(
            state, AbilityTrigger.phase(BattleState.Phase.ROUND_END)));
        if (finishBattleIfNeeded(state, events, 0)) return events;

        for (BattleCombatant combatant : new BattleCombatant[]{ state.getPlayerCombatant(), state.getEnemyCombatant() }) {
            int previousMaxHp = combatant.getMaxHp();
            int previousMaxCe = combatant.getMaxCursedEnergy();
            combatant.tickRuntimeAbilityEffects(round);
            appendResourceMaximumEvents(combatant, previousMaxHp, previousMaxCe, events);
            combatant.tickStatusEffects();
            expiredByCombatant.put(combatant, combatant.drainExpiredStatusEffects());
            flushRemainingBlocks(combatant, events);

            boolean wasBfs = combatant.isInBlackFlashState();
            combatant.tickBfsExpiry(round);
            if (wasBfs && !combatant.isInBlackFlashState()) {
                events.add(CombatEvent.of(CombatEvent.Type.BFS_EXPIRED)
                    .source(combatant)
                    .message(combatant.getCharacter().getName() + "'s Black Flash State has ended.")
                    .build());
            }
        }
        state.markRoundEndMaintenanceComplete();

        // Dispatch expiry predicates only after both sides have ticked, so an
        // effect granted to the second combatant cannot immediately lose a round.
        for (Map.Entry<BattleCombatant, List<StatusEffect>> entry : expiredByCombatant.entrySet()) {
            BattleCombatant combatant = entry.getKey();
            // Log every stat-boost / status effect that expired during this
            // round-end tick. Each gets its own flavour line so a fading power
            // surge reads differently from a guard dropping (which is handled at
            // the tick the block's AP window ends, not here).
            for (StatusEffect expired : entry.getValue()) {
                events.add(CombatEvent.of(CombatEvent.Type.STATUS_EXPIRED)
                    .source(combatant)
                    .message(StatusEffectMessages.expiryMessage(
                        combatant.getCharacter().getName(), expired.getType()))
                    .build());
                events.addAll(passiveAbilities.process(state, AbilityTrigger.status(
                    AbilityTrigger.Type.STATUS_REMOVED, combatant, expired.getType(), 0)));
                if (finishBattleIfNeeded(state, events, 0)) return events;
            }
        }

        state.endRound();
        events.add(CombatEvent.of(CombatEvent.Type.ROUND_END)
            .message("--- Round " + round + " complete. Starting Round " + state.getRoundNumber() + " ---")
            .build());

        return events;
    }

    private static boolean finishBattleIfNeeded(
        BattleState state,
        List<CombatEvent> events,
        int tick
    ) {
        if (!state.checkAndResolveBattleOver()) return false;
        if (events.stream().noneMatch(event -> event.getType() == CombatEvent.Type.BATTLE_OVER)) {
            String message = state.getWinner() == null
                ? "The battle ends in a draw!"
                : state.getWinner().getCharacter().getName() + " wins the battle!";
            events.add(CombatEvent.of(CombatEvent.Type.BATTLE_OVER)
                .source(state.getWinner()).tick(tick).message(message).build());
        }
        return true;
    }

    private void appendAutomaticStatusEvents(BattleState state, List<CombatEvent> events) {
        for (BattleState.AutomaticStatusApplication application
            : state.drainAutomaticStatusApplications()) {
            events.add(CombatEvent.of(CombatEvent.Type.STATUS_APPLIED)
                .source(application.source())
                .target(application.target())
                .message(application.target().getCharacter().getName()
                    + " receives " + application.status() + " from an ability!")
                .build());
            events.addAll(passiveAbilities.process(state, AbilityTrigger.status(
                AbilityTrigger.Type.STATUS_APPLIED,
                application.target(),
                application.status(),
                0)));
            if (state.checkAndResolveBattleOver()) break;
        }
    }

    private void applyPoisonDamage(BattleState state, List<CombatEvent> events) {
        Map<BattleCombatant, Integer> poisonByCombatant = new LinkedHashMap<>();
        for (BattleCombatant combatant : new BattleCombatant[]{
            state.getPlayerCombatant(), state.getEnemyCombatant() }) {
            int poison = Math.max(0, (int) Math.round(
                combatant.getStatusMagnitude(StatusEffectType.POISON)));
            if (poison > 0) poisonByCombatant.put(combatant, poison);
        }
        for (Map.Entry<BattleCombatant, Integer> entry : poisonByCombatant.entrySet()) {
            BattleCombatant target = entry.getKey();
            int damage = target.receiveDamage(entry.getValue());
            appendConsumedStatusEvents(state, target, 0, events);
            events.addAll(target.getCodedAbilities().drainPendingEvents(0));
            events.add(CombatEvent.of(damage == 0
                    ? CombatEvent.Type.DAMAGE_IGNORED : CombatEvent.Type.DAMAGE_DEALT)
                .target(target).intValue(damage)
                .message(damage == 0
                    ? target.getCharacter().getName() + " ignores poison damage!"
                    : target.getCharacter().getName() + " takes " + damage + " poison damage!")
                .build());
            if (damage > 0) {
                events.addAll(passiveAbilities.process(state, AbilityTrigger.amount(
                    AbilityTrigger.Type.DAMAGE, null, target, damage, 0)));
            }
        }
    }

    private void appendConsumedStatusEvents(
        BattleState state,
        BattleCombatant target,
        int tick,
        List<CombatEvent> events
    ) {
        for (StatusEffect consumed : target.drainConsumedStatusEffects()) {
            events.add(CombatEvent.of(CombatEvent.Type.STATUS_EXPIRED)
                .target(target).tick(tick)
                .message(StatusEffectMessages.expiryMessage(
                    target.getCharacter().getName(), consumed.getType()))
                .build());
            events.addAll(passiveAbilities.process(state, AbilityTrigger.status(
                AbilityTrigger.Type.STATUS_REMOVED, target, consumed.getType(), tick)));
        }
    }

    private static void appendResourceMaximumEvents(
        BattleCombatant combatant,
        int previousMaxHp,
        int previousMaxCe,
        List<CombatEvent> events
    ) {
        if (combatant.getMaxHp() != previousMaxHp) {
            events.add(CombatEvent.of(CombatEvent.Type.MAX_HP_CHANGED)
                .target(combatant).intValue(combatant.getMaxHp())
                .message(combatant.getCharacter().getName() + "'s max HP is now "
                    + combatant.getMaxHp() + ".").build());
        }
        if (combatant.getMaxCursedEnergy() != previousMaxCe) {
            events.add(CombatEvent.of(CombatEvent.Type.MAX_CE_CHANGED)
                .target(combatant).intValue(combatant.getMaxCursedEnergy())
                .message(combatant.getCharacter().getName() + "'s max CE is now "
                    + combatant.getMaxCursedEnergy() + ".").build());
        }
    }
}
