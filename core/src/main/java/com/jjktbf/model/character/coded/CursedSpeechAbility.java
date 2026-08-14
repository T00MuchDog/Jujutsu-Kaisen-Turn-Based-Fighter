package com.jjktbf.model.character.coded;

import com.jjktbf.model.combat.AbilityTrigger;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.CombatEvent;
import com.jjktbf.model.combat.RandomSource;
import com.jjktbf.model.move.HitComponent;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.StatusEffect;
import com.jjktbf.model.move.MoveEffectData;
import com.jjktbf.model.move.MoveEffectTrigger;
import com.jjktbf.model.character.AbilityEffectType;
import com.jjktbf.model.character.CombatStats;
import com.jjktbf.model.progression.TechniqueMasteryResolver;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Runtime implementation for CE resistance and recoil from Cursed Speech. */
public final class CursedSpeechAbility implements CodedAbilityRuntime {

    public static final String KEY = "CURSED_SPEECH";
    public static final String TECHNIQUE = "TECHNIQUE";
    public static final String REFINED_COMMANDS = "REFINED_COMMANDS";
    public static final String COMMAND = "COMMAND";

    public static final String BASE_CHANCE_PERCENT = "baseChancePercent";
    public static final String BASE_RECOIL = "baseRecoil";
    public static final String SUCCESS_BONUS_PERCENT = "successBonusPercent";

    public static final String DONT_MOVE = "DONT_MOVE";
    public static final String BLAST_AWAY = "BLAST_AWAY";
    public static final String SLEEP = "SLEEP";
    public static final String PLUMMET = "PLUMMET";
    public static final String GET_TWISTED = "GET_TWISTED";
    public static final String RETURN = "RETURN";
    public static final String EXPLODE = "EXPLODE";
    public static final String DIE = "DIE";

    private static final Set<String> COMMAND_MODES = Set.of(
        DONT_MOVE, BLAST_AWAY, SLEEP, PLUMMET, GET_TWISTED, RETURN, EXPLODE, DIE);

    private final BattleCombatant owner;
    private final Map<String, List<CodedAbilityBinding>> bindingsByFeature;

    CursedSpeechAbility(
        BattleCombatant owner,
        Set<String> features,
        Map<String, List<CodedAbilityBinding>> bindingsByFeature
    ) {
        this.owner = owner;
        this.bindingsByFeature = bindingsByFeature == null ? Map.of() : bindingsByFeature;
    }

    @Override
    public List<CombatEvent> onTrigger(
        BattleState state,
        AbilityTrigger trigger,
        Predicate<String> featureActive
    ) {
        return List.of();
    }

    @Override
    public CodedHitModifiers onAttackConnected(
        BattleCombatant attacker,
        BattleCombatant defender,
        Move move,
        HitComponent component,
        int tick,
        RandomSource rng,
        Predicate<String> featureActive,
        Predicate<MoveEffectData> moveEffectActive
    ) {
        if (attacker != owner || defender == null || component == null) {
            return CodedHitModifiers.none();
        }
        StatusEffect authored;
        if (move.usesUnifiedEffects()) {
            MoveEffectData commandRow = commandEffectData(move, component);
            if (commandRow == null) return CodedHitModifiers.none();
            if (!moveEffectActive.test(commandRow)) {
                return new CodedHitModifiers(true, true, 1.0, true, 0, List.of());
            }
            authored = commandRow.toCodedStatusEffect();
        } else {
            authored = commandEffect(move, component);
        }
        if (authored == null) return CodedHitModifiers.none();

        StatusEffect command = TechniqueMasteryResolver.resolve(
            authored, TechniqueMasteryResolver.masteryOf(owner));
        int baseChance = TechniqueMasteryResolver.codedParameter(
            command.getCodedParameters(), BASE_CHANCE_PERCENT, 0);
        int baseRecoil = TechniqueMasteryResolver.codedParameter(
            command.getCodedParameters(), BASE_RECOIL, 0);
        int bonus = featureActive.test(REFINED_COMMANDS)
            ? featureParameter(REFINED_COMMANDS, SUCCESS_BONUS_PERCENT, 0) : 0;

        double userReinforcedCe = reinforcedCe(owner);
        double targetReinforcedCe = reinforcedCe(defender);
        double ceAdjustment = userReinforcedCe / Math.max(1.0, targetReinforcedCe);
        int chance = clamp(1, 99,
            (int) Math.round((baseChance + bonus) * ceAdjustment));
        double recoilMultiplier = targetReinforcedCe / Math.max(1.0, userReinforcedCe);
        int recoil = (int) Math.round(baseRecoil * recoilMultiplier);
        boolean eligible = !RETURN.equalsIgnoreCase(command.getCodedTarget())
            || defender.isSummon();
        boolean succeeds = eligible && rng.nextDouble() < chance / 100.0;

        String message = succeeds
            ? owner.getCharacter().getName() + "'s " + move.getName() + " takes hold on "
                + defender.getCharacter().getName() + "!"
            : defender.getCharacter().getName() + " resists " + move.getName() + "!";
        CombatEvent event = CombatEvent.of(CombatEvent.Type.ABILITY_ACTIVATED)
            .source(owner).target(defender).move(move).tick(tick)
            .intValue(chance).message(message).build();

        return new CodedHitModifiers(
            true,
            true,
            1.0,
            !succeeds,
            recoil,
            List.of(event));
    }

