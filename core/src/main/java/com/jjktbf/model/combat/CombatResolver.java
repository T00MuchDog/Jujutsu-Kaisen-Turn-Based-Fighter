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
     * Execute the full resolution phase for one round.
     *
     * @param state     the current battle state (Phase must be RESOLUTION)
     * @return          ordered list of all events that occurred this resolution
     */
    public List<CombatEvent> resolveRound(BattleState state) {
        List<CombatEvent> events = new ArrayList<>();

        BattleCombatant player = state.getPlayerCombatant();
        BattleCombatant enemy  = state.getEnemyCombatant();

        Timeline playerTimeline = player.getTimeline();
        Timeline enemyTimeline  = enemy.getTimeline();

        int maxTick = Math.max(
            playerTimeline != null ? playerTimeline.getGridLength() : 0,
            enemyTimeline  != null ? enemyTimeline.getGridLength()  : 0
        );

        drainSustainedCe(player, events);
        drainSustainedCe(enemy, events);

        for (int tick = 1; tick <= maxTick; tick++) {
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
                if (entry.segment.isKnockedOut()) continue;
                if (state.checkAndResolveBattleOver()) {
                    events.add(CombatEvent.of(CombatEvent.Type.BATTLE_OVER)
                        .message("Battle ended during resolution!").build());
                    return events;
                }
                resolveMove(entry, player, enemy, state, tick, events);
            }

            if (state.isBattleOver()) break;
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
            if (segment.isKnockedOut()) continue;
            if (segment.getStartTick() == tick && segment.getActualCeCost() > 0) {
                if (!combatant.hasCe(segment.getActualCeCost())) {
                    segment.knockOut();
                    events.add(CombatEvent.of(CombatEvent.Type.CE_DEPLETED)
                        .source(combatant)
                        .move(segment.getMove())
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
                    .message(combatant.getCharacter().getName() + " uses " + drained
                             + " CE for " + segment.getMove().getName())
                    .build());

                if (!combatant.hasAnyCe()) {
                    events.add(CombatEvent.of(CombatEvent.Type.CE_DEPLETED)
                        .source(combatant)
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
            .message(attacker.getCharacter().getName() + " unleashes " + move.getName() + "!")
            .build());

        // --- Defensive moves: apply buff or register full block ---
        if (move.isDefensive()) {
            resolveDefensiveMove(attacker, move, tick, events);
            return; // defensive moves don't attack
        }

        // --- Non-damaging utility moves ---
        if (move.getCategory() == MoveCategory.UTILITY) {
            applySelfEffects(attacker, move, events);
            return;
        }

        // --- Damaging moves ---
        DamageCalculator.DamageResult result = DamageCalculator.resolve(
            attacker, defender, move, tick, rng, state.getRoundNumber()
        );

        if (result.isMiss()) {
            events.add(CombatEvent.of(CombatEvent.Type.MOVE_MISSED)
                .source(attacker).target(defender).move(move)
                .message(move.getName() + " missed " + defender.getCharacter().getName() + "!")
                .build());

        } else if (result.isBlocked()) {
            events.add(CombatEvent.of(CombatEvent.Type.MOVE_BLOCKED)
                .source(attacker).target(defender).move(move)
                .message(defender.getCharacter().getName() + " blocked " + move.getName() + "!")
                .build());

        } else {
            // Hit — check whether a block softened it
            boolean wasBlocked = defender.getTimeline() != null
                && defender.getTimeline().activeBlockAt(tick, move) != null;

            defender.applyDamage(result.getFinalDamage());

            if (wasBlocked) {
                events.add(CombatEvent.of(CombatEvent.Type.MOVE_BLOCK_REDUCED)
                    .source(attacker).target(defender).move(move)
                    .message(defender.getCharacter().getName()
                             + " blocked " + move.getName() + "! (damage reduced)")
                    .build());
            }

            events.add(CombatEvent.of(CombatEvent.Type.DAMAGE_DEALT)
                .source(attacker).target(defender).move(move)
                .intValue(result.getFinalDamage())
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
                    .message("*** BLACK FLASH! *** " + attacker.getCharacter().getName()
                             + " lands a Black Flash! +" + ceRestored + " CE restored!")
                    .build());
                events.add(CombatEvent.of(CombatEvent.Type.CE_RESTORED)
                    .source(attacker).intValue(ceRestored)
                    .message(attacker.getCharacter().getName() + " recovered " + ceRestored + " CE!")
                    .build());
            }

            // On-hit status effects
            applyOnHitEffects(attacker, defender, move, events);

            // Interrupt resolution
            if (move.hasInterrupt()) {
                resolveInterrupt(attacker, defender, move, tick, events);
            }
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
                .message(msg)
                .build());
        }
        applySelfEffects(combatant, move, events);
    }

    // -------------------------------------------------------------------------
    // Status effect application
    // -------------------------------------------------------------------------

    private void applyOnHitEffects(
        BattleCombatant attacker,
        BattleCombatant defender,
        Move            move,
        List<CombatEvent> events
    ) {
        for (StatusEffect effect : move.getOnHitEffects()) {
            defender.addStatusEffect(effect);
            events.add(CombatEvent.of(CombatEvent.Type.STATUS_APPLIED)
                .source(attacker).target(defender).move(move)
                .message(defender.getCharacter().getName()
                         + " is afflicted with " + effect.getType() + "!")
                .build());
        }
    }

    private void applySelfEffects(BattleCombatant combatant, Move move, List<CombatEvent> events) {
        for (StatusEffect effect : move.getSelfEffects()) {
            combatant.addStatusEffect(effect);
            events.add(CombatEvent.of(CombatEvent.Type.STATUS_APPLIED)
                .source(combatant).move(move)
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
            events.add(CombatEvent.of(CombatEvent.Type.MOVE_KNOCKED_OUT)
                .source(attacker).target(defender).move(targetSegment.getMove())
                .message(attacker.getCharacter().getName() + "'s " + move.getName()
                          + " knocks out " + defender.getCharacter().getName()
                         + "'s " + targetSegment.getMove().getName() + "!")
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
