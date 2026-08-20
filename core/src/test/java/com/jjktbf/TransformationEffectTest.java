package com.jjktbf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjktbf.model.character.Ability;
import com.jjktbf.model.character.AbilityConditionData;
import com.jjktbf.model.character.AbilityConditionType;
import com.jjktbf.model.character.AbilityData;
import com.jjktbf.model.character.AbilityEffectData;
import com.jjktbf.model.character.AbilityEffectType;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.SorcererCharacter;
import com.jjktbf.model.character.TransformationHpMode;
import com.jjktbf.model.combat.AbilityActivationEngine;
import com.jjktbf.model.combat.AbilityTrigger;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.CombatEvent;
import com.jjktbf.model.combat.SeededRandomSource;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;
import com.jjktbf.model.move.MoveEffectData;
import com.jjktbf.model.move.MoveEffectTrigger;
import com.jjktbf.model.move.StatusEffect;
import com.jjktbf.model.move.StatusEffectType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransformationEffectTest {

    @Test
    void transformationEffectMetadataAndConditionRoundTrip() throws Exception {
        AbilityEffectData effect = transformationEffect(
            TransformationHpMode.CURRENT_PERCENTAGE,
            AbilityConditionType.ROUND_REACHED.createDefault());

        assertTrue(AbilityEffectType.TRANSFORM_CHARACTER.requiresActivation());
        assertNull(AbilityEffectType.TRANSFORM_CHARACTER.validationError(effect));

        String json = new ObjectMapper().writeValueAsString(effect);
        AbilityEffectData restored = new ObjectMapper().readValue(json, AbilityEffectData.class);
        assertEquals("FORM", restored.characterId);
        assertEquals(TransformationHpMode.CURRENT_PERCENTAGE.name(),
            restored.transformationHpMode);
        assertNotNull(restored.returnCondition);
        assertEquals(AbilityConditionType.ROUND_REACHED.name(), restored.returnCondition.type);

        AbilityEffectData copy = restored.copy();
        copy.returnCondition.round = 9;
        assertEquals(1, restored.returnCondition.round,
            "effect copies must deep-copy the return condition tree");
    }

    @Test
    void hpModesMapTheNewFormsStartingHp() {
        for (TransformationHpMode mode : TransformationHpMode.values()) {
            SorcererCharacter form = character("FORM", "Form", 300, List.of(), List.of());
            BattleCombatant owner = combatantWithTransformation(mode, null);
            BattleState state = new BattleState(owner, combatant("ENEMY", "Enemy", 100));
            int oldMax = owner.getMaxHp();
            owner.receiveDamage((int) Math.round(oldMax * 0.6));
            int oldHp = owner.getCurrentHp();

            AbilityActivationEngine engine = new AbilityActivationEngine(
                new SeededRandomSource(1L), id -> Optional.of(form));
            engine.process(state, AbilityTrigger.manual(owner, "TRANSFORM", 0));

            int expected = switch (mode) {
                case FULL -> owner.getMaxHp();
                case CURRENT_VALUE -> Math.min(oldHp, owner.getMaxHp());
                case CURRENT_PERCENTAGE -> (int) Math.round(
                    owner.getMaxHp() * ((double) oldHp / oldMax));
            };
            assertEquals(expected, owner.getCurrentHp(), mode.name());
            assertEquals("FORM", owner.getCharacter().getId());
        }
    }

    @Test
    void transformationSwapsDefinitionProfileWithoutReplacingBattleIdentity() {
        Move originalMove = move("ORIGINAL_MOVE");
        Move formMove = move("FORM_MOVE");
        Ability originalPassive = passiveStrength("ORIGINAL_PASSIVE", 10);
        Ability formPassive = passiveStrength("FORM_PASSIVE", 70);
        Ability transform = transformationAbility(
            TransformationHpMode.CURRENT_VALUE, null);
        SorcererCharacter original = character(
            "ORIGINAL", "Original", 100,
            List.of(originalMove), List.of(originalPassive, transform));
        SorcererCharacter form = character(
            "FORM", "Form", 300, List.of(formMove), List.of(formPassive));
        BattleCombatant owner = new BattleCombatant(original);
        BattleState state = new BattleState(owner, combatant("ENEMY", "Enemy", 100));
        var instanceId = owner.getInstanceId();
        var teamId = owner.getTeamId();
        owner.addStatusEffect(new StatusEffect(
            StatusEffectType.SPEED_INCREASE, 2, 10.0));
        int hp = owner.getCurrentHp();

        AbilityActivationEngine engine = new AbilityActivationEngine(
            new SeededRandomSource(1L), id -> Optional.of(form));
        List<CombatEvent> events = engine.process(
            state, AbilityTrigger.manual(owner, "TRANSFORM", 0));

        assertSame(owner, state.combatant(instanceId));
        assertEquals(instanceId, owner.getInstanceId());
        assertEquals(teamId, owner.getTeamId());
        assertEquals("ORIGINAL", owner.getOriginCharacter().getId());
        assertEquals("FORM", owner.getCharacter().getId());
        assertEquals(List.of(formMove), owner.getCharacter().getKnownMoves());
        assertEquals(150, owner.getEffectiveStats().getStrength());
        assertEquals(hp, owner.getCurrentHp());
        assertTrue(owner.hasEffect(StatusEffectType.SPEED_INCREASE));
        assertTrue(events.stream().anyMatch(event ->
            event.getType() == CombatEvent.Type.CHARACTER_TRANSFORMED
                && "FORM".equals(event.getCharacterId())));
    }

    @Test
    void returnConditionRestoresTheParkedOriginalProfile() {
        AbilityConditionData returnCondition =
            AbilityConditionType.HP_PERCENT_AT_OR_BELOW.createDefault();
        returnCondition.percentage = 0.5;
        Move originalMove = move("ORIGINAL_MOVE");
        Move formMove = move("FORM_MOVE");
        Ability originalPassive = passiveStrength("ORIGINAL_PASSIVE", 10);
        Ability transform = transformationAbility(
            TransformationHpMode.CURRENT_PERCENTAGE, returnCondition);
        SorcererCharacter original = character(
            "ORIGINAL", "Original", 100,
            List.of(originalMove), List.of(originalPassive, transform));
        SorcererCharacter form = character(
            "FORM", "Form", 300,
            List.of(formMove), List.of(passiveStrength("FORM_PASSIVE", 70)));
        BattleCombatant owner = new BattleCombatant(original);
        BattleCombatant enemy = combatant("ENEMY", "Enemy", 100);
        BattleState state = new BattleState(owner, enemy);
        var identity = owner.getInstanceId();
        AbilityActivationEngine engine = new AbilityActivationEngine(
            new SeededRandomSource(1L), id -> Optional.of(form));
        engine.process(state, AbilityTrigger.manual(owner, "TRANSFORM", 0));
        int damage = owner.getCurrentHp() - Math.max(1, owner.getMaxHp() / 3);
        owner.receiveDamage(damage);
        int formHp = owner.getCurrentHp();

        List<CombatEvent> events = engine.process(state, AbilityTrigger.amount(
            AbilityTrigger.Type.DAMAGE, enemy, owner, damage, 1));

        assertFalse(owner.isTransformed());
        assertEquals("ORIGINAL", owner.getCharacter().getId());
        assertEquals(List.of(originalMove), owner.getCharacter().getKnownMoves());
        assertEquals(90, owner.getEffectiveStats().getStrength());
        assertEquals(Math.min(formHp, owner.getMaxHp()), owner.getCurrentHp());
        assertSame(owner, state.combatant(identity));
        assertTrue(events.stream().anyMatch(event ->
            event.getType() == CombatEvent.Type.CHARACTER_REVERTED
                && "ORIGINAL".equals(event.getCharacterId())));
    }

    @Test
    void moveEffectCanTransformItsConfiguredTarget() {
        SorcererCharacter form = character("FORM", "Form", 300, List.of(), List.of());
        BattleCombatant owner = combatant("OWNER", "Owner", 100);
        BattleCombatant enemy = combatant("ENEMY", "Enemy", 100);
        BattleState state = new BattleState(owner, enemy);
        MoveEffectData effect = AbilityEffectType.TRANSFORM_CHARACTER.createDefaultMoveEffect();
        effect.characterId = "FORM";
        effect.target = "ENEMY";
        effect.transformationHpMode = TransformationHpMode.CURRENT_VALUE.name();
        effect.trigger = MoveEffectTrigger.ON_FIRE.name();
        Move move = new Move.Builder("TRANSFORM_MOVE")
            .name("Transform").category(MoveCategory.UTILITY)
            .apCost(1).unleashPoint(1).effects(List.of(effect)).build();
        AbilityActivationEngine engine = new AbilityActivationEngine(
            new SeededRandomSource(1L), id -> Optional.of(form));

        engine.processMoveEffects(
            state, owner, enemy, move, MoveEffectTrigger.ON_FIRE, -1, 1);

        assertEquals("OWNER", owner.getCharacter().getId());
        assertEquals("FORM", enemy.getCharacter().getId());
    }

    @Test
    void unknownFormReferenceFailsClosed() {
        BattleCombatant owner = combatantWithTransformation(
            TransformationHpMode.FULL, null);
        BattleState state = new BattleState(owner, combatant("ENEMY", "Enemy", 100));
        AbilityActivationEngine engine = new AbilityActivationEngine(
            new SeededRandomSource(1L), id -> Optional.empty());

        List<CombatEvent> events = engine.process(
            state, AbilityTrigger.manual(owner, "TRANSFORM", 0));

        assertEquals("ORIGINAL", owner.getCharacter().getId());
        assertFalse(owner.isTransformed());
        assertTrue(events.stream().noneMatch(event ->
            event.getType() == CombatEvent.Type.CHARACTER_TRANSFORMED));
    }

    private static BattleCombatant combatantWithTransformation(
        TransformationHpMode mode,
        AbilityConditionData returnCondition
    ) {
        Ability transform = transformationAbility(mode, returnCondition);
        SorcererCharacter original = character(
            "ORIGINAL", "Original", 100, List.of(), List.of(transform));
        return new BattleCombatant(original);
    }

    private static Ability transformationAbility(
        TransformationHpMode mode,
        AbilityConditionData returnCondition
    ) {
        AbilityData data = new AbilityData();
        data.id = "TRANSFORM";
        data.name = "Transform";
        data.category = "ACTIVE";
        data.sourceType = "CHARACTER";
        data.activationCondition = AbilityConditionData.manualActivation();
        data.effects = List.of(transformationEffect(mode, returnCondition));
        return new Ability(data);
    }

    private static AbilityEffectData transformationEffect(
        TransformationHpMode mode,
        AbilityConditionData returnCondition
    ) {
        AbilityEffectData effect = AbilityEffectType.TRANSFORM_CHARACTER.createDefault();
        effect.characterId = "FORM";
        effect.transformationHpMode = mode.name();
        effect.returnCondition = returnCondition;
        return effect;
    }

    private static Ability passiveStrength(String id, int amount) {
        AbilityData data = new AbilityData();
        data.id = id;
        data.name = id;
        data.category = "PASSIVE";
        data.sourceType = "CHARACTER";
        AbilityEffectData effect = AbilityEffectType.STAT_ADD.createDefault();
        effect.stat = "strength";
        effect.intValue = amount;
        data.effects = List.of(effect);
        return new Ability(data);
    }

    private static Move move(String id) {
        return new Move.Builder(id).name(id).category(MoveCategory.UTILITY)
            .apCost(1).unleashPoint(1).build();
    }

    private static BattleCombatant combatant(String id, String name, int vitality) {
        return new BattleCombatant(character(id, name, vitality, List.of(), List.of()));
    }

    private static SorcererCharacter character(
        String id,
        String name,
        int vitality,
        List<Move> moves,
        List<Ability> abilities
    ) {
        CharacterStats stats = new CharacterStats.Builder()
            .strength(80).vitality(vitality).speed(100).build();
        return new SorcererCharacter(
            id, name, stats, null, moves, abilities, false);
    }
}
