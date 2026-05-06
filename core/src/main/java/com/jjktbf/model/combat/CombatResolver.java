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
 *      a. If a block's startTick is reached → drain CE (move "begins")
 *      b. If a block's fireTick is reached  → resolve the move
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
            combatant.getCharacter().getBaseStats().getCursedEnergyEfficiency()
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
            playerTimeline != null ? playerTimeline.getMaxApBar() : 0,
            enemyTimeline  != null ? enemyTimeline.getMaxApBar()  : 0
        );

        for (int tick = 1; tick <= maxTick; tick++) {
            state.advanceTick();

            // --- CE drain when a block starts ---
            drainCeForStartingBlocks(player, tick, events);
            drainCeForStartingBlocks(enemy,  tick, events);

            // --- Collect all moves firing this tick ---
            List<FiringEntry> firing = collectFiringMoves(player, enemy, tick);

            // --- Sort by priority ---
            sortFiringEntries(firing, player, enemy);

            // --- Resolve each firing move ---
            for (FiringEntry entry : firing) {
                if (entry.block.isKnockedOut()) continue;
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

    private void drainCeForStartingBlocks(BattleCombatant combatant, int tick, List<CombatEvent> events) {
        Timeline tl = combatant.getTimeline();
        if (tl == null) return;

        for (MoveBlock block : tl.getBlocks()) {
            if (block.isKnockedOut()) continue;
            if (block.getStartTick() == tick && block.getActualCeCost() > 0) {
                int drained = combatant.drainCe(block.getActualCeCost());
                events.add(CombatEvent.of(CombatEvent.Type.CE_DRAINED)
                    .source(combatant)
                    .move(block.getMove())
                    .intValue(drained)
                    .message(combatant.getCharacter().getName() + " uses " + drained
                             + " CE for " + block.getMove().getName())
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

    // -------------------------------------------------------------------------
    // Firing collection and sorting
    // -------------------------------------------------------------------------

    private record FiringEntry(MoveBlock block, BattleCombatant attacker, BattleCombatant defender) {}

    private List<FiringEntry> collectFiringMoves(BattleCombatant player, BattleCombatant enemy, int tick) {
        List<FiringEntry> firing = new ArrayList<>();

        if (player.getTimeline() != null) {
            for (MoveBlock b : player.getTimeline().firingAt(tick)) {
                firing.add(new FiringEntry(b, player, enemy));
            }
        }
        if (enemy.getTimeline() != null) {
            for (MoveBlock b : enemy.getTimeline().firingAt(tick)) {
                firing.add(new FiringEntry(b, enemy, player));
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
            int aInstant = a.block.isInstant() ? 1 : 0;
            int bInstant = b.block.isInstant() ? 1 : 0;
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
        MoveBlock       block    = entry.block;
        Move            move     = block.getMove();
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
                && defender.getTimeline().hasActiveBlockAt(tick);

            defender.applyDamage(result.getFinalDamage());

            if (wasBlocked) {
                events.add(CombatEvent.of(CombatEvent.Type.MOVE_PARTIAL_BLOCK)
                    .source(attacker).target(defender).move(move)
                    .message(defender.getCharacter().getName()
                             + " partially blocked " + move.getName() + "! (damage halved)")
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
                    attacker.getCharacter().getCombatStats().getMaxCursedEnergy()
                    * CombatStats.BF_CE_RESTORE_FRACTION
                );
                attacker.restoreCeFraction(CombatStats.BF_CE_RESTORE_FRACTION);
                attacker.enterBlackFlashState(state.getRoundNumber());
                attacker.recordBfsHit();

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
        switch (move.getDefenseType()) {
            case BLOCK -> {
                // Block is tracked via Timeline.hasActiveBlockAt()
                events.add(CombatEvent.of(CombatEvent.Type.STATUS_APPLIED)
                    .source(combatant).move(move)
                    .message(combatant.getCharacter().getName()
                             + " raises their block! (" + move.getBlockDamageReduction() + "% damage reduction)")
                    .build());
            }
            case FLAT_BLOCK -> {
                // Flat block is tracked via Timeline.hasActiveBlockAt() (same window logic)
                events.add(CombatEvent.of(CombatEvent.Type.STATUS_APPLIED)
                    .source(combatant).move(move)
                    .message(combatant.getCharacter().getName()
                             + " raises their block! (-" + move.getBlockFlatReduction() + " flat damage reduction)")
                    .build());
            }
            case NONE -> {}
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

        MoveBlock targetBlock = switch (move.getInterruptType()) {
            case KNOCK_CURRENT_BLOCK -> defenderTimeline.blockAt(tick);
            case KNOCK_NEXT_BLOCK    -> defenderTimeline.nextBlockAfter(tick);
            case NONE                -> null;
        };

        if (targetBlock != null && !targetBlock.isKnockedOut()) {
            targetBlock.knockOut();
            events.add(CombatEvent.of(CombatEvent.Type.MOVE_KNOCKED_OUT)
                .source(attacker).target(defender).move(targetBlock.getMove())
                .message(attacker.getCharacter().getName() + "'s " + move.getName()
                         + " knocks out " + defender.getCharacter().getName()
                         + "'s " + targetBlock.getMove().getName() + "!")
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
