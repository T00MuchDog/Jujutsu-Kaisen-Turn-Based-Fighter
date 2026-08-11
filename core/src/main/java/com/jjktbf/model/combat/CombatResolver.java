package com.jjktbf.model.combat;

import com.jjktbf.model.character.CombatStats;
import com.jjktbf.model.character.AbilityEffectData;
import com.jjktbf.model.character.AbilityEffectTarget;
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
        this.abilityActivations = new AbilityActivationEngine(rng);
        this.summonLookup = summonLookup;
    }

    /**
     * Inject the character lookup used to resolve summon character ids at runtime,
     * so the engine can materialize shikigami without loading files itself.
     */
    public CombatResolver withSummonLookup(BattleCharacterLookup lookup) {
        this.summonLookup = lookup;
        return this;
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
        c.activeBlocks.clear();

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

            if (isChargeableTick(state, tick)) {
                chargeSummonUpkeep(state, tick, events);
                if (finishBattleIfNeeded(state, events, tick)) return events;
            }

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

        // --- Detect defensive blocks whose AP window just ended (active → inactive) ---
            for (BattleCombatant combatant : state.activeCombatants()) {
                detectExpiredBlocks(combatant, tick, events);
            }

            processTimelineEffectExpiry(state, tick, events);
            if (finishBattleIfNeeded(state, events, tick)) return events;
            updateResolutionEndForTimelineEffects(state);

            // Summons created during this tick are visible only after every firing
            // entry has resolved, so later same-tick AOE cannot acquire them.
            materializePendingSummons(state, tick, events);
            finishBattleIfNeeded(state, events, tick);
            return events;
        } finally {
            c.deferSummonMaterialization = false;
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
            int due = summoner.accrueSummonCeUpkeep(rate);
            if (due <= 0) continue;
            int drained = summoner.drainCe(due);
            if (drained <= 0) continue;
            events.add(CombatEvent.of(CombatEvent.Type.CE_DRAINED)
                .source(summoner).intValue(drained).tick(tick)
                .message(summoner.getCharacter().getName() + " spends " + drained
                    + " CE maintaining summoned shikigami.")
                .build());
            events.addAll(abilityActivations.process(state, AbilityTrigger.amount(
                AbilityTrigger.Type.CE_LOST, summoner, null, drained, tick)));
            if (!summoner.hasAnyCe()) {
                events.add(CombatEvent.of(CombatEvent.Type.CE_DEPLETED)
                    .source(summoner).tick(tick)
                    .message(summoner.getCharacter().getName()
                        + " has exhausted all Cursed Energy!")
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
                events.addAll(abilityActivations.process(state, AbilityTrigger.amount(
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
            events.addAll(abilityActivations.process(state, AbilityTrigger.amount(
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
        int aSpeed = a.attacker.getEffectiveStats().getSpeed();
        int bSpeed = b.attacker.getEffectiveStats().getSpeed();
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

        resolveMove(entry, targets, state, tick, events, fullBlockByTarget);
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
     *       the caster, taken now so summons created afterward are excluded.</li>
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

    private void resolveReactionMove(
        Move reactionMove,
        BattleCombatant reactor,
        BattleCombatant target,
        BattleState state,
        int tick,
        List<CombatEvent> events
    ) {
        if (reactor == null || !reactor.isActive()) return;
        if ((long) tick + reactionMove.getMaxHitDelayTicks() > cursor.get().gridLimit) {
            return;
        }
        if (reactor.consumeMoveCancellation()) {
            events.add(CombatEvent.of(CombatEvent.Type.MOVE_STUNNED)
                .target(reactor).move(reactionMove).tick(tick)
                .message(reactor.getCharacter().getName() + "'s "
                    + reactionMove.getName() + " was cancelled by an ability!")
                .build());
            return;
        }
        int cost = reactor.computeMoveCeCost(reactionMove);
        if (!reactor.hasCe(cost)) {
            events.add(CombatEvent.of(CombatEvent.Type.CE_DEPLETED)
                .source(reactor).move(reactionMove).tick(tick)
                .message(reactor.getCharacter().getName() + " does not have enough CE for "
                    + reactionMove.getName() + "!")
                .build());
            return;
        }
        if (cost > 0) {
            int drained = reactor.drainCe(cost);
            events.add(CombatEvent.of(CombatEvent.Type.CE_DRAINED)
                .source(reactor).move(reactionMove).intValue(drained).tick(tick)
                .message(reactor.getCharacter().getName() + " uses " + drained
                    + " CE for " + reactionMove.getName())
                .build());
            events.addAll(abilityActivations.process(state, AbilityTrigger.amount(
                AbilityTrigger.Type.CE_SPENT, reactor, null, drained, tick)));
            if (finishBattleIfNeeded(state, events, tick)) return;
            if (!reactor.hasAnyCe()) {
                events.add(CombatEvent.of(CombatEvent.Type.CE_DEPLETED)
                    .source(reactor).tick(tick)
                    .message(reactor.getCharacter().getName()
                    + " has exhausted all Cursed Energy!")
                    .build());
            }
        }

        ActionSegment reactionSegment = new ActionSegment(reactionMove, tick, cost);
        // A reaction move targets the attacker that triggered it.
        resolveMove(
            new FiringEntry(reactionSegment, reactor),
            target == null ? TargetSet.empty() : TargetSet.single(target),
            state, tick, events, Map.of());
    }

    private void resolveMove(
        FiringEntry       entry,
        TargetSet         targets,
        BattleState       state,
        int               tick,
        List<CombatEvent> events,
        Map<CombatantId, Boolean> forceFullBlockByTarget
    ) {
        ActionSegment   segment  = entry.segment;
        Move            move     = segment.getMove();
        BattleCombatant attacker = entry.attacker;
        if (!attacker.isActive()) return;

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
        // MOVE_FIRED fires once, regardless of how many targets the move hits.
        events.addAll(abilityActivations.process(state, AbilityTrigger.move(
            AbilityTrigger.Type.MOVE_USED, attacker, targets.primary(), move, tick)));
        if (finishBattleIfNeeded(state, events, tick)) return;

        // --- Self-effects apply on unleash, for every move type (damaging,
        // defensive, and utility alike). A move that buffs its user when cast
        // (e.g. a CE strike that raises Power) fires the buff here, regardless
        // of whether the attack later hits, misses, or is blocked. Charged once.
        if (move.usesUnifiedEffects()) {
            events.addAll(abilityActivations.processMoveEffects(
                state, attacker, targets.all(), move,
                MoveEffectTrigger.ON_FIRE, -1, tick));
        } else {
            applySelfEffects(state, attacker, targets.primary(), move, tick, events);
        }
        if (finishBattleIfNeeded(state, events, tick)) return;
        if (!attacker.isActive()) return;

        // --- Summon: a move that carries a summonCharacterId enqueues a shikigami
        // at its unleash point (works on utility and attack moves alike). The
        // summon is materialized after the current tick batch via the shared
        // runtime summon path, so it joins the firing list only next round.
        if (!move.usesUnifiedEffects() && move.summonsCharacter()) {
            state.enqueueSummon(attacker, move.getSummonCharacterId(),
                move.getTags().contains(MoveTag.INNATE_TECHNIQUE));
        }

        // --- Defensive moves: apply buff or register full block ---
        if (move.isDefensive()) {
            resolveDefensiveMove(attacker, move, tick, events);
            return; // defensive moves don't attack
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
                .message(defender.getCharacter().getName() + " dodged " + move.getName() + "!")
                .build());
            applyDefenseEffects(state, defender, attacker, defenseMove,
                MoveEffectTrigger.ON_DODGE,
                defenseMove == null ? List.of() : defenseMove.getOnDodgeEffects(),
                componentIndex, tick, events);
            return false;
        }

        if (result.isParried()) {
            Move defenseMove = defenseMove(result);
            events.add(CombatEvent.of(CombatEvent.Type.MOVE_PARRIED)
                .source(attacker).target(defender).move(move).componentIndex(componentIndex)
                .tick(tick)
                .message(defender.getCharacter().getName() + " parried " + move.getName() + "!")
                .build());
            if (result.staggersAttacker()) {
                attacker.addStatusEffect(
                    new StatusEffect(StatusEffectType.STAGGER, 0,
                                     result.getParryStaggerTicks(), 0.0),
                    state.getCurrentPhase());
                events.add(CombatEvent.of(CombatEvent.Type.STATUS_APPLIED)
                    .source(defender).target(attacker).move(move).componentIndex(componentIndex)
                    .tick(tick)
                    .message(defender.getCharacter().getName() + "'s parry applies "
                             + StatusEffectType.STAGGER.displayName() + " to "
                             + attacker.getCharacter().getName() + "!")
                    .build());
                stunActiveSegments(attacker, tick, false);
            }
            applyDefenseEffects(state, defender, attacker, defenseMove,
                MoveEffectTrigger.ON_PARRY,
                defenseMove == null ? List.of() : defenseMove.getOnParryEffects(),
                componentIndex, tick, events);
            events.addAll(abilityActivations.process(state, AbilityTrigger.move(
                AbilityTrigger.Type.MOVE_BLOCKED, attacker, defender, move, tick)));
            return false;
        }

        if (result.isBlocked()) {
            Move defenseMove = defenseMove(result);
            events.add(CombatEvent.of(CombatEvent.Type.MOVE_BLOCKED)
                .source(attacker).target(defender).move(move).componentIndex(componentIndex)
                .tick(tick)
                .message(defender.getCharacter().getName() + " blocked " + move.getName() + "!")
                .build());
            applyDefenseEffects(state, defender, attacker, defenseMove,
                MoveEffectTrigger.ON_BLOCK,
                defenseMove == null ? List.of() : defenseMove.getOnBlockEffects(),
                componentIndex, tick, events);
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
                componentIndex, tick, events);
        }

        if (component.getBasePower() > 0 || appliedDamage > 0) {
            events.add(CombatEvent.of(appliedDamage == 0
                    ? CombatEvent.Type.DAMAGE_IGNORED : CombatEvent.Type.DAMAGE_DEALT)
                .source(attacker).target(defender).move(move).componentIndex(componentIndex)
                .intValue(appliedDamage)
                .tick(tick)
                .message(appliedDamage == 0
                    ? defender.getCharacter().getName() + " ignores all damage from " + move.getName() + "!"
                    : attacker.getCharacter().getName() + "'s " + move.getName()
                        + " hits " + defender.getCharacter().getName()
                        + " for " + appliedDamage + " damage!"
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

            events.add(CombatEvent.of(CombatEvent.Type.BLACK_FLASH)
                .source(attacker).target(defender).move(move).componentIndex(componentIndex)
                .intValue(result.getFinalDamage())
                .tick(tick)
                .message("*** BLACK FLASH! *** " + attacker.getCharacter().getName()
                         + " lands a Black Flash! +" + ceRestored + " CE restored!")
                .build());
            events.add(CombatEvent.of(CombatEvent.Type.CE_RESTORED)
                .source(attacker).intValue(ceRestored).componentIndex(componentIndex)
                .tick(tick)
                .message(attacker.getCharacter().getName() + " recovered " + ceRestored + " CE!")
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
        if (move.isStun()) resolveStunTag(
            attacker, defender, move, componentIndex, tick, events);
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
        BattleCombatant   attacker,
        BattleCombatant   defender,
        Move              move,
        int               componentIndex,
        int               tick,
        List<CombatEvent> events
    ) {
        if (stunActiveSegments(defender, tick, true)) {
            events.add(CombatEvent.of(CombatEvent.Type.MOVE_STUNNED)
                .source(attacker).target(defender)
                .move(move)
                .componentIndex(componentIndex)
                .tick(tick)
                .message(attacker.getCharacter().getName() + "'s " + move.getName()
                         + " stunned " + defender.getCharacter().getName()
                         + ", who could not move.")
                .build());
        }
    }

    /** Apply STAGGER's ongoing stun. HEAVY only resists the STUN move tag. */
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
    // Defensive move resolution
    // -------------------------------------------------------------------------

    private void resolveDefensiveMove(BattleCombatant combatant, Move move, int tick, List<CombatEvent> events) {
        String msg = move.defenseActivationMessage(combatant.getCharacter().getName());
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
            if (!segment.getMove().isActiveDefense()) continue;
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
            String msg = move.defenseExpiryMessage(combatant.getCharacter().getName());
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
            String msg = move.defenseExpiryMessage(combatant.getCharacter().getName());
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

    /**
     * Enqueue a shikigami summon requested by a move effect row (self / on-hit /
     * on-defense). Shares the runtime path with the legacy {@code summonCharacterId}
     * unleash block and ability {@code SUMMON_CHARACTER} effect: the summon is
     * materialized after the current tick batch via {@code drainPendingSummons}.
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
            .message(summoner.getCharacter().getName()
                + "'s " + move.getName() + " summons a shikigami!")
            .build());
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
            .message(applied == 0
                ? attacker.getCharacter().getName() + " avoids the recoil from " + move.getName() + "."
                : attacker.getCharacter().getName() + " takes " + applied
                    + " recoil damage from commanding " + recoilTargetLabel(targets) + "!")
            .build());
        if (applied > 0) {
            events.addAll(abilityActivations.process(state, AbilityTrigger.amount(
                AbilityTrigger.Type.DAMAGE, attacker, attacker, applied, tick)));
            wakeFromSleep(state, attacker, attacker, move, -1, tick, events);
        }
    }

    private static String recoilTargetLabel(List<BattleCombatant> targets) {
        if (targets != null && targets.size() == 1 && targets.get(0) != null) {
            return targets.get(0).getCharacter().getName();
        }
        return (targets == null ? 0 : targets.size()) + " targets";
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
                            : defender.getCharacter().getName() + " takes " + applied
                                + " damage but survives the command to die!")
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
     * runtime.
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
        List<CombatEvent> events
    ) {
        if (move != null && move.usesUnifiedEffects()) {
            events.addAll(abilityActivations.processMoveEffects(
                state, defender, attacker, move, trigger, -1, tick));
            return;
        }
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
                flushRemainingBlocks(combatant, events);

                boolean wasBfs = combatant.isInBlackFlashState();
                combatant.tickBfsExpiry(round);
                if (wasBfs && !combatant.isInBlackFlashState()) {
                    events.add(CombatEvent.of(CombatEvent.Type.BFS_EXPIRED)
                        .source(combatant)
                        .message(combatant.getCharacter().getName()
                            + "'s Black Flash State has ended.")
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
                    .message(c.getCharacter().getName() + " is defeated!")
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

    private void materializePendingSummons(
        BattleState state,
        int tick,
        List<CombatEvent> events
    ) {
        if (summonLookup == null) return;
        for (BattleCombatant summon : state.drainPendingSummons(summonLookup)) {
            BattleCombatant summoner = state.combatant(summon.getSummonerId());
            events.add(CombatEvent.of(CombatEvent.Type.COMBATANT_SUMMONED)
                .source(summoner).target(summon).tick(tick)
                .message(summon.getCharacter().getName() + " joins the battle!")
                .build());
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
                .message(combatant.getCharacter().getName() + "'s max HP is now "
                    + resultingMaxHp + ".").build());
        }
        if (resultingMaxCe != previousMaxCe) {
            events.add(CombatEvent.of(CombatEvent.Type.MAX_CE_CHANGED)
                .source(source).target(combatant).intValue(resultingMaxCe).tick(tick)
                .message(combatant.getCharacter().getName() + "'s max CE is now "
                    + resultingMaxCe + ".").build());
        }
    }
}
