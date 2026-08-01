package com.jjktbf.model.combat;

import com.jjktbf.model.character.CombatStats;
import com.jjktbf.model.character.AbilityEffectData;
import com.jjktbf.model.character.AbilityEffectTarget;
import com.jjktbf.model.character.AbilityEffectTiming;
import com.jjktbf.model.character.coded.CodedMoveResponse;
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

    public CombatResolver(RandomSource rng) {
        this.rng = rng;
        this.abilityActivations = new AbilityActivationEngine(rng);
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
            || (owner != state.getPlayerCombatant() && owner != state.getEnemyCombatant())) {
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
        boolean battleStart = state.getPlayerCombatant().beginAbilityFightStart()
            | state.getEnemyCombatant().beginAbilityFightStart();
        if (battleStart) {
            events.addAll(abilityActivations.process(
                state, AbilityTrigger.simple(AbilityTrigger.Type.BATTLE_START)));
            if (finishBattleIfNeeded(state, events, 0)) return events;
        }
        boolean roundStart = state.getPlayerCombatant().beginAbilityRoundStart(state.getRoundNumber())
            | state.getEnemyCombatant().beginAbilityRoundStart(state.getRoundNumber());
        if (roundStart) {
            events.addAll(abilityActivations.process(state, AbilityTrigger.simple(AbilityTrigger.Type.ROUND_START)));
            if (finishBattleIfNeeded(state, events, 0)) return events;
            events.addAll(abilityActivations.process(state, AbilityTrigger.phase(BattleState.Phase.PLANNING)));
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
        int actionMaxTick;
        int gridLimit;
        long nextLaunchSequence;
        boolean roundCostsProcessed;
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
        c.actionMaxTick = maxTick;
        c.gridLimit = Math.max(
            playerTimeline == null ? 0 : playerTimeline.getGridLength(),
            enemyTimeline == null ? 0 : enemyTimeline.getGridLength());
        if (c.gridLimit == 0) c.gridLimit = BattlePlan.GRID_LENGTH;
        c.roundCostsProcessed = true;
        c.nextLaunchSequence = 0;
        c.pendingComponents.clear();
        c.activeBlocks.clear();

        events.addAll(abilityActivations.process(state, AbilityTrigger.phase(BattleState.Phase.RESOLUTION)));
        if (finishBattleIfNeeded(state, events, 0)) {
            c.roundCostsProcessed = false;
            return events;
        }
        updateResolutionEndForTimelineEffects(player, enemy);
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

        events.addAll(abilityActivations.process(state, AbilityTrigger.tick(tick)));
        if (finishBattleIfNeeded(state, events, tick)) return events;

        // STAGGER is a character status, not a move tag. It acts before any
        // segment can begin or fire on this AP tick.
        applyActiveStaggers(player, tick, events);
        applyActiveStaggers(enemy, tick, events);

        // --- CE drain when a segment starts ---
        drainCeForStartingSegments(state, player, tick, events);
        if (finishBattleIfNeeded(state, events, tick)) return events;
        drainCeForStartingSegments(state, enemy,  tick, events);
        if (finishBattleIfNeeded(state, events, tick)) return events;

        // Impacts committed by earlier launches resolve before anything new is
        // unleashed on this tick. They remain valid even if the source segment
        // was subsequently stunned.
        resolvePendingComponentsAtTick(state, player, enemy, tick, events);
        if (finishBattleIfNeeded(state, events, tick)) return events;
        applyActiveStaggers(player, tick, events);
        applyActiveStaggers(enemy, tick, events);

        // --- Collect all moves firing this tick ---
        List<FiringEntry> firing = collectFiringMoves(player, enemy, tick);

        // --- Sort by priority ---
        sortFiringEntries(firing);

        // --- Resolve each firing move ---
        for (FiringEntry entry : firing) {
            // A stagger applied by an earlier same-tick move takes effect before
            // the next queued move gets a chance to resolve.
            applyActiveStaggers(player, tick, events);
            applyActiveStaggers(enemy, tick, events);
            if (entry.segment.isStunned()) continue;
            if (finishBattleIfNeeded(state, events, tick)) return events;
            resolveMove(entry, player, enemy, state, tick, events);
            // This also handles a stagger that lands while the target is charging
            // and has no separate move firing later on the same tick.
            applyActiveStaggers(player, tick, events);
            applyActiveStaggers(enemy, tick, events);
            if (finishBattleIfNeeded(state, events, tick)) return events;
        }

        // --- Detect defensive blocks whose AP window just ended (active → inactive) ---
        detectExpiredBlocks(player, tick, events);
        detectExpiredBlocks(enemy,  tick, events);

        processTimelineEffectExpiry(state, tick, events);
        if (!state.isBattleOver()) updateResolutionEndForTimelineEffects(player, enemy);

        return events;
    }

    private void updateResolutionEndForTimelineEffects(
        BattleCombatant player,
        BattleCombatant enemy
    ) {
        ResolutionCursor c = cursor.get();
        int remainingTicks = Math.max(
            player.getRemainingTimelineEffectTicks(),
            enemy.getRemainingTimelineEffectTicks());
        long timerEnd = remainingTicks <= 0
            ? 0L : Math.min((long) c.gridLimit, (long) c.tick + remainingTicks);
        c.maxTick = Math.max(c.actionMaxTick, (int) timerEnd);
    }

    private void processTimelineEffectExpiry(
        BattleState state,
        int tick,
        List<CombatEvent> events
    ) {
        BattleCombatant[] combatants = {
            state.getPlayerCombatant(), state.getEnemyCombatant()
        };
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

    private record FiringEntry(ActionSegment segment, BattleCombatant attacker, BattleCombatant defender) {}

    private static final class MoveExecution {
        private final FiringEntry entry;
        private final boolean forceFullBlock;
        private final int launchTick;
        private final long launchSequence;
        private final boolean[] connected;

        private MoveExecution(
            FiringEntry entry,
            boolean forceFullBlock,
            int launchTick,
            long launchSequence
        ) {
            this.entry = entry;
            this.forceFullBlock = forceFullBlock;
            this.launchTick = launchTick;
            this.launchSequence = launchSequence;
            this.connected = new boolean[entry.segment.getMove().getHitComponents().size()];
        }
    }

    private record PendingComponent(MoveExecution execution, int componentIndex) {}

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
        Move incomingMove = entry.segment.getMove();
        CodedMoveResponse response = abilityActivations.beforeIncomingMove(
            state, AbilityTrigger.incomingMove(
                entry.attacker, entry.defender, incomingMove, tick));
        events.addAll(response.events());

        for (Move reactionMove : response.reactionMoves()) {
            resolveReactionMove(
                reactionMove, entry.defender, entry.attacker, player, enemy, state, tick, events);
            applyActiveStaggers(player, tick, events);
            applyActiveStaggers(enemy, tick, events);
            if (entry.segment.isStunned() || finishBattleIfNeeded(state, events, tick)) return;
        }

        resolveMove(entry, player, enemy, state, tick, events, response.fullBlock());
    }

    private void resolveReactionMove(
        Move reactionMove,
        BattleCombatant reactor,
        BattleCombatant target,
        BattleCombatant player,
        BattleCombatant enemy,
        BattleState state,
        int tick,
        List<CombatEvent> events
    ) {
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
        resolveMove(
            new FiringEntry(reactionSegment, reactor, target),
            player, enemy, state, tick, events, false);
    }

    private void resolveMove(
        FiringEntry       entry,
        BattleCombatant   player,
        BattleCombatant   enemy,
        BattleState       state,
        int               tick,
        List<CombatEvent> events,
        boolean           forceFullBlock
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
        events.addAll(abilityActivations.process(state, AbilityTrigger.move(
            AbilityTrigger.Type.MOVE_USED, attacker, defender, move, tick)));
        if (finishBattleIfNeeded(state, events, tick)) return;

        // --- Self-effects apply on unleash, for every move type (damaging,
        // defensive, and utility alike). A move that buffs its user when cast
        // (e.g. a CE strike that raises Power) fires the buff here, regardless
        // of whether the attack later hits, misses, or is blocked.
        applySelfEffects(state, attacker, defender, move, tick, events);
        if (finishBattleIfNeeded(state, events, tick)) return;

        // --- Defensive moves: apply buff or register full block ---
        if (move.isDefensive()) {
            resolveDefensiveMove(attacker, move, tick, events);
            return; // defensive moves don't attack
        }

        // --- Non-damaging utility moves ---
        if (move.getHitComponents().isEmpty()) {
            return;
        }

        MoveExecution execution = new MoveExecution(
            entry, forceFullBlock, tick, cursor.get().nextLaunchSequence++);
        scheduleComponents(execution);
        resolvePendingComponentsAtTick(state, player, enemy, tick, events);
    }

    private void scheduleComponents(MoveExecution execution) {
        ResolutionCursor c = cursor.get();
        List<HitComponent> components = execution.entry.segment.getMove().getHitComponents();
        for (int index = 0; index < components.size(); index++) {
            int impactTick = Math.addExact(
                execution.launchTick, components.get(index).getDelayTicks());
            c.pendingComponents.computeIfAbsent(impactTick, ignored -> new ArrayList<>())
                .add(new PendingComponent(execution, index));
            c.actionMaxTick = Math.max(c.actionMaxTick, impactTick);
            c.maxTick = Math.max(c.maxTick, impactTick);
        }
    }

    private void resolvePendingComponentsAtTick(
        BattleState state,
        BattleCombatant player,
        BattleCombatant enemy,
        int tick,
        List<CombatEvent> events
    ) {
        List<PendingComponent> pending = cursor.get().pendingComponents.remove(tick);
        if (pending == null) return;
        pending.sort(Comparator
            .comparingLong((PendingComponent value) -> value.execution.launchSequence)
            .thenComparingInt(PendingComponent::componentIndex));

        for (PendingComponent value : pending) {
            if (state.isBattleOver()) return;
            MoveExecution execution = value.execution;
            int componentIndex = value.componentIndex;
            HitComponent component = execution.entry.segment.getMove()
                .getHitComponents().get(componentIndex);
            if (component.requiresPreviousConnection()
                && (componentIndex == 0 || !execution.connected[componentIndex - 1])) {
                continue;
            }
            execution.connected[componentIndex] = resolveHitComponent(
                execution, component, componentIndex, state, tick, events);
            if (finishBattleIfNeeded(state, events, tick)) return;
            applyActiveStaggers(player, tick, events);
            applyActiveStaggers(enemy, tick, events);
            if (finishBattleIfNeeded(state, events, tick)) return;
        }
    }

    /** Resolve one impact. HIT and full BLOCK are the two connecting outcomes. */
    private boolean resolveHitComponent(
        MoveExecution execution,
        HitComponent component,
        int componentIndex,
        BattleState state,
        int tick,
        List<CombatEvent> events
    ) {
        Move move = execution.entry.segment.getMove();
        BattleCombatant attacker = execution.entry.attacker;
        BattleCombatant defender = execution.entry.defender;
        DamageCalculator.DamageResult result = DamageCalculator.resolve(
            attacker, defender, move, component, tick, rng,
            state.getRoundNumber(), execution.forceFullBlock,
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
                defenseMove == null ? List.of() : defenseMove.getOnDodgeEffects(),
                componentIndex, tick, events);
            // A dodge is NOT a miss: do not fire ATTACK_MISSED here. The dodge
            // outcome is already fully represented by the MOVE_DODGED event and
            // onDodgeEffects above. Firing ATTACK_MISSED would let abilities that
            // key off a miss (e.g. Fortune Reclaimed) trigger on an active dodge,
            // which they must not — only a natural miss (result.isMiss()) fires it.
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
                    .message(attacker.getCharacter().getName()
                             + " is staggered by the parry!")
                    .build());
                stunActiveSegments(attacker, tick, false);
            }
            applyDefenseEffects(state, defender, attacker, defenseMove,
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
                defenseMove == null ? List.of() : defenseMove.getOnBlockEffects(),
                componentIndex, tick, events);
        }

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
        events.addAll(abilityActivations.process(state, AbilityTrigger.move(
            AbilityTrigger.Type.ATTACK_HIT, attacker, defender, move, tick)));
        if (appliedDamage > 0) {
            events.addAll(abilityActivations.process(state, AbilityTrigger.amount(
                AbilityTrigger.Type.DAMAGE, attacker, defender, appliedDamage, tick)));
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

        if (finishBattleIfNeeded(state, events, tick)) return true;
        applyOnHitEffects(state, attacker, defender, move, component, componentIndex, tick, events);
        applyAbilityOnHitEffects(
            state, attacker, defender, move, componentIndex, tick, events);
        if (finishBattleIfNeeded(state, events, tick)) return true;
        if (move.isStun()) resolveStunTag(defender, move, componentIndex, tick, events);
        return true;
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
        BattleCombatant   defender,
        Move              move,
        int               componentIndex,
        int               tick,
        List<CombatEvent> events
    ) {
        if (stunActiveSegments(defender, tick, true)) {
            events.add(CombatEvent.of(CombatEvent.Type.MOVE_STUNNED)
                .target(defender)
                .move(move)
                .componentIndex(componentIndex)
                .tick(tick)
                .message(defender.getCharacter().getName()
                         + " was stunned and could not move.")
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
            // A coded on-hit row is dispatched to the matching compiled runtime
            // instead of being applied as a status — this is how a technique move's
            // hardcoded on-hit behaviour is stored on an editable effect row.
            if (effect.isCoded()) {
                events.addAll(attacker.getCodedAbilities().onEffectFired(
                    state, effect, attacker, defender, tick));
                continue;
            }
            int previousMaxHp = defender.getMaxHp();
            int previousMaxCe = defender.getMaxCursedEnergy();
            if (!defender.addStatusEffect(effect, state.getCurrentPhase())) continue;
            events.add(CombatEvent.of(CombatEvent.Type.STATUS_APPLIED)
                .source(attacker).target(defender).move(move)
                .componentIndex(componentIndex)
                .tick(tick)
                .message(defender.getCharacter().getName()
                         + " receives " + effect.getType().displayName() + "!")
                .build());
            appendResourceMaximumEvents(
                attacker, defender, previousMaxHp, previousMaxCe, tick, events);
            events.addAll(abilityActivations.process(state, AbilityTrigger.status(
                AbilityTrigger.Type.STATUS_APPLIED, defender, effect.getType(), tick)));
        }
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
                .message(combatant.getCharacter().getName()
                         + " gains " + effect.getType().displayName() + "!")
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
        List<StatusEffect> effects,
        int componentIndex,
        int tick,
        List<CombatEvent> events
    ) {
        if (effects == null || effects.isEmpty()) return;
        for (StatusEffect authored : effects) {
            StatusEffect effect = TechniqueMasteryResolver.resolve(
                authored, TechniqueMasteryResolver.masteryOf(defender));
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
                .message(defender.getCharacter().getName()
                         + " gains " + effect.getType().displayName() + "!")
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
                    .message(target.getCharacter().getName()
                        + " receives " + StatusEffectType.fromName(
                            effect.stringValue, effect.magnitude != null ? effect.magnitude : 0.0)
                            .displayName()
                        + " from an ability!")
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
        BattleCombatant[] combatants = {
            state.getPlayerCombatant(), state.getEnemyCombatant()
        };
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

    private void appendAutomaticStatusEvents(BattleState state, List<CombatEvent> events) {
        for (BattleState.AutomaticStatusApplication application
            : state.drainAutomaticStatusApplications()) {
            events.add(CombatEvent.of(CombatEvent.Type.STATUS_APPLIED)
                .source(application.source())
                .target(application.target())
                .message(application.target().getCharacter().getName()
                    + " receives " + application.status().displayName() + " from an ability!")
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
            if (state.checkAndResolveBattleOver()) break;
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
