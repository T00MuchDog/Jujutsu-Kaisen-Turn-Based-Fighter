package com.jjktbf.model.combat;

import com.jjktbf.model.character.CombatStats;
import com.jjktbf.model.character.AbilityEffectData;
import com.jjktbf.model.character.AbilityEffectTarget;
import com.jjktbf.model.character.StatKey;
import com.jjktbf.model.character.AbilityEffectTiming;
import com.jjktbf.model.character.coded.CodedMoveResponse;
import com.jjktbf.model.character.coded.CursedSpeechAbility;
import com.jjktbf.model.move.*;
import com.jjktbf.model.progression.TechniqueMasteryResolver;

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
    private final AbilityActivationEngine abilityActivations;
    /**
     * Lookup used to materialize summoned shikigami. May be null (summons are
     * then enqueued but not materialized — useful for tests that don't summon).
     */
    private BattleCharacterLookup summonLookup;

    public CombatResolver(RandomSource rng) {
        this(rng, null);
    }

    public CombatResolver(RandomSource rng, BattleCharacterLookup summonLookup) {
        this.rng = rng;
        this.abilityActivations = new AbilityActivationEngine(rng, summonLookup);
        this.summonLookup = summonLookup;
    }

    /**
     * Inject the character lookup used to resolve summon character ids at runtime,
     * so the engine can materialize shikigami without loading files itself.
     */
    public CombatResolver withSummonLookup(BattleCharacterLookup lookup) {
        this.summonLookup = lookup;
        this.abilityActivations.withCharacterLookup(lookup);
        return this;
    }

    /** Character-definition lookup shared by summon and transformation effects. */
    public CombatResolver withCharacterLookup(BattleCharacterLookup lookup) {
        return withSummonLookup(lookup);
    }

    /** Compatibility constructor for callers that still supply {@link Random}. */
    public CombatResolver(Random rng) {
        this(new SeededRandomSource(rng));
    }

    /** Compatibility constructor with an injected summon lookup. */
    public CombatResolver(Random rng, BattleCharacterLookup summonLookup) {
        this(new SeededRandomSource(rng), summonLookup);
    }

    public CombatResolver(BattleCharacterLookup summonLookup) {
        this(new SeededRandomSource(), summonLookup);
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

    /** Authoritative entry point for a player-requested manual ability activation. */
    public List<CombatEvent> activateAbilityManually(
        BattleState state,
        BattleCombatant owner,
        String abilityId,
        int tick
    ) {
        if (state == null || owner == null || abilityId == null
            || state.getCurrentPhase() != BattleState.Phase.PLANNING
            || state.isBattleOver()
            || state.teamOf(owner) == null) {
            return List.of();
        }
        List<CombatEvent> events = new ArrayList<>(abilityActivations.process(
            state, AbilityTrigger.manual(owner, abilityId, tick)));
        finishBattleIfNeeded(state, events, tick);
        return events;
    }

    /** Charge passive per-round CE costs before either side plans the round. */
    public List<CombatEvent> processRoundStart(BattleState state) {
        List<CombatEvent> events = new ArrayList<>();
        // BattleState applies queued automatic statuses before this method. Emit
        // those mutations before any BATTLE_START ability can change the same values.
        appendAutomaticStatusEvents(state, events);
        if (finishBattleIfNeeded(state, events, 0)) return events;
        if (processPendingBattleStarts(state, events)) return events;
        boolean roundStart = false;
        while (true) {
            BattleCombatant entrant = null;
            for (BattleCombatant combatant : state.activeCombatants()) {
                if (combatant.beginAbilityRoundStart(state.getRoundNumber())) {
                    entrant = combatant;
                    break;
                }
            }
            if (entrant == null) break;
            roundStart = true;
            events.addAll(abilityActivations.process(
                state, AbilityTrigger.roundStart(entrant)));
            if (finishBattleIfNeeded(state, events, 0)) return events;
            if (processPendingBattleStarts(state, events)) return events;
        }
        if (roundStart) {
            events.addAll(abilityActivations.process(state, AbilityTrigger.phase(BattleState.Phase.PLANNING)));
            if (finishBattleIfNeeded(state, events, 0)) return events;
        }
        for (BattleCombatant c : state.activeCombatants()) {
            if (!c.isActive()) continue;
            drainRoundAbilityCost(state, c, state.getRoundNumber(), events);
            if (finishBattleIfNeeded(state, events, 0)) return events;
        }
        finishBattleIfNeeded(state, events, 0);
        if (!state.isBattleOver()) state.finalizeTimelineGridLengthForRound();
        return events;
    }

    private boolean processPendingBattleStarts(
        BattleState state,
        List<CombatEvent> events
    ) {
        while (true) {
            BattleCombatant entrant = null;
            for (BattleCombatant combatant : state.activeCombatants()) {
                if (combatant.beginAbilityFightStart()) {
                    entrant = combatant;
                    break;
                }
            }
            if (entrant == null) return false;
            events.addAll(abilityActivations.process(
                state, AbilityTrigger.battleStart(entrant)));
            if (finishBattleIfNeeded(state, events, 0)) return true;
        }
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
        int actionMaxTick;
        int gridLimit;
        long nextLaunchSequence;
        boolean roundCostsProcessed;
        boolean deferSummonMaterialization;
        final NavigableMap<Integer, List<PendingComponent>> pendingComponents = new TreeMap<>();
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

        // The round ends once the last placed segment finishes: sweep only as
        // many ticks as the latest segment's AP window actually needs, rather
        // than always running out to the full grid length. Scan every active
        // combatant's timeline (multi-combatant), not just two.
        int maxTick = 0;
        int gridLimit = 0;
        for (BattleCombatant c : state.activeCombatants()) {
            Timeline tl = c.getTimeline();
            if (tl == null) continue;
            gridLimit = Math.max(gridLimit, tl.getGridLength());
            for (ActionSegment s : tl.getSegments()) {
                maxTick = Math.max(maxTick, s.getEndTick());
            }
        }

        ResolutionCursor c = cursor.get();
        c.tick = 0;
        c.maxTick = maxTick;
        c.actionMaxTick = maxTick;
        c.gridLimit = gridLimit == 0 ? BattlePlan.GRID_LENGTH : gridLimit;
        c.roundCostsProcessed = true;
        c.nextLaunchSequence = 0;
        c.pendingComponents.clear();

        events.addAll(abilityActivations.process(state, AbilityTrigger.phase(BattleState.Phase.RESOLUTION)));
        if (finishBattleIfNeeded(state, events, 0)) {
            c.roundCostsProcessed = false;
            return events;
        }
        updateResolutionEndForTimelineEffects(state);
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

        c.deferSummonMaterialization = true;
        try {
            c.tick++;
            int tick = c.tick;
            List<CombatEvent> events = new ArrayList<>();

            state.advanceTick();

            regenerateFighterCursedEnergy(state, tick, events);
            if (finishBattleIfNeeded(state, events, tick)) return events;

            if (isChargeableTick(state, tick)) {
                chargeSummonUpkeep(state, tick, events);
                if (finishBattleIfNeeded(state, events, tick)) return events;
            }

            processPerTickStatusRemoval(state, tick, events);
            if (finishBattleIfNeeded(state, events, tick)) return events;

            events.addAll(abilityActivations.process(state, AbilityTrigger.tick(tick)));
            if (finishBattleIfNeeded(state, events, tick)) return events;

            List<BattleCombatant> combatants = state.activeCombatants();

        // STAGGER is a character status, not a move tag. It acts before any
        // segment can begin or fire on this AP tick.
            for (BattleCombatant combatant : combatants) applyActiveStaggers(combatant, tick, events);

        // --- CE drain when a segment starts ---
            for (BattleCombatant combatant : combatants) {
                drainCeForStartingSegments(state, combatant, tick, events);
                if (finishBattleIfNeeded(state, events, tick)) return events;
            }

        // Impacts committed by earlier launches resolve before anything new is
        // unleashed on this tick. They remain valid even if the source segment
        // was subsequently stunned.
            resolvePendingComponentsAtTick(state, tick, events);
            if (finishBattleIfNeeded(state, events, tick)) return events;
            for (BattleCombatant combatant : combatants) applyActiveStaggers(combatant, tick, events);

        // --- Collect all moves firing this tick across every active combatant ---
            List<FiringEntry> firing = collectFiringMoves(state, tick);

        // --- Sort by priority ---
            sortFiringEntries(firing);

        // --- Resolve each firing move ---
            for (FiringEntry entry : firing) {
            // A stagger applied by an earlier same-tick move takes effect before
            // the next queued move gets a chance to resolve.
                for (BattleCombatant combatant : state.activeCombatants()) {
                    applyActiveStaggers(combatant, tick, events);
                }
                if (finishBattleIfNeeded(state, events, tick)) return events;
                if (stopSleepingAction(entry, tick, events)) continue;
                if (!entry.attacker.isActive() || entry.segment.isStunned()) continue;
                resolveMove(entry, state, tick, events);
            // This also handles a stagger that lands while the target is charging
            // and has no separate move firing later on the same tick.
                for (BattleCombatant combatant : state.activeCombatants()) {
                    applyActiveStaggers(combatant, tick, events);
                }
                if (finishBattleIfNeeded(state, events, tick)) return events;
            }

            processTimelineEffectExpiry(state, tick, events);
            if (finishBattleIfNeeded(state, events, tick)) return events;
            updateResolutionEndForTimelineEffects(state);

            // Safety net: summon enqueues materialize at their broadcast (inside
            // resolveMove / the ability engine), so this only drains summons
            // enqueued outside those paths during the tick.
            materializePendingSummons(state, tick, events);
            finishBattleIfNeeded(state, events, tick);
            return events;
        } finally {
            c.deferSummonMaterialization = false;
        }
    }

    private void regenerateFighterCursedEnergy(
        BattleState state,
        int tick,
        List<CombatEvent> events
    ) {
        for (BattleCombatant fighter : state.activeCombatants()) {
            int restored = fighter.regenerateCursedEnergyForTick();
            if (restored <= 0) continue;
            events.add(CombatEvent.of(CombatEvent.Type.CE_RESTORED)
                .source(fighter).target(fighter).intValue(restored).tick(tick)
                .build());
            events.addAll(abilityActivations.process(state, AbilityTrigger.amount(
                AbilityTrigger.Type.CE_RESTORED, fighter, null, restored, tick)));
        }
    }

    private void updateResolutionEndForTimelineEffects(BattleState state) {
        ResolutionCursor c = cursor.get();
        int remainingTicks = 0;
        for (BattleCombatant combatant : state.activeCombatants()) {
            remainingTicks = Math.max(remainingTicks, combatant.getRemainingTimelineEffectTicks());
        }
        long timerEnd = remainingTicks <= 0
            ? 0L : Math.min((long) c.gridLimit, (long) c.tick + remainingTicks);
        c.maxTick = Math.max(c.actionMaxTick, (int) timerEnd);
    }

    private boolean isChargeableTick(BattleState state, int tick) {
        if (cursor.get().pendingComponents.containsKey(tick)) return true;
        for (BattleCombatant combatant : state.activeCombatants()) {
            Timeline timeline = combatant.getTimeline();
            if (timeline != null && timeline.hasResolutionAt(tick)) return true;
            if (combatant.getRemainingTimelineEffectTicks() > 0) return true;
        }
        return false;
    }

    private void chargeSummonUpkeep(
        BattleState state,
        int tick,
        List<CombatEvent> events
    ) {
        List<BattleCombatant> active = state.activeCombatants();
        for (BattleCombatant summoner : active) {
            if (!summoner.isActive()) continue;
            double rate = 0.0;
            for (BattleCombatant summon : active) {
                if (summon.isActive()
                    && summoner.getInstanceId().equals(summon.getSummonerId())) {
                    rate += summon.getCharacter().getBaseCeDrainPerTick()
                        + summon.getAbilityFlags().summonCeUpkeepPerActiveTick;
                }
            }
            if (rate > 0.0) {
                // Efficient summoners maintain shikigami more cheaply: scale the
                // summed upkeep rate by the summoner's (scaled) CE Efficiency
                // before fractional accumulation.
                rate *= SummonUpkeepScaler.upkeepMultiplier(
                    summoner.getEffectiveStats().getCursedEnergyEfficiency(),
                    summoner.getStatMode());
            }
            int due = summoner.accrueSummonCeUpkeep(rate);
            if (due <= 0) continue;
            int drained = summoner.drainCe(due);
            if (drained <= 0) continue;
            events.add(CombatEvent.of(CombatEvent.Type.CE_DRAINED)
                .source(summoner).intValue(drained).tick(tick)
                .build());
            events.addAll(abilityActivations.process(state, AbilityTrigger.amount(
                AbilityTrigger.Type.CE_LOST, summoner, null, drained, tick)));
            if (!summoner.hasAnyCe()) {
                events.add(CombatEvent.of(CombatEvent.Type.CE_DEPLETED)
                    .source(summoner).tick(tick)
                    .build());
            }
        }
    }

    private void processTimelineEffectExpiry(
        BattleState state,
        int tick,
        List<CombatEvent> events
    ) {
        List<BattleCombatant> combatants = state.activeCombatants();
        Map<BattleCombatant, List<StatusEffect>> expiredByCombatant = new LinkedHashMap<>();
        Map<BattleCombatant, Integer> previousMaxHp = new IdentityHashMap<>();
        Map<BattleCombatant, Integer> previousMaxCe = new IdentityHashMap<>();
        Set<BattleCombatant> hpClamped = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<BattleCombatant> ceClamped = Collections.newSetFromMap(new IdentityHashMap<>());

        for (BattleCombatant combatant : combatants) {
            previousMaxHp.put(combatant, combatant.getMaxHp());
            previousMaxCe.put(combatant, combatant.getMaxCursedEnergy());
            combatant.beginPoolClampDeferral();
        }
        try {
            for (BattleCombatant combatant : combatants) {
                combatant.tickTimelineEffects();
                events.addAll(combatant.getCodedAbilities().tickTimelineEffects(tick));
                expiredByCombatant.put(combatant, combatant.drainExpiredStatusEffects());
            }
            // Expiry reactions happen after this tick's boundary; effects they
            // create begin counting on the next AP tick.
            expiryEvents:
            for (Map.Entry<BattleCombatant, List<StatusEffect>> entry
                : expiredByCombatant.entrySet()) {
                BattleCombatant combatant = entry.getKey();
                for (StatusEffect expired : entry.getValue()) {
                    events.add(CombatEvent.of(CombatEvent.Type.STATUS_EXPIRED)
                        .source(combatant).target(combatant).tick(tick)
                        .message(StatusEffectMessages.expiryMessage(
                            combatant.getCharacter().getName(), expired.getType()))
                        .build());
                    events.addAll(abilityActivations.process(state, AbilityTrigger.status(
                        AbilityTrigger.Type.STATUS_REMOVED, combatant, expired.getType(), tick)));
                    if (finishBattleIfNeeded(state, events, tick)) break expiryEvents;
                }
            }
        } finally {
            for (BattleCombatant combatant : combatants) {
                int hpBeforeClamp = combatant.getCurrentHp();
                int ceBeforeClamp = combatant.getCurrentCe();
                combatant.endPoolClampDeferral();
                if (combatant.getCurrentHp() != hpBeforeClamp) hpClamped.add(combatant);
                if (combatant.getCurrentCe() != ceBeforeClamp) ceClamped.add(combatant);
            }
        }

        for (BattleCombatant combatant : combatants) {
            appendResourceMaximumEvents(
                null, combatant,
                hpClamped.contains(combatant) ? -1 : previousMaxHp.get(combatant),
                ceClamped.contains(combatant) ? -1 : previousMaxCe.get(combatant),
                tick, events);
        }
    }

    /** Resolve configured status self-removal before this tick's actions can fire. */
    private void processPerTickStatusRemoval(
        BattleState state,
        int tick,
        List<CombatEvent> events
    ) {
        for (BattleCombatant combatant : state.activeCombatants()) {
            Map<StatusEffectType, Double> removalChances = new LinkedHashMap<>();
            for (StatusEffect effect : combatant.getActiveEffects()) {
                double chance = effect.getPerTickRemovalChance();
                if (chance > 0.0) {
                    removalChances.merge(effect.getType(), chance, Math::max);
                }
            }
            for (Map.Entry<StatusEffectType, Double> entry : removalChances.entrySet()) {
                double chance = entry.getValue();
                if (chance < 1.0 && rng.nextDouble() >= chance) continue;
                StatusEffectType status = entry.getKey();
                int previousMaxHp = combatant.getMaxHp();
                int previousMaxCe = combatant.getMaxCursedEnergy();
                if (combatant.removeStatusEffects(status) == 0) continue;
                events.add(CombatEvent.of(CombatEvent.Type.STATUS_EXPIRED)
                    .source(combatant).target(combatant).tick(tick)
                    .message(status == StatusEffectType.SLEEP
                        ? combatant.getCharacter().getName() + " wakes from Sleep!"
                        : StatusEffectMessages.expiryMessage(
                            combatant.getCharacter().getName(), status))
                    .build());
                appendResourceMaximumEvents(
                    combatant, combatant, previousMaxHp, previousMaxCe, tick, events);
                events.addAll(abilityActivations.process(state, AbilityTrigger.status(
                    AbilityTrigger.Type.STATUS_REMOVED, combatant, status, tick)));
                if (state.isBattleOver()) return;
            }
        }
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
        if (!combatant.isActive()) return;
        Timeline tl = combatant.getTimeline();
        if (tl == null) return;

        for (ActionSegment segment : tl.getSegments()) {
            if (!combatant.isActive()) return;
            if (segment.isStunned()) continue;
            if (segment.getStartTick() == tick) {
                if (stopMoveUnavailableForActiveSummon(
                    state, combatant, segment.getMove(), segment, tick, events)) {
                    continue;
                }
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
                    .build());
                events.addAll(abilityActivations.process(state, AbilityTrigger.amount(
                    AbilityTrigger.Type.CE_SPENT, combatant, null, drained, tick)));
                if (finishBattleIfNeeded(state, events, tick)) return;

                if (!combatant.hasAnyCe()) {
                    events.add(CombatEvent.of(CombatEvent.Type.CE_DEPLETED)
                        .source(combatant)
                        .tick(tick)
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
            .build());
        if (drained > 0) {
            events.addAll(abilityActivations.process(state, AbilityTrigger.amount(
                AbilityTrigger.Type.CE_SPENT, combatant, null, drained, 0)));
        }
        if (!combatant.hasAnyCe()) {
            events.add(CombatEvent.of(CombatEvent.Type.CE_DEPLETED)
                .source(combatant)
                .build());
        }
    }

    // -------------------------------------------------------------------------
    // Firing collection and sorting
    // -------------------------------------------------------------------------

    private record FiringEntry(ActionSegment segment, BattleCombatant attacker) {}

    private static final class MoveExecution {
        private final FiringEntry entry;
        private final Map<CombatantId, Boolean> forceFullBlockByTarget;
        private final int launchTick;
        private final long launchSequence;
        /**
         * The snapshot of targets this move fires against (fixed at fire time).
         * Single-target moves have one; AOE moves have many; defensive/utility
         * moves have none.
         */
        private final List<BattleCombatant> targets;
        /**
         * Per-target connection state for multi-hit AOE. Each target gets its own
         * array so one target's miss/block cannot prematurely cascade to another.
         * Keyed by combatant instance id.
         */
        private final Map<CombatantId, boolean[]> connectedByTarget;
        private int pendingRecoilDamage;
        private HitComponent recoilComponent;
        private final List<BattleCombatant> recoilTargets = new ArrayList<>();
        /**
         * Defenders who already attempted their on-defence counter against this
         * execution. A multi-hit attack defended component-by-component must
         * trigger each defender's counter at most once.
         */
        private final Set<CombatantId> counterAttemptedBy = new java.util.HashSet<>();

        /** Record a counter attempt; false when this defender already had one. */
        private boolean markCounterAttempt(CombatantId defenderId) {
            return counterAttemptedBy.add(defenderId);
        }

        private MoveExecution(
            FiringEntry entry,
            Map<CombatantId, Boolean> forceFullBlockByTarget,
            int launchTick,
            long launchSequence,
            List<BattleCombatant> targets
        ) {
            this.entry = entry;
            this.forceFullBlockByTarget = Map.copyOf(forceFullBlockByTarget);
            this.launchTick = launchTick;
            this.launchSequence = launchSequence;
            this.targets = List.copyOf(targets);
            this.connectedByTarget = new LinkedHashMap<>();
            for (BattleCombatant target : targets) {
                connectedByTarget.put(target.getInstanceId(),
                    new boolean[entry.segment.getMove().getHitComponents().size()]);
            }
        }

        private void addRecoil(
            int damage,
            HitComponent component,
            BattleCombatant target
        ) {
            if (damage <= 0) return;
            pendingRecoilDamage = (int) Math.min(
                Integer.MAX_VALUE, (long) pendingRecoilDamage + damage);
            if (recoilComponent == null) recoilComponent = component;
            recoilTargets.add(target);
        }

        private RecoilBatch drainRecoil() {
            if (pendingRecoilDamage <= 0) return null;
            RecoilBatch batch = new RecoilBatch(
                pendingRecoilDamage, recoilComponent, List.copyOf(recoilTargets));
            pendingRecoilDamage = 0;
            recoilComponent = null;
            recoilTargets.clear();
            return batch;
        }
    }

    private record PendingComponent(MoveExecution execution, int componentIndex, BattleCombatant target) {}

    private record RecoilBatch(
        int damage,
        HitComponent component,
        List<BattleCombatant> targets
    ) {}

    private record TieBreak(double randomKey, int insertionOrder) {}

    /**
     * Collect every segment firing at {@code tick} across all active combatants.
     * Targets are resolved at fire time in {@link #resolveMove}, not here — this
     * only pairs each segment with its attacker and preserves the stable
     * team/roster/instance order as the deterministic fallback.
     */
    private List<FiringEntry> collectFiringMoves(BattleState state, int tick) {
        List<FiringEntry> firing = new ArrayList<>();
        for (BattleCombatant combatant : state.activeCombatants()) {
            Timeline tl = combatant.getTimeline();
            if (tl == null) continue;
            for (ActionSegment segment : tl.firingAt(tick)) {
                firing.add(new FiringEntry(segment, combatant));
            }
        }
        return firing;
    }

    /**
     * Sort firing entries:
     *  1. Instant moves (unleashPoint == 1) first
     *  2. Higher Speed first
     *  3. Precomputed random tiebreak
     *  4. Stable team/roster/instance order as the deterministic fallback
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
                    if (randomComparison != 0) return randomComparison;
                    // Deterministic fallback: stable team/roster/instance order.
                    return Integer.compare(a.attacker.getRosterOrder(), b.attacker.getRosterOrder());
                });
            }
            groupStart = groupEnd;
        }
    }

    private int comparePriority(FiringEntry a, FiringEntry b) {
        int instantComparison = Boolean.compare(b.segment.isInstant(), a.segment.isInstant());
        if (instantComparison != 0) return instantComparison;
        int aSpeed = a.attacker.getRuntimeStat(StatKey.SPEED);
        int bSpeed = b.attacker.getRuntimeStat(StatKey.SPEED);
        return Integer.compare(bSpeed, aSpeed);
    }

    // -------------------------------------------------------------------------
    // Move resolution
    // -------------------------------------------------------------------------

    private void resolveMove(
        FiringEntry       entry,
        BattleState       state,
        int               tick,
        List<CombatEvent> events
    ) {
        if (!entry.attacker.isActive()) return;
        Move move = entry.segment.getMove();
        // Resolve targets at fire time: retarget an invalid single-target to the
        // first living enemy; snapshot the AOE target set so later summons are
        // excluded. Once resolved, the target set is fixed for this firing.
        TargetSet targets = resolveTargets(state, entry, tick, events);

        Map<CombatantId, Boolean> fullBlockByTarget = new LinkedHashMap<>();
        // Every AOE defender evaluates its own incoming-move hooks. A response
        // from one target must never block or react on behalf of another target.
        for (BattleCombatant target : targets.all()) {
            if (!target.isActive()) continue;
            CodedMoveResponse response = abilityActivations.beforeIncomingMove(
                state, AbilityTrigger.incomingMove(entry.attacker, target, move, tick));
            events.addAll(response.events());
            fullBlockByTarget.put(target.getInstanceId(), response.fullBlock());

            for (Move reactionMove : response.reactionMoves()) {
                resolveReactionMove(
                    reactionMove, target, entry.attacker, state, tick, events);
                for (BattleCombatant c : state.activeCombatants()) {
                    applyActiveStaggers(c, tick, events);
                }
                if (finishBattleIfNeeded(state, events, tick)
                    || !entry.attacker.isActive() || entry.segment.isStunned()) {
                    return;
                }
            }
        }

        resolveMove(entry, targets, state, tick, events, fullBlockByTarget, false);
    }

    /**
     * Resolve the target set for a firing move at fire time:
     * <ul>
     *   <li>{@link MoveTargeting#NONE} — no targets (self/defensive/utility/summon).</li>
     *   <li>{@link MoveTargeting#SINGLE_ENEMY} — the selected target, retargeted
     *       deterministically to the first living enemy if invalid (emits a
     *       retarget event). Once fired, the target is fixed; if it leaves
     *       before a delayed impact, that impact produces no hit.</li>
     *   <li>{@link MoveTargeting#MULTIPLE_ENEMIES} — the explicitly selected active
     *       enemies, refilled in roster order only up to the original selection count.</li>
     *   <li>{@link MoveTargeting#ALL_ENEMIES} / {@link MoveTargeting#ALL_OTHERS}
     *       — a snapshot of every active enemy / every active combatant except
     *       the caster, taken now. Summons materialize at their broadcast, so a
     *       summon created earlier this same tick IS included; ones created by
     *       later same-tick moves are not.</li>
     * </ul>
     */
    private TargetSet resolveTargets(
        BattleState state,
        FiringEntry entry,
        int tick,
        List<CombatEvent> events
    ) {
        Move move = entry.segment.getMove();
        MoveTargeting targeting = MoveTargeting.forMove(move);
        BattleCombatant attacker = entry.attacker;
        switch (targeting) {
            case SINGLE_ENEMY: {
                CombatantId selected = entry.segment.getTarget();
                BattleCombatant resolved = selected == null ? null : state.combatant(selected);

                // Taunt: a single-target MELEE attack is pulled onto any active
                // enemy holding a Taunt. Area-of-effect moves are resolved by
                // their own cases (ALL_ENEMIES / MULTIPLE_ENEMIES / ALL_OTHERS)
                // and are never redirected here, so a Taunt cannot pull an AOE.
                if (move.isMelee()) {
                    BattleCombatant taunter = state.taunterOf(attacker);
                    if (taunter != null && taunter.isActive() && !taunter.isAlliedWith(attacker)
                        && (resolved == null
                            || !taunter.getInstanceId().equals(resolved.getInstanceId()))) {
                        events.add(CombatEvent.of(CombatEvent.Type.TARGET_RETARGETED)
                            .source(attacker).target(taunter).move(move).tick(tick)
                            .message(attacker.getCharacter().getName() + "'s "
                                + move.getName() + " is drawn to "
                                + taunter.getCharacter().getName() + "!")
                            .build());
                        return TargetSet.single(taunter);
                    }
                }

                if (resolved == null || !resolved.isActive() || resolved.isAlliedWith(attacker)) {
                    // Retarget deterministically to the first living enemy.
                    BattleCombatant retarget = state.firstActiveEnemyOf(attacker);
                    if (retarget != null && (resolved == null || !retarget.getInstanceId().equals(selected))) {
                        events.add(CombatEvent.of(CombatEvent.Type.TARGET_RETARGETED)
                            .source(attacker).target(retarget).move(move).tick(tick)
                            .message(attacker.getCharacter().getName() + "'s "
                                + move.getName() + " retargets to "
                                + retarget.getCharacter().getName() + "!")
                            .build());
                    }
                    resolved = retarget;
                }
                return resolved == null ? TargetSet.empty() : TargetSet.single(resolved);
            }
            case ALL_ENEMIES:
                return TargetSet.multiple(state.activeEnemiesOf(attacker));
            case MULTIPLE_ENEMIES: {
                List<BattleCombatant> enemies = state.activeEnemiesOf(attacker);
                List<BattleCombatant> selected = new ArrayList<>();
                int requestedCount = Math.min(
                    Math.max(1, entry.segment.getTargets().isEmpty()
                        ? move.getAoeTargetCount() : entry.segment.getTargets().size()),
                    Math.max(1, move.getAoeTargetCount()));
                for (CombatantId selectedId : entry.segment.getTargets()) {
                    BattleCombatant candidate = state.combatant(selectedId);
                    if (candidate != null && candidate.isActive()
                        && !candidate.isAlliedWith(attacker) && !selected.contains(candidate)
                        && CursedSpeechAbility.canTarget(move, candidate)) {
                        selected.add(candidate);
                    }
                }
                for (BattleCombatant enemy : enemies) {
                    if (selected.size() >= requestedCount) break;
                    if (!selected.contains(enemy) && CursedSpeechAbility.canTarget(move, enemy)) {
                        selected.add(enemy);
                    }
                }
                return TargetSet.multiple(selected);
            }
            case ALL_OTHERS: {
                List<BattleCombatant> others = new ArrayList<>();
                for (BattleCombatant c : state.activeCombatants()) {
                    if (c != attacker && c.isActive()) others.add(c);
                }
                return TargetSet.multiple(others);
            }
            case NONE:
            default:
                return TargetSet.empty();
        }
    }

    /**
     * Confer a defensive move's active-defense window onto its beneficiaries at
     * fire time. {@link DefenseTargeting#SELF} (the default) leaves the segment
     * on the caster's own timeline — the historical behaviour, so nothing to do.
     * The ally modes insert a fired clone into each non-caster beneficiary's
     * timeline and mark the original transferred so it stops protecting the
     * caster — <em>unless</em> the caster is itself a beneficiary (e.g.
     * {@link DefenseTargeting#ALL_ALLIES_INCLUDING_SELF}), in which case the
     * caster keeps its own protection and only the allies receive clones. If no
     * ally beneficiary can be resolved (e.g. none selected, or all defeated),
     * the protection simply remains on the caster.
     */
    private void grantDefense(
        BattleState state, BattleCombatant caster, ActionSegment segment,
        Move move, int tick, List<CombatEvent> events,
        List<BattleCombatant> beneficiaries
    ) {
        if (move.getDefenseTargeting() == DefenseTargeting.SELF) return;

        boolean casterIsBeneficiary = false;
        boolean grantedToAlly = false;
        for (BattleCombatant beneficiary : beneficiaries) {
            if (beneficiary == caster) {
                casterIsBeneficiary = true;
                continue;
            }
            if (beneficiary == null || !beneficiary.isActive()) continue;
            Timeline timeline = beneficiary.getTimeline();
            if (timeline == null) continue;
            timeline.insertGrantedDefense(segment.cloneFired());
            grantedToAlly = true;
            events.add(CombatEvent.of(CombatEvent.Type.DEFENSE_GRANTED)
                .source(caster)
                .target(beneficiary)
                .move(move)
                .tick(tick)
                .message(caster.getCharacter().getName() + " granted "
                    + move.getName() + " to " + beneficiary.getCharacter().getName() + ".")
                .build());
        }
        // Only relinquish the caster's own protection when the window was
        // actually conferred to at least one ally AND the caster is not itself a
        // beneficiary (so ALL_ALLIES_INCLUDING_SELF keeps protecting the caster).
        if (grantedToAlly && !casterIsBeneficiary) segment.markTransferred();
    }

    /**
     * Resolve the combatants a defensive move protects, per its
     * {@link DefenseTargeting}. Explicit modes read the segment's planned
     * targets (validated active + allied); the auto modes fan out over the
     * caster's living allies.
     */
    private List<BattleCombatant> resolveDefenseBeneficiaries(
        BattleState state, BattleCombatant caster, ActionSegment segment, Move move
    ) {
        return switch (move.getDefenseTargeting()) {
            case SINGLE_ALLY, MULTIPLE_ALLIES -> {
                List<BattleCombatant> out = new ArrayList<>();
                for (CombatantId id : segment.getTargets()) {
                    BattleCombatant c = state.combatant(id);
                    if (c != null && c.isActive() && c.isAlliedWith(caster)) out.add(c);
                }
                yield out;
            }
            case ALL_ALLIES_EXCEPT_SELF -> new ArrayList<>(state.activeAlliesOf(caster));
            case ALL_ALLIES_INCLUDING_SELF -> {
                List<BattleCombatant> out = new ArrayList<>();
                out.add(caster);
                out.addAll(state.activeAlliesOf(caster));
                yield out;
            }
            default -> List.of();
        };
    }

    private void resolveReactionMove(
        Move reactionMove,
        BattleCombatant reactor,
        BattleCombatant target,
        BattleState state,
        int tick,
        List<CombatEvent> events
    ) {
        // A reaction move targets the attacker that triggered it.
        resolveLaunchedMove(
            reactionMove, reactor,
            target == null ? TargetSet.empty() : TargetSet.single(target),
            state, tick, events, true);
    }

    /**
     * Launch a move mid-resolution: the launched move's CE cost is paid now
     * (at launch, not at a planned segment start), a fresh segment is built at
     * the current tick, and the move resolves against the given targets. This
     * is the shared path for coded reaction moves, a hybrid's referenced
     * attack, and on-fire launches.
     */
    private void resolveLaunchedMove(
        Move launchedMove,
        BattleCombatant launcher,
        TargetSet targets,
        BattleState state,
        int tick,
        List<CombatEvent> events,
        boolean reaction
    ) {
        if (launcher == null || !launcher.isActive()) return;
        if ((long) tick + launchedMove.getMaxHitDelayTicks() > cursor.get().gridLimit) {
            return;
        }
        if (stopMoveUnavailableForActiveSummon(
            state, launcher, launchedMove, null, tick, events)) return;
        if (launcher.consumeMoveCancellation()) {
            events.add(CombatEvent.of(CombatEvent.Type.MOVE_STUNNED)
                .target(launcher).move(launchedMove).tick(tick)
                .message(launcher.getCharacter().getName() + "'s "
                    + launchedMove.getName() + " was cancelled by an ability!")
                .build());
            return;
        }
        int cost = launcher.computeMoveCeCost(launchedMove);
        if (!launcher.hasCe(cost)) {
            events.add(CombatEvent.of(CombatEvent.Type.CE_DEPLETED)
                .source(launcher).move(launchedMove).tick(tick)
                .message(launcher.getCharacter().getName() + " does not have enough CE for "
                    + launchedMove.getName() + "!")
                .build());
            return;
        }
        if (cost > 0) {
            int drained = launcher.drainCe(cost);
            events.add(CombatEvent.of(CombatEvent.Type.CE_DRAINED)
                .source(launcher).move(launchedMove).intValue(drained).tick(tick)
                .build());
            events.addAll(abilityActivations.process(state, AbilityTrigger.amount(
                AbilityTrigger.Type.CE_SPENT, launcher, null, drained, tick)));
            if (finishBattleIfNeeded(state, events, tick)) return;
            if (!launcher.hasAnyCe()) {
                events.add(CombatEvent.of(CombatEvent.Type.CE_DEPLETED)
                    .source(launcher).tick(tick)
                    .build());
            }
        }

        ActionSegment launchedSegment = new ActionSegment(launchedMove, tick, cost);
        resolveMove(
            new FiringEntry(launchedSegment, launcher),
            targets,
            state, tick, events, Map.of(), reaction);
    }

    private void resolveMove(
        FiringEntry       entry,
        TargetSet         targets,
        BattleState       state,
        int               tick,
        List<CombatEvent> events,
        Map<CombatantId, Boolean> forceFullBlockByTarget,
        boolean reaction
    ) {
        ActionSegment   segment  = entry.segment;
        Move            move     = segment.getMove();
        BattleCombatant attacker = entry.attacker;
        if (!attacker.isActive()) return;

        if (stopMoveUnavailableForActiveSummon(
            state, attacker, move, segment, tick, events)) return;

        // This segment's move is now actually executing. Recording it as fired
        // makes it immune to retro-stunning for the rest of the round — a stun
        // or interrupt landing later this tick (or a later tick still inside a
        // block's window) can't cancel a move whose effects are already in play.
        segment.markFired();

        events.add(CombatEvent.of(CombatEvent.Type.MOVE_FIRED)
            .source(attacker)
            .move(move)
            .tick(tick)
            .message(attacker.getCharacter().getName() + (reaction ? " reacted with " : " used ")
                + move.getName() + "!")
            .build());
        // MOVE_FIRED fires once, regardless of how many targets the move hits.
        events.addAll(abilityActivations.process(state, AbilityTrigger.move(
            AbilityTrigger.Type.MOVE_USED, attacker, targets.primary(), move, tick)));
        if (finishBattleIfNeeded(state, events, tick)) return;

        // --- Resolve this defensive move's beneficiaries once: the on-fire
        // effect rows may target them (ALLY / SELF_AND_ALLY), and the grant
        // below confers the active-defense window to them. ---
        List<BattleCombatant> defenseBeneficiaries = move.isDefensive()
            ? resolveDefenseBeneficiaries(state, attacker, segment, move)
            : List.of();
        List<BattleCombatant> defenseAllies = defenseBeneficiaries.stream()
            .filter(ally -> ally != attacker).toList();

        // --- Self-effects apply on unleash, for every move type (damaging,
        // defensive, and utility alike). A move that buffs its user when cast
        // (e.g. a CE strike that raises Power) fires the buff here, regardless
        // of whether the attack later hits, misses, or is blocked. Charged once.
        // For a defensive move, ALLY effect rows resolve to the conferred allies.
        if (move.usesUnifiedEffects()) {
            events.addAll(abilityActivations.processMoveEffects(
                state, attacker, targets.all(), move,
                MoveEffectTrigger.ON_FIRE, -1, tick, defenseAllies));
        } else {
            applySelfEffects(state, attacker, targets.primary(), move, tick, events);
        }
        if (finishBattleIfNeeded(state, events, tick)) return;
        if (!attacker.isActive()) return;

        // --- Summon: a move that carries a summonCharacterId enqueues a shikigami
        // at its unleash point (works on utility and attack moves alike). The
        // summon materializes immediately — right after the MOVE_FIRED broadcast
        // — so later moves this same tick can target it (and are subject to the
        // shikigami-locked move gate). It joins the firing list next round,
        // because only planning attaches timelines.
        if (!move.usesUnifiedEffects() && move.summonsCharacter()) {
            state.enqueueSummon(attacker, move.getSummonCharacterId(),
                move.getTags().contains(MoveTag.INNATE_TECHNIQUE));
            materializePendingSummons(state, tick, events);
        }

        // --- Defensive moves: confer the active-defense window onto the
        // beneficiaries (self by default; an ally targeting mode grants a fired
        // copy to each selected/allied combatant's timeline and marks the
        // original transferred so it no longer protects the caster — unless the
        // caster is itself a beneficiary, e.g. ALL_ALLIES_INCLUDING_SELF). ---
        if (move.isDefensive()) {
            grantDefense(state, attacker, segment, move, tick, events, defenseBeneficiaries);
            // A Defensive+Attack hybrid launching on fire resolves its attack
            // right here: a referenced move launches against the resolved
            // targets, a custom attack falls through to the normal component
            // scheduling below. The launch gate (condition + chance) applies
            // to both variants.
            if (!move.launchesAttackOnFire()) return;
            if (!abilityActivations.allowsAttackLaunch(
                    state, attacker, targets.primary(), move, tick)) return;
            if (move.referencesAttackMove()) {
                Move referenced = move.getAttackLaunchMove();
                if (referenced != null) {
                    resolveLaunchedMove(referenced, attacker, targets,
                        state, tick, events, false);
                }
                return;
            }
            // Custom attack: schedule this move's own components below.
        }

        // --- Non-damaging utility moves (including summon-only moves) ---
        if (move.getHitComponents().isEmpty()) {
            return;
        }

        MoveExecution execution = new MoveExecution(
            entry, forceFullBlockByTarget, tick, cursor.get().nextLaunchSequence++,
            targets.all());
        scheduleComponents(execution);
        resolvePendingComponentsAtTick(state, tick, events);
    }

    private boolean stopMoveUnavailableForActiveSummon(
        BattleState state,
        BattleCombatant attacker,
        Move move,
        ActionSegment segment,
        int tick,
        List<CombatEvent> events
    ) {
        String reason = MoveAvailability.activeOwnedSummonRestrictionReason(
            state, attacker, move);
        if (reason == null) return false;
        if (segment != null) segment.stun();
        // A move re-validated as unavailable mid-round (e.g. its shikigami was
        // summoned earlier this same round) uses the generic failure message —
        // the same wording every other failed move reports with.
        events.add(CombatEvent.of(CombatEvent.Type.MOVE_STUNNED)
            .source(attacker).target(attacker).move(move).tick(tick)
            .message(moveFailedMessage(attacker, move))
            .build());
        return true;
    }

    /** The default move-failure broadcast: "X tried to use Y, but it failed!" */
    private static String moveFailedMessage(BattleCombatant attacker, Move move) {
        return attacker.getCharacter().getName()
            + " tried to use " + move.getName() + ", but it failed!";
    }

    private void scheduleComponents(MoveExecution execution) {
        ResolutionCursor c = cursor.get();
        List<HitComponent> components = execution.entry.segment.getMove().getHitComponents();
        // Schedule each component for each target. Multi-hit AOE has independent
        // requiresPreviousConnection state per target (one execution object's
        // per-target arrays handle that).
        for (BattleCombatant target : execution.targets) {
            for (int index = 0; index < components.size(); index++) {
                int impactTick = Math.addExact(
                    execution.launchTick, components.get(index).getDelayTicks());
                c.pendingComponents.computeIfAbsent(impactTick, ignored -> new ArrayList<>())
                    .add(new PendingComponent(execution, index, target));
                c.actionMaxTick = Math.max(c.actionMaxTick, impactTick);
                c.maxTick = Math.max(c.maxTick, impactTick);
            }
        }
    }

    private void resolvePendingComponentsAtTick(
        BattleState state,
        int tick,
        List<CombatEvent> events
    ) {
        List<PendingComponent> pending = cursor.get().pendingComponents.remove(tick);
        if (pending == null) return;
        pending.sort(Comparator
            .comparingLong((PendingComponent value) -> value.execution.launchSequence)
            .thenComparingInt(PendingComponent::componentIndex));
        Set<MoveExecution> executions = new LinkedHashSet<>();

        // Resolve every target in one AOE hit-component batch before checking
        // team victory, allowing friendly-fire simultaneous wipes and draws.
        for (PendingComponent value : pending) {
            MoveExecution execution = value.execution;
            executions.add(execution);
            int componentIndex = value.componentIndex;
            BattleCombatant target = value.target;
            // Once a projectile has fired, its target is fixed. If that target
            // leaves (defeated/removed/dismissed) before a delayed impact, the
            // impact produces no hit.
            if (target == null || !target.isActive()) continue;
            boolean[] connected = execution.connectedByTarget.get(target.getInstanceId());
            if (connected == null) continue;
            HitComponent component = execution.entry.segment.getMove()
                .getHitComponents().get(componentIndex);
            if (component.requiresPreviousConnection()
                && (componentIndex == 0 || !connected[componentIndex - 1])) {
                continue;
            }
            connected[componentIndex] = resolveHitComponent(
                execution, component, componentIndex, target, state, tick, events);
            // Miss/block hooks and defensive coded effects can also mutate HP.
            // Reconcile after every outcome while deferring team victory until
            // the complete target batch has resolved.
            reconcileLifecycle(state, tick, events);
            // Do NOT finish-battle mid-batch: resolve every pending target in
            // this batch first so a simultaneous friendly-fire wipe can draw.
            for (BattleCombatant c : state.activeCombatants()) applyActiveStaggers(c, tick, events);
        }
        // Cursed Speech rolls independently per target, then applies one summed
        // recoil hit only after every selected target in this impact batch resolves.
        for (MoveExecution execution : executions) {
            RecoilBatch recoil = execution.drainRecoil();
            if (recoil == null) continue;
            applyCursedSpeechRecoil(
                state, execution.entry.attacker, execution.entry.segment.getMove(),
                recoil.component(), recoil.damage(), recoil.targets(), tick, events);
            reconcileLifecycle(state, tick, events);
        }
        // Reconcile defeats and check victory only after the whole batch resolves.
        finishBattleIfNeeded(state, events, tick);
    }

    /** Resolve one impact against one target. HIT and full BLOCK are the two connecting outcomes. */
    private boolean resolveHitComponent(
        MoveExecution execution,
        HitComponent component,
        int componentIndex,
        BattleCombatant defender,
        BattleState state,
        int tick,
        List<CombatEvent> events
    ) {
        Move move = execution.entry.segment.getMove();
        BattleCombatant attacker = execution.entry.attacker;

        // An armed REACTION defence converts into a triggered window the moment
        // a hostile component is about to resolve against its wielder. The
        // triggered clone is already fired, so it contests this very attack
        // under the normal requireFiredDefense gate.
        Timeline defenderTimeline = defender.getTimeline();
        if (defenderTimeline != null) {
            ActionSegment reaction = defenderTimeline.triggerArmedReaction(tick, move);
            if (reaction != null) {
                events.add(CombatEvent.of(CombatEvent.Type.MOVE_FIRED)
                    .source(defender).move(reaction.getMove()).tick(tick)
                    .message(defender.getCharacter().getName() + "'s "
                        + reaction.getMove().getName() + " reacted to " + move.getName() + "!")
                    .build());
            }
        }

        DamageCalculator.DamageResult result = DamageCalculator.resolve(
            attacker, defender, move, component, tick, rng,
            state.getRoundNumber(), Boolean.TRUE.equals(
                execution.forceFullBlockByTarget.get(defender.getInstanceId())),
            // A defense only contests an attack if it has ALREADY fired this
            // tick — i.e. it won the same-tick speed ordering (instant > higher
            // Speed > random). A defense aligned to the same tick but slower has
            // not been markFired() yet when a faster attack resolves, so it is
            // skipped here. Defending therefore means committing the block to
            // fire BEFORE the attack lands, not merely on the same tick. Do NOT
            // revert this to `launchTick < tick` — that re-enables the old rule
            // where a not-yet-fired same-tick defense contested regardless of speed.
            true,
            trigger -> abilityActivations.onAttackConnected(state, trigger));
        events.addAll(result.getCodedEvents());
        execution.addRecoil(result.getRecoilDamage(), component, defender);

        if (result.isResisted()) {
            return false;
        }

        if (result.isMiss()) {
            events.add(CombatEvent.of(CombatEvent.Type.MOVE_MISSED)
                .source(attacker).target(defender).move(move).componentIndex(componentIndex)
                .tick(tick)
                .message(move.getName() + " missed " + defender.getCharacter().getName() + "!")
                .build());
            events.addAll(abilityActivations.process(state, AbilityTrigger.move(
                AbilityTrigger.Type.ATTACK_MISSED, attacker, defender, move, tick)));
            return false;
        }

        if (result.isDodged()) {
            Move defenseMove = defenseMove(result);
            events.add(CombatEvent.of(CombatEvent.Type.MOVE_DODGED)
                .source(attacker).target(defender).move(move).componentIndex(componentIndex)
                .tick(tick)
                .message((result.isPerfectRead() ? "PERFECT READ! " : "")
                    + defender.getCharacter().getName() + " dodged " + move.getName() + "!")
                .build());
            applyDefenseEffects(state, defender, attacker, defenseMove,
                MoveEffectTrigger.ON_DODGE,
                defenseMove == null ? List.of() : defenseMove.getOnDodgeEffects(),
                componentIndex, tick, events, execution);
            return false;
        }

        if (result.isParried()) {
            Move defenseMove = defenseMove(result);
            events.add(CombatEvent.of(CombatEvent.Type.MOVE_PARRIED)
                .source(attacker).target(defender).move(move).componentIndex(componentIndex)
                .tick(tick)
                .message((result.isPerfectRead() ? "PERFECT READ! " : "")
                    + defender.getCharacter().getName() + " parried " + move.getName() + "!")
                .build());
            if (result.staggersAttacker()) {
                attacker.addStatusEffect(
                    new StatusEffect(StatusEffectType.STAGGER, 0,
                                     result.getParryStaggerTicks(), 0.0),
                    state.getCurrentPhase());
                events.add(CombatEvent.of(CombatEvent.Type.STATUS_APPLIED)
                    .source(defender).target(attacker).move(move).componentIndex(componentIndex)
                    .tick(tick)
                    .message(attacker.getCharacter().getName() + " was staggered!")
                    .build());
                stunActiveSegments(attacker, tick, false);
            }
            applyDefenseEffects(state, defender, attacker, defenseMove,
                MoveEffectTrigger.ON_PARRY,
                defenseMove == null ? List.of() : defenseMove.getOnParryEffects(),
                componentIndex, tick, events, execution);
            events.addAll(abilityActivations.process(state, AbilityTrigger.move(
                AbilityTrigger.Type.MOVE_BLOCKED, attacker, defender, move, tick)));
            return false;
        }

        if (result.isBlocked()) {
            Move defenseMove = defenseMove(result);
            events.add(CombatEvent.of(CombatEvent.Type.MOVE_BLOCKED)
                .source(attacker).target(defender).move(move).componentIndex(componentIndex)
                .tick(tick)
                .message((result.isPerfectRead() ? "PERFECT READ! " : "")
                    + defender.getCharacter().getName() + " blocked " + move.getName() + "!")
                .build());
            applyDefenseEffects(state, defender, attacker, defenseMove,
                MoveEffectTrigger.ON_BLOCK,
                defenseMove == null ? List.of() : defenseMove.getOnBlockEffects(),
                componentIndex, tick, events, execution);
            events.addAll(abilityActivations.process(state, AbilityTrigger.move(
                AbilityTrigger.Type.MOVE_BLOCKED, attacker, defender, move, tick)));
            return true;
        }

        boolean wasBlocked = !result.bypassedBlock() && result.getDefenseSegment() != null;
        int appliedDamage = defender.receiveDamage(
            result.getFinalDamage(),
            fatalAmount -> abilityActivations.preventFatalDamage(
                state,
                AbilityTrigger.fatalDamage(
                    attacker, defender, move, component, fatalAmount, tick)));
        events.addAll(defender.getCodedAbilities().drainPendingEvents(tick));

        if (wasBlocked) {
            events.add(CombatEvent.of(CombatEvent.Type.MOVE_BLOCK_REDUCED)
                .source(attacker).target(defender).move(move).componentIndex(componentIndex)
                .tick(tick)
                .message(defender.getCharacter().getName()
                    + " blocked " + move.getName() + "! (damage reduced)"
                    + hitQualifier(move, componentIndex))
                .build());
            Move defenseMove = defenseMove(result);
            applyDefenseEffects(state, defender, attacker, defenseMove,
                MoveEffectTrigger.ON_BLOCK,
                defenseMove == null ? List.of() : defenseMove.getOnBlockEffects(),
                componentIndex, tick, events, execution);
        }

        if (component.getBasePower() > 0 || appliedDamage > 0) {
            events.add(CombatEvent.of(appliedDamage == 0
                    ? CombatEvent.Type.DAMAGE_IGNORED : CombatEvent.Type.DAMAGE_DEALT)
                .source(attacker).target(defender).move(move).componentIndex(componentIndex)
                .intValue(appliedDamage)
                .tick(tick)
                .message(wasBlocked ? "" : appliedDamage == 0
                    ? defender.getCharacter().getName() + " ignored " + move.getName() + "!"
                    : move.getName() + " hit " + defender.getCharacter().getName() + "!"
                        + hitQualifier(move, componentIndex))
                .build());
        }
        events.addAll(abilityActivations.process(state, AbilityTrigger.move(
            AbilityTrigger.Type.ATTACK_HIT, attacker, defender, move, tick)));
        if (appliedDamage > 0) {
            events.addAll(abilityActivations.process(state, AbilityTrigger.amount(
                AbilityTrigger.Type.DAMAGE, attacker, defender, appliedDamage, tick)));
            wakeFromSleep(state, attacker, defender, move, componentIndex, tick, events);
        }

        if (result.isBlackFlash()) {
            int requestedCe = (int) Math.round(
                attacker.getMaxCursedEnergy() * CombatStats.BF_CE_RESTORE_FRACTION);
            int ceRestored = attacker.restoreCe(requestedCe);
            boolean wasInBfs = attacker.isInBlackFlashState();
            attacker.enterBlackFlashState(state.getRoundNumber());
            if (wasInBfs) attacker.recordBfsHit();

            // Landing a Black Flash focuses the wielder's cursed energy: a timed
            // CE Output buff that refreshes (resets) instead of stacking on repeat procs.
            attacker.addRuntimeAbilityEffect(
                AbilityEffectData.tempStatPercent(
                    StatKey.CURSED_ENERGY_OUTPUT.fieldName,
                    CombatStats.BF_CE_OUTPUT_BUFF_FRACTION,
                    0, CombatStats.BF_CE_OUTPUT_BUFF_TICKS),
                state.getRoundNumber(), state.getCurrentPhase(),
                CombatStats.BF_CE_OUTPUT_BUFF_REFRESH_GROUP);

            events.add(CombatEvent.of(CombatEvent.Type.BLACK_FLASH)
                .source(attacker).target(defender).move(move).componentIndex(componentIndex)
                .intValue(result.getFinalDamage())
                .tick(tick)
                .message("*** BLACK FLASH! *** " + attacker.getCharacter().getName()
                         + " lands a Black Flash!")
                .build());
            events.add(CombatEvent.of(CombatEvent.Type.CE_RESTORED)
                .source(attacker).intValue(ceRestored).componentIndex(componentIndex)
                .tick(tick)
                .build());
            events.addAll(abilityActivations.process(state, AbilityTrigger.move(
                AbilityTrigger.Type.BLACK_FLASH, attacker, defender, move, tick)));
            if (ceRestored > 0) {
                events.addAll(abilityActivations.process(state, AbilityTrigger.amount(
                    AbilityTrigger.Type.CE_RESTORED, attacker, null, ceRestored, tick)));
            }
        }

        // Reconcile per-target defeat/removal (a summon at 0 HP is removed; a
        // summoner at 0 recursively dismisses its summons) but do NOT end the
        // battle mid-batch — a friendly-fire AOE must be able to wipe both teams
        // simultaneously for a draw. Team victory is checked after the batch.
        reconcileLifecycle(state, tick, events);
        if (move.usesUnifiedEffects()) {
            events.addAll(abilityActivations.processMoveEffects(
                state, attacker, defender, move,
                MoveEffectTrigger.ON_HIT, componentIndex, tick));
        } else {
            applyOnHitEffects(
                state, attacker, defender, move, component, componentIndex, tick, events);
        }
        applyAbilityOnHitEffects(
            state, attacker, defender, move, componentIndex, tick, events);
        return true;
    }

    /** A resolved target set for one firing move (single, AOE, or none). */
    private static final class TargetSet {
        private final List<BattleCombatant> targets;
        private TargetSet(List<BattleCombatant> targets) { this.targets = targets; }
        static TargetSet empty() { return new TargetSet(List.of()); }
        static TargetSet single(BattleCombatant c) { return new TargetSet(List.of(c)); }
        static TargetSet multiple(List<BattleCombatant> cs) {
            return new TargetSet(List.copyOf(cs));
        }
        List<BattleCombatant> all() { return targets; }
        BattleCombatant primary() {
            return targets.isEmpty() ? null : targets.get(0);
        }
    }

    /**
     * Per-hit dialogue qualifier for multi-hit moves — e.g. " (hit 2)". Empty
     * for single-hit moves so their messages are unchanged, and empty for the
     * first hit of a multi-hit move (it reads as the opening strike).
     */
    private static String hitQualifier(Move move, int componentIndex) {
        if (move == null || move.getHitComponents().size() <= 1) return "";
        if (componentIndex <= 0) return "";
        return " (hit " + (componentIndex + 1) + ")";
    }

    private static Move defenseMove(DamageCalculator.DamageResult result) {
        return result == null || result.getDefenseSegment() == null
            ? null : result.getDefenseSegment().getMove();
    }

    /** Apply STAGGER's ongoing stun. HEAVY resists only immediate stun effects. */
    private void resolveStaggerStatus(
        BattleCombatant defender,
        int tick,
        List<CombatEvent> events
    ) {
        if (stunActiveSegments(defender, tick, false)) {
            events.add(CombatEvent.of(CombatEvent.Type.MOVE_STUNNED)
                .target(defender)
                .tick(tick)
                .message(defender.getCharacter().getName()
                    + " was staggered and could not move.")
                .build());
        }
    }

    /** Stop an action only when it reaches its fire tick while its user is asleep. */
    private static boolean stopSleepingAction(
        FiringEntry entry,
        int tick,
        List<CombatEvent> events
    ) {
        if (entry == null || !entry.attacker.isActive() || entry.segment.isStunned()
            || !entry.attacker.hasEffect(StatusEffectType.SLEEP)) {
            return false;
        }
        entry.segment.stun();
        events.add(CombatEvent.of(CombatEvent.Type.MOVE_STUNNED)
            .target(entry.attacker).move(entry.segment.getMove()).tick(tick)
            .message(entry.attacker.getCharacter().getName() + " tried to use "
                + entry.segment.getMove().getName() + " but was asleep!")
            .build());
        return true;
    }

    private void applyActiveStaggers(
        BattleCombatant combatant,
        int tick,
        List<CombatEvent> events
    ) {
        if (combatant.hasEffect(StatusEffectType.STAGGER)) {
            resolveStaggerStatus(combatant, tick, events);
        }
    }

    /** Stun active, not-yet-fired segments and report whether any were changed. */
    private static boolean stunActiveSegments(
        BattleCombatant defender,
        int tick,
        boolean heavyResists
    ) {
        Timeline defenderTimeline = defender.getTimeline();
        if (defenderTimeline == null) return false;

        boolean stunnedAny = false;
        for (ActionSegment segment : defenderTimeline.getSegments()) {
            if (segment.isStunned()) continue;
            if (segment.hasFired()) continue;        // can't un-fire an already-resolved move
            if (heavyResists && segment.getMove().isHeavy()) continue;
            boolean onCurrentTick =
                (tick >= segment.getStartTick() && tick <= segment.getEndTick())
                || segment.getFireTick() == tick;
            if (onCurrentTick) {
                segment.stun();
                stunnedAny = true;
            }
        }
        return stunnedAny;
    }

    // -------------------------------------------------------------------------
    // Status effect application
    // -------------------------------------------------------------------------

    /**
     * Enqueue a shikigami summon requested by a move effect row (self / on-hit /
     * on-defense). Shares the runtime path with the legacy {@code summonCharacterId}
     * unleash block and ability {@code SUMMON_CHARACTER} effect: the summon is
     * materialized immediately at the broadcast via {@code drainPendingSummons},
     * so the COMBATANT_SUMMONED event follows this MOVE_SUMMON event directly.
     */
    private void enqueueEffectSummon(
        BattleState state,
        BattleCombatant summoner,
        String summonCharacterId,
        Move move,
        int componentIndex,
        int tick,
        List<CombatEvent> events
    ) {
        if (summonCharacterId == null || summonCharacterId.isBlank()) return;
        if (!state.enqueueSummon(summoner, summonCharacterId,
                move != null && move.getTags().contains(MoveTag.INNATE_TECHNIQUE))) return;
        events.add(CombatEvent.of(CombatEvent.Type.MOVE_SUMMON)
            .source(summoner).move(move)
            .componentIndex(componentIndex)
            .tick(tick)
            .build());
        materializePendingSummons(state, tick, events);
    }

    private void applyOnHitEffects(
        BattleState state,
        BattleCombatant attacker,
        BattleCombatant defender,
        Move            move,
        HitComponent    component,
        int             componentIndex,
        int             tick,
        List<CombatEvent> events
    ) {
        for (StatusEffect authored : component.getOnHitEffects()) {
            StatusEffect effect = TechniqueMasteryResolver.resolve(
                authored, TechniqueMasteryResolver.masteryOf(attacker));
            // A summon on-hit row enqueues a shikigami onto the attacker's team
            // when the hit connects (mirrors the unleash-time summon path).
            if (effect.isSummon()) {
                enqueueEffectSummon(state, attacker, effect.getSummonCharacterId(),
                    move, componentIndex, tick, events);
                continue;
            }
            // A coded on-hit row is dispatched to the matching compiled runtime
            // instead of being applied as a status — this is how a technique move's
            // hardcoded on-hit behaviour is stored on an editable effect row.
            if (effect.isCoded()) {
                if (CursedSpeechAbility.isCommand(effect)) {
                    applyCursedSpeechCommandOutcome(
                        state, attacker, defender, move, effect, componentIndex, tick, events);
                } else {
                    events.addAll(attacker.getCodedAbilities().onEffectFired(
                        state, effect, attacker, defender, tick));
                }
                continue;
            }
            int previousMaxHp = defender.getMaxHp();
            int previousMaxCe = defender.getMaxCursedEnergy();
            if (!defender.addStatusEffect(effect, state.getCurrentPhase())) continue;
            events.add(CombatEvent.of(CombatEvent.Type.STATUS_APPLIED)
                .source(attacker).target(defender).move(move)
                .componentIndex(componentIndex)
                .tick(tick)
                .message(StatusEffectMessages.applicationMessage(
                    attacker.getCharacter().getName(),
                    defender.getCharacter().getName(),
                    effect.getType(),
                    attacker == defender))
                .build());
            appendResourceMaximumEvents(
                attacker, defender, previousMaxHp, previousMaxCe, tick, events);
            events.addAll(abilityActivations.process(state, AbilityTrigger.status(
                AbilityTrigger.Type.STATUS_APPLIED, defender, effect.getType(), tick)));
        }
    }

    private void applyCursedSpeechRecoil(
        BattleState state,
        BattleCombatant attacker,
        Move move,
        HitComponent component,
        int requested,
        List<BattleCombatant> targets,
        int tick,
        List<CombatEvent> events
    ) {
        if (requested <= 0 || attacker == null) return;
        int applied = attacker.receiveDamage(requested,
            fatalAmount -> abilityActivations.preventFatalDamage(
                state, AbilityTrigger.fatalDamage(
                    attacker, attacker, move, component, fatalAmount, tick)));
        events.addAll(attacker.getCodedAbilities().drainPendingEvents(tick));
        events.add(CombatEvent.of(applied == 0
                ? CombatEvent.Type.DAMAGE_IGNORED : CombatEvent.Type.DAMAGE_DEALT)
            .source(attacker).target(attacker).move(move).intValue(applied).tick(tick)
            .build());
        if (applied > 0) {
            events.addAll(abilityActivations.process(state, AbilityTrigger.amount(
                AbilityTrigger.Type.DAMAGE, attacker, attacker, applied, tick)));
            wakeFromSleep(state, attacker, attacker, move, -1, tick, events);
        }
    }

    private void applyCursedSpeechCommandOutcome(
        BattleState state,
        BattleCombatant attacker,
        BattleCombatant defender,
        Move move,
        StatusEffect command,
        int componentIndex,
        int tick,
        List<CombatEvent> events
    ) {
        String mode = command.getCodedTarget() == null
            ? "" : command.getCodedTarget().toUpperCase(Locale.ROOT);
        switch (mode) {
            case CursedSpeechAbility.DONT_MOVE ->
                applyCommandStatus(state, attacker, defender, move,
                    StatusEffectType.STAGGER, 0, 6, componentIndex, tick, events);
            case CursedSpeechAbility.BLAST_AWAY ->
                applyCommandStatus(state, attacker, defender, move,
                    StatusEffectType.STAGGER, 0, 3, componentIndex, tick, events);
            case CursedSpeechAbility.SLEEP ->
                applyCommandStatus(state, attacker, defender, move,
                    StatusEffectType.SLEEP, 1, 0, componentIndex, tick, events);
            case CursedSpeechAbility.PLUMMET ->
                applyCommandStatus(state, attacker, defender, move,
                    StatusEffectType.STAGGER, 0, 4, componentIndex, tick, events);
            case CursedSpeechAbility.RETURN -> {
                if (defender.isSummon()) state.voluntarilyDesummon(defender);
            }
            case CursedSpeechAbility.DIE -> {
                int applied = defender.receiveInstantKill(
                    fatalAmount -> abilityActivations.preventFatalDamage(
                        state, AbilityTrigger.fatalDamage(
                            attacker, defender, move, null, fatalAmount, tick)));
                boolean defeated = defender.isDefeated();
                events.addAll(defender.getCodedAbilities().drainPendingEvents(tick));
                events.add(CombatEvent.of(applied == 0
                        ? CombatEvent.Type.DAMAGE_IGNORED : CombatEvent.Type.DAMAGE_DEALT)
                    .source(attacker).target(defender).move(move)
                    .componentIndex(componentIndex).intValue(applied).tick(tick)
                    .message(applied == 0
                        ? defender.getCharacter().getName() + " survives the command to die!"
                        : defeated
                            ? defender.getCharacter().getName()
                                + " is struck down by Cursed Speech!"
                            : defender.getCharacter().getName()
                                + " survives the command to die!")
                    .build());
                if (applied > 0) {
                    events.addAll(abilityActivations.process(state, AbilityTrigger.amount(
                        AbilityTrigger.Type.DAMAGE, attacker, defender, applied, tick)));
                    wakeFromSleep(
                        state, attacker, defender, move, componentIndex, tick, events);
                }
            }
            default -> { }
        }
    }

    private void applyCommandStatus(
        BattleState state,
        BattleCombatant attacker,
        BattleCombatant defender,
        Move move,
        StatusEffectType type,
        int rounds,
        int ticks,
        int componentIndex,
        int tick,
        List<CombatEvent> events
    ) {
        StatusEffect status = new StatusEffect(type, rounds, ticks, 0.0);
        if (!defender.addStatusEffect(status, state.getCurrentPhase())) return;
        events.add(CombatEvent.of(CombatEvent.Type.STATUS_APPLIED)
            .source(attacker).target(defender).move(move).componentIndex(componentIndex)
            .tick(tick).message(StatusEffectMessages.applicationMessage(
                attacker.getCharacter().getName(),
                defender.getCharacter().getName(),
                type,
                attacker == defender)).build());
        events.addAll(abilityActivations.process(state, AbilityTrigger.status(
            AbilityTrigger.Type.STATUS_APPLIED, defender, type, tick)));
        boolean cancelled = type != StatusEffectType.SLEEP
            && stunActiveSegments(defender, tick, false);
        if (cancelled) {
            events.add(CombatEvent.of(CombatEvent.Type.MOVE_STUNNED)
                .source(attacker).target(defender).tick(tick)
                .message(defender.getCharacter().getName() + " cannot act: "
                    + type.displayName() + " from " + attacker.getCharacter().getName()
                    + "'s " + move.getName() + "!")
                .build());
        }
    }

    private void wakeFromSleep(
        BattleState state,
        BattleCombatant source,
        BattleCombatant target,
        Move move,
        int componentIndex,
        int tick,
        List<CombatEvent> events
    ) {
        if (target == null || target.removeStatusEffects(StatusEffectType.SLEEP) == 0) return;
        events.add(CombatEvent.of(CombatEvent.Type.STATUS_EXPIRED)
            .source(source).target(target).move(move).componentIndex(componentIndex)
            .tick(tick).message(target.getCharacter().getName() + " wakes from Sleep!").build());
        events.addAll(abilityActivations.process(state, AbilityTrigger.status(
            AbilityTrigger.Type.STATUS_REMOVED, target, StatusEffectType.SLEEP, tick)));
    }

    private void applySelfEffects(
        BattleState state,
        BattleCombatant combatant,
        BattleCombatant defender,
        Move move,
        int tick,
        List<CombatEvent> events
    ) {
        for (StatusEffect authored : move.getSelfEffects()) {
            StatusEffect effect = TechniqueMasteryResolver.resolve(
                authored, TechniqueMasteryResolver.masteryOf(combatant));
            // A summon self row enqueues a shikigami onto the wielder's team at
            // unleash (equivalent to the legacy summonCharacterId field, now
            // expressed as an editable effect row).
            if (effect.isSummon()) {
                enqueueEffectSummon(state, combatant, effect.getSummonCharacterId(),
                    move, -1, tick, events);
                continue;
            }
            // A coded self row is dispatched to the matching compiled runtime
            // (fires on unleash, hit/miss/block agnostic) instead of being applied
            // as a status — this is how a technique move's hardcoded self/cast
            // behaviour is stored on an editable effect row.
            if (effect.isCoded()) {
                events.addAll(combatant.getCodedAbilities().onEffectFired(
                    state, effect, combatant, defender, tick));
                continue;
            }
            int previousMaxHp = combatant.getMaxHp();
            int previousMaxCe = combatant.getMaxCursedEnergy();
            if (!combatant.addStatusEffect(effect, state.getCurrentPhase())) continue;
            events.add(CombatEvent.of(CombatEvent.Type.STATUS_APPLIED)
                .source(combatant).move(move)
                .tick(tick)
                .message(StatusEffectMessages.applicationMessage(
                    combatant.getCharacter().getName(),
                    combatant.getCharacter().getName(),
                    effect.getType(),
                    true))
                .build());
            appendResourceMaximumEvents(
                combatant, combatant, previousMaxHp, previousMaxCe, tick, events);
            events.addAll(abilityActivations.process(state, AbilityTrigger.status(
                AbilityTrigger.Type.STATUS_APPLIED, combatant, effect.getType(), tick)));
        }
    }

    /**
     * Apply a defensive move's on-defense effect list (on-block / on-parry /
     * on-dodge). These fire when the defender's defense resolves the incoming
     * attack. Effects apply to the defender (the move's wielder), mirroring
     * {@link #applySelfEffects}; coded rows are dispatched to the defender's
     * runtime. A Defensive+Attack hybrid with launch mode ON_DEFENCE launches
     * its attack afterwards (see {@link #launchDefenceCounter}).
     */
    private void applyDefenseEffects(
        BattleState state,
        BattleCombatant defender,
        BattleCombatant attacker,
        Move move,
        MoveEffectTrigger trigger,
        List<StatusEffect> effects,
        int componentIndex,
        int tick,
        List<CombatEvent> events,
        MoveExecution incomingExecution
    ) {
        if (move != null && move.usesUnifiedEffects()) {
            events.addAll(abilityActivations.processMoveEffects(
                state, defender, attacker, move, trigger, -1, tick));
            launchDefenceCounter(
                state, defender, attacker, move, trigger, tick, events, incomingExecution);
            return;
        }
        launchDefenceCounter(
            state, defender, attacker, move, trigger, tick, events, incomingExecution);
        if (effects == null || effects.isEmpty()) return;
        for (StatusEffect authored : effects) {
            StatusEffect effect = TechniqueMasteryResolver.resolve(
                authored, TechniqueMasteryResolver.masteryOf(defender));
            // A summon on-defense row enqueues a shikigami onto the defender's
            // team when their defense resolves the incoming attack.
            if (effect.isSummon()) {
                enqueueEffectSummon(state, defender, effect.getSummonCharacterId(),
                    move, componentIndex, tick, events);
                continue;
            }
            if (effect.isCoded()) {
                events.addAll(defender.getCodedAbilities().onEffectFired(
                    state, effect, defender, attacker, tick));
                continue;
            }
            int previousMaxHp = defender.getMaxHp();
            int previousMaxCe = defender.getMaxCursedEnergy();
            if (!defender.addStatusEffect(effect, state.getCurrentPhase())) continue;
            events.add(CombatEvent.of(CombatEvent.Type.STATUS_APPLIED)
                .source(defender).move(move)
                .componentIndex(componentIndex)
                .tick(tick)
                .message(StatusEffectMessages.applicationMessage(
                    defender.getCharacter().getName(),
                    defender.getCharacter().getName(),
                    effect.getType(),
                    true))
                .build());
            appendResourceMaximumEvents(
                defender, defender, previousMaxHp, previousMaxCe, tick, events);
            events.addAll(abilityActivations.process(state, AbilityTrigger.status(
                AbilityTrigger.Type.STATUS_APPLIED, defender, effect.getType(), tick)));
        }
    }

    /**
     * A Defensive+Attack hybrid with launch mode ON_DEFENCE launches its attack
     * when its defence successfully resolves an incoming attack, targeting the
     * attacker it just defended against. A referenced move launches through the
     * shared mid-resolution launcher (its own CE cost is paid at launch); a
     * custom attack schedules this move's own hit components against the
     * attacker without re-granting the defence or re-firing on-fire rows.
     *
     * <p>One attempt per defender per incoming execution: a multi-hit attack
     * defended component-by-component must not trigger the counter repeatedly.</p>
     */
    private void launchDefenceCounter(
        BattleState state,
        BattleCombatant defender,
        BattleCombatant attacker,
        Move move,
        MoveEffectTrigger trigger,
        int tick,
        List<CombatEvent> events,
        MoveExecution incomingExecution
    ) {
        if (move == null || !move.launchesAttackOnDefence()) return;
        if (attacker == null || !defender.isActive() || !attacker.isActive()) return;
        if (trigger != defenceResolutionTrigger(move.getDefenseType())) return;
        if (incomingExecution == null
            || !incomingExecution.markCounterAttempt(defender.getInstanceId())) return;
        if (!abilityActivations.allowsAttackLaunch(state, defender, attacker, move, tick)) {
            return;
        }
        if (move.referencesAttackMove()) {
            Move referenced = move.getAttackLaunchMove();
            if (referenced != null) {
                resolveLaunchedMove(
                    referenced, defender, TargetSet.single(attacker),
                    state, tick, events, true);
            }
            return;
        }
        if (move.getHitComponents().isEmpty()) return;
        if ((long) tick + move.getMaxHitDelayTicks() > cursor.get().gridLimit) return;
        events.add(CombatEvent.of(CombatEvent.Type.MOVE_FIRED)
            .source(defender).move(move).tick(tick)
            .message(defender.getCharacter().getName() + " counterattacked with "
                + move.getName() + "!")
            .build());
        // The counter reuses the move's own already-paid segment cost; a fresh
        // synthetic segment carries its components against the attacker.
        ActionSegment counterSegment = new ActionSegment(move, tick, 0);
        MoveExecution execution = new MoveExecution(
            new FiringEntry(counterSegment, defender), Map.of(), tick,
            cursor.get().nextLaunchSequence++, List.of(attacker));
        scheduleComponents(execution);
        resolvePendingComponentsAtTick(state, tick, events);
        finishBattleIfNeeded(state, events, tick);
    }

    /** The effect trigger that matches a defence type's successful resolution. */
    private static MoveEffectTrigger defenceResolutionTrigger(DefenseType defenseType) {
        return switch (defenseType) {
            case BLOCK -> MoveEffectTrigger.ON_BLOCK;
            case PARRY -> MoveEffectTrigger.ON_PARRY;
            case DODGE -> MoveEffectTrigger.ON_DODGE;
            default    -> null;
        };
    }

    private void applyAbilityOnHitEffects(
        BattleState state,
        BattleCombatant attacker,
        BattleCombatant defender,
        Move move,
        int componentIndex,
        int tick,
        List<CombatEvent> events
    ) {
        for (AbilityEffectData effect : attacker.getAbilityFlags().autoStatusEffects) {
            if (!AbilityEffectTiming.ON_HIT.name().equals(effect.timing)) continue;
            List<BattleCombatant> targets = AbilityEffectTarget.BOTH.name().equals(effect.target)
                ? List.of(attacker, defender)
                : List.of(AbilityEffectTarget.ENEMY.name().equals(effect.target) ? defender : attacker);
            for (BattleCombatant target : targets) {
                int previousMaxHp = target.getMaxHp();
                int previousMaxCe = target.getMaxCursedEnergy();
                if (!target.addAutomaticStatusEffect(effect, state.getCurrentPhase())) continue;
                events.add(CombatEvent.of(CombatEvent.Type.STATUS_APPLIED)
                    .source(attacker).target(target).move(move)
                    .componentIndex(componentIndex)
                    .tick(tick)
                    .message(StatusEffectMessages.applicationMessage(
                        attacker.getCharacter().getName(),
                        target.getCharacter().getName(),
                        StatusEffectType.fromName(
                            effect.stringValue,
                            effect.magnitude != null ? effect.magnitude : 0.0),
                        attacker == target))
                    .build());
                appendResourceMaximumEvents(
                    attacker, target, previousMaxHp, previousMaxCe, tick, events);
                try {
                    events.addAll(abilityActivations.process(state, AbilityTrigger.status(
                        AbilityTrigger.Type.STATUS_APPLIED,
                        target,
                        StatusEffectType.fromName(
                            effect.stringValue, effect.magnitude != null ? effect.magnitude : 0.0),
                        tick)));
                } catch (IllegalArgumentException ignored) { }
            }
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
        // Round-end maintenance runs over every present combatant (fighters and
        // active summons alike). Removed combatants are skipped.
        List<BattleCombatant> combatants = state.presentCombatants();
        Map<BattleCombatant, Integer> previousMaxHp = new LinkedHashMap<>();
        Map<BattleCombatant, Integer> previousMaxCe = new LinkedHashMap<>();
        Set<BattleCombatant> hpClamped = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<BattleCombatant> ceClamped = Collections.newSetFromMap(new IdentityHashMap<>());

        events.addAll(abilityActivations.process(
            state, AbilityTrigger.phase(BattleState.Phase.ROUND_END)));
        if (finishBattleIfNeeded(state, events, 0)) return events;

        for (BattleCombatant combatant : combatants) {
            previousMaxHp.put(combatant, combatant.getMaxHp());
            previousMaxCe.put(combatant, combatant.getMaxCursedEnergy());
            combatant.beginPoolClampDeferral();
        }

        boolean battleEnded = false;
        try {
            for (BattleCombatant combatant : combatants) {
                combatant.tickRoundEffects(round);
                expiredByCombatant.put(combatant, combatant.drainExpiredStatusEffects());
                boolean wasBfs = combatant.isInBlackFlashState();
                combatant.tickBfsExpiry(round);
                if (wasBfs && !combatant.isInBlackFlashState()) {
                    events.add(CombatEvent.of(CombatEvent.Type.BFS_EXPIRED)
                        .source(combatant)
                        .build());
                }
            }
            state.markRoundEndMaintenanceComplete();

            // Dispatch expiry predicates only after both sides have ticked, so an
            // effect granted to the second combatant cannot immediately lose a round.
            expiryEvents:
            for (Map.Entry<BattleCombatant, List<StatusEffect>> entry
                : expiredByCombatant.entrySet()) {
                BattleCombatant combatant = entry.getKey();
                for (StatusEffect expired : entry.getValue()) {
                    events.add(CombatEvent.of(CombatEvent.Type.STATUS_EXPIRED)
                        .source(combatant)
                        .message(StatusEffectMessages.expiryMessage(
                            combatant.getCharacter().getName(), expired.getType()))
                        .build());
                    events.addAll(abilityActivations.process(state, AbilityTrigger.status(
                        AbilityTrigger.Type.STATUS_REMOVED, combatant, expired.getType(), 0)));
                    if (finishBattleIfNeeded(state, events, 0)) {
                        battleEnded = true;
                        break expiryEvents;
                    }
                }
            }

            if (!battleEnded) state.endRound();
        } finally {
            for (BattleCombatant combatant : combatants) {
                int hpBeforeClamp = combatant.getCurrentHp();
                int ceBeforeClamp = combatant.getCurrentCe();
                combatant.endPoolClampDeferral();
                if (combatant.getCurrentHp() != hpBeforeClamp) hpClamped.add(combatant);
                if (combatant.getCurrentCe() != ceBeforeClamp) ceClamped.add(combatant);
            }
        }

        for (BattleCombatant combatant : combatants) {
            appendResourceMaximumEvents(
                null, combatant,
                hpClamped.contains(combatant) ? -1 : previousMaxHp.get(combatant),
                ceClamped.contains(combatant) ? -1 : previousMaxCe.get(combatant),
                0, events);
        }
        if (battleEnded) return events;

        if (finishBattleIfNeeded(state, events, 0)) return events;

        events.add(CombatEvent.of(CombatEvent.Type.ROUND_END)
            .message("--- Round " + round + " complete. Starting Round " + state.getRoundNumber() + " ---")
            .build());

        return events;
    }

    private boolean finishBattleIfNeeded(
        BattleState state,
        List<CombatEvent> events,
        int tick
    ) {
        reconcileLifecycle(state, tick, events);
        if (!cursor.get().deferSummonMaterialization) {
            materializePendingSummons(state, tick, events);
        }
        if (!state.checkAndResolveBattleOver()) return false;
        ResolutionCursor c = cursor.get();
        c.pendingComponents.clear();
        c.maxTick = c.tick;
        c.roundCostsProcessed = false;
        if (events.stream().noneMatch(event -> event.getType() == CombatEvent.Type.BATTLE_OVER)) {
            String message = state.getWinner() == null
                ? "The battle ends in a draw!"
                : state.getWinner().getCharacter().getName() + " wins the battle!";
            events.add(CombatEvent.of(CombatEvent.Type.BATTLE_OVER)
                .source(state.getWinner()).tick(tick).message(message).build());
        }
        return true;
    }

    /**
     * Emit explicit events for combatants whose lifecycle just changed: defeat
     * (a fighter/summon reaching 0 HP) and removal (a dismissed/destroyed summon).
     * Defeated fighters are not removed (they remain for end-of-round reckoning);
     * defeated summons are removed.
     */
    private void emitLifecycleEvents(
        List<BattleCombatant> changed,
        int tick,
        List<CombatEvent> events
    ) {
        for (BattleCombatant c : changed) {
            boolean defeated = c.isLifecycleDefeated() || (c.isRemoved() && c.isDefeated());
            if (defeated) {
                events.add(CombatEvent.of(CombatEvent.Type.COMBATANT_DEFEATED)
                    .target(c).tick(tick)
                    .message(c.isSummon() ? "" : c.getCharacter().getName() + " is defeated!")
                    .build());
            }
            if (c.isRemoved()) {
                events.add(CombatEvent.of(CombatEvent.Type.COMBATANT_REMOVED)
                    .target(c).tick(tick)
                    .message(c.getCharacter().getName()
                        + (c.isSummon() && defeated
                            ? " is destroyed!"
                            : c.isSummon() ? " is dismissed!" : " is removed!"))
                    .build());
            }
        }
    }

    private void reconcileLifecycle(
        BattleState state,
        int tick,
        List<CombatEvent> events
    ) {
        List<BattleCombatant> changed = state.reconcileDefeats();
        if (!state.usesLegacySingleCombatantConstruction()) {
            emitLifecycleEvents(changed, tick, events);
        }
    }

    /**
     * Materialize every pending summon and broadcast each join. Called the
     * moment a summon is enqueued (the broadcast of the summoning action), so
     * the new combatant is active for every later move this tick; the call at
     * the end of a tick only catches summons enqueued outside those paths
     * (e.g. round-start abilities during planning).
     */
    private void materializePendingSummons(
        BattleState state,
        int tick,
        List<CombatEvent> events
    ) {
        if (summonLookup == null) return;
        for (BattleCombatant summon : state.drainPendingSummons(summonLookup)) {
            BattleCombatant summoner = state.combatant(summon.getSummonerId());
            events.add(CombatEvent.summoned(summoner, summon, tick));
        }
    }

    private void appendAutomaticStatusEvents(BattleState state, List<CombatEvent> events) {
        for (BattleState.AutomaticStatusApplication application
            : state.drainAutomaticStatusApplications()) {
            events.add(CombatEvent.of(CombatEvent.Type.STATUS_APPLIED)
                .source(application.source())
                .target(application.target())
                .message(StatusEffectMessages.applicationMessage(
                    application.source().getCharacter().getName(),
                    application.target().getCharacter().getName(),
                    application.status(),
                    application.source() == application.target()))
                .build());
            appendResourceMaximumEvents(
                application.source(), application.target(),
                application.previousMaxHp(), application.previousMaxCe(),
                application.resultingMaxHp(), application.resultingMaxCe(), 0, events);
            events.addAll(abilityActivations.process(state, AbilityTrigger.status(
                AbilityTrigger.Type.STATUS_APPLIED,
                application.target(),
                application.status(),
                0)));
            if (finishBattleIfNeeded(state, events, 0)) break;
        }
    }

    private static void appendResourceMaximumEvents(
        BattleCombatant source,
        BattleCombatant combatant,
        int previousMaxHp,
        int previousMaxCe,
        int tick,
        List<CombatEvent> events
    ) {
        appendResourceMaximumEvents(
            source, combatant, previousMaxHp, previousMaxCe,
            combatant.getMaxHp(), combatant.getMaxCursedEnergy(), tick, events);
    }

    private static void appendResourceMaximumEvents(
        BattleCombatant source,
        BattleCombatant combatant,
        int previousMaxHp,
        int previousMaxCe,
        int resultingMaxHp,
        int resultingMaxCe,
        int tick,
        List<CombatEvent> events
    ) {
        if (resultingMaxHp != previousMaxHp) {
            events.add(CombatEvent.of(CombatEvent.Type.MAX_HP_CHANGED)
                .source(source).target(combatant).intValue(resultingMaxHp).tick(tick)
                .build());
        }
        if (resultingMaxCe != previousMaxCe) {
            events.add(CombatEvent.of(CombatEvent.Type.MAX_CE_CHANGED)
                .source(source).target(combatant).intValue(resultingMaxCe).tick(tick)
                .build());
        }
    }
}
