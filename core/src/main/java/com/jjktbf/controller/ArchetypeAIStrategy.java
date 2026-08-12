package com.jjktbf.controller;

import com.jjktbf.model.character.Character;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.CharacterType;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattlePlan;
import com.jjktbf.model.combat.RandomSource;

import java.util.Set;

/**
 * Routes AI planning to a per-archetype strategy based on the combatant's
 * character definition.
 *
 * <p>AI type is a <b>code-only</b> mapping — it is never a data or editor field:
 * <ul>
 *   <li>Shikigami → {@link ShikigamiAIStrategy} (offense-first summon).</li>
 *   <li>Technique-less sorcerer → {@link AggressiveSorcererAIStrategy} or
 *       {@link PassiveSorcererAIStrategy}, decided by {@link #archetypeFor}.</li>
 *   <li>Everyone else (sorcerers with a cursed technique) →
 *       {@link GreedyAIStrategy} until dedicated technique archetypes exist.</li>
 * </ul>
 *
 * <p>Team-level planning and explicit-target assignment are inherited unchanged
 * from {@link AIStrategy#selectTeamPlan}, so dispatch happens automatically for
 * each AI-controlled combatant every round.
 *
 * <p><b>Adding an archetype.</b> Add a branch in {@link #selectPlan} (or replace
 * the branching with a registry keyed the same way) and implement
 * {@link AIStrategy} for the new archetype. No controller or data changes are
 * required.
 */
public class ArchetypeAIStrategy implements AIStrategy {

    private final GreedyAIStrategy sorcererStrategy = new GreedyAIStrategy();
    private final ShikigamiAIStrategy shikigamiStrategy = new ShikigamiAIStrategy();
    private final AggressiveSorcererAIStrategy aggressiveStrategy = new AggressiveSorcererAIStrategy();
    private final PassiveSorcererAIStrategy passiveStrategy = new PassiveSorcererAIStrategy();

    /**
     * Hardcoded archetype assignment for the final technique-less sorcerer
     * roster, keyed by character id (code-only, not authorable).
     */
    private static final Set<String> AGGRESSIVE_IDS = Set.of("000003", "000005"); // Yuji Itadori, Maki Zenin
    private static final Set<String> PASSIVE_IDS = Set.of("000002");              // Miwa Kasumi

    @Override
    public BattlePlan selectPlan(BattleCombatant ai, BattleCombatant opponent, RandomSource rng) {
        if (ai == null || ai.getCharacter() == null) {
            return sorcererStrategy.selectPlan(ai, opponent, rng);
        }
        Character character = ai.getCharacter();
        if (character.getType() == CharacterType.SHIKIGAMI) {
            return shikigamiStrategy.selectPlan(ai, opponent, rng);
        }
        if (character.getType() == CharacterType.SORCERER && !character.hasInnateTechnique()) {
            return archetypeFor(character).selectPlan(ai, opponent, rng);
        }
        return sorcererStrategy.selectPlan(ai, opponent, rng);
    }

    /**
     * Pick the technique-less sorcerer archetype: the hardcoded id map decides
     * for the known roster; any unmapped technique-less sorcerer falls back to a
     * stat-derived pick (offense-leaning → Aggressive, defense-leaning → Passive).
     */
    private AIStrategy archetypeFor(Character character) {
        String id = character.getId();
        if (AGGRESSIVE_IDS.contains(id)) return aggressiveStrategy;
        if (PASSIVE_IDS.contains(id)) return passiveStrategy;
        return statLeansAggressive(character) ? aggressiveStrategy : passiveStrategy;
    }

    /**
     * Offense-leaning stats (strength + combat ability + CE output) vs
     * defense-leaning stats (durability + vitality + jujutsu skill). Offense
     * wins ties.
     */
    private static boolean statLeansAggressive(Character character) {
        CharacterStats s = character.getBaseStats();
        int offense = s.getStrength() + s.getCombatAbility() + s.getCursedEnergyOutput();
        int defense = s.getDurability() + s.getVitality() + s.getJujutsuSkill();
        return offense >= defense;
    }
}