    @Override
    public List<CombatEvent> drainPendingEvents(int tick) {
        return List.of();
    }

    @Override
    public CodedAbilityState state() {
        return new CodedAbilityState(KEY, "Cursed Speech", 0, 0);
    }

    public static boolean supportsFeature(String feature) {
        return TECHNIQUE.equals(feature) || REFINED_COMMANDS.equals(feature);
    }

    public static boolean supportsTarget(String target, Integer stackCount) {
        return target != null && COMMAND_MODES.contains(target.toUpperCase(Locale.ROOT))
            && stackCount == null;
    }

    public static List<String> commandModes() {
        return List.of(
            DONT_MOVE, BLAST_AWAY, SLEEP, PLUMMET,
            GET_TWISTED, RETURN, EXPLODE, DIE);
    }

    public static boolean isCommand(StatusEffect effect) {
        return effect != null && effect.isCoded()
            && KEY.equalsIgnoreCase(effect.getCodedAbilityKey())
            && COMMAND.equalsIgnoreCase(effect.getCodedAction());
    }

    public static String commandMode(Move move) {
        if (move == null) return null;
        for (HitComponent component : move.getHitComponents()) {
            StatusEffect effect = move.usesUnifiedEffects()
                ? java.util.Optional.ofNullable(commandEffectData(move, component))
                    .map(MoveEffectData::toCodedStatusEffect).orElse(null)
                : commandEffect(move, component);
            if (effect != null) return effect.getCodedTarget();
        }
        return null;
    }

    /** Return can only address a manifested summon; other commands target any enemy. */
    public static boolean canTarget(Move move, BattleCombatant target) {
        return target != null && (!RETURN.equalsIgnoreCase(commandMode(move)) || target.isSummon());
    }

    private static StatusEffect commandEffect(Move move, HitComponent component) {
        if (move == null || move.usesUnifiedEffects()) return null;
        return component.getOnHitEffects().stream()
            .filter(CursedSpeechAbility::isCommand)
            .findFirst().orElse(null);
    }

    private static MoveEffectData commandEffectData(Move move, HitComponent component) {
        if (move == null || !move.usesUnifiedEffects()) return null;
        int index = move.getHitComponents().indexOf(component);
        return move.effectsFor(MoveEffectTrigger.ON_HIT, index).stream()
            .filter(effect -> AbilityEffectType.CODED_MOVE_ACTION.name()
                .equalsIgnoreCase(effect.type))
            .filter(CursedSpeechAbility::isCommand)
            .findFirst().orElse(null);
    }

    private static boolean isCommand(MoveEffectData effect) {
        return effect != null
            && KEY.equalsIgnoreCase(effect.codedAbilityKey)
            && COMMAND.equalsIgnoreCase(effect.codedAction);
    }

    private int featureParameter(String feature, String parameter, int fallback) {
        List<CodedAbilityBinding> bindings = bindingsByFeature.getOrDefault(feature, List.of());
        if (bindings.isEmpty() || bindings.get(0).effect() == null) return fallback;
        var resolved = TechniqueMasteryResolver.resolve(
            bindings.get(0).effect(), TechniqueMasteryResolver.masteryOf(owner));
        return TechniqueMasteryResolver.codedParameter(
            resolved.codedParameters, parameter, fallback);
    }

    private static double reinforcedCe(BattleCombatant combatant) {
        double reinforcementCap = CombatStats.computeCeReinforcementCap(
            combatant.getEffectiveStats(), combatant.getStatMode());
        return Math.min(combatant.getCurrentCe(), reinforcementCap);
    }

    private static int clamp(int minimum, int maximum, int value) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
