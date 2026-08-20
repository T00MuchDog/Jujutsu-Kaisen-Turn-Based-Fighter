package com.jjktbf.model.move;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.jjktbf.model.character.AbilityConditionData;
import com.jjktbf.model.character.AbilityConditionType;
import com.jjktbf.model.character.AbilityEffectData;
import com.jjktbf.model.character.AbilityEffectType;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.coded.CodedAbilityRegistry;
import com.jjktbf.model.progression.TechniqueMasteryProgressionData;
import com.jjktbf.model.progression.TechniqueMasteryProgressions;
import com.jjktbf.model.progression.TechniqueMasteryResolver;

/**
 * One effect primitive attached to a move trigger.
 *
 * <p>The effect fields are inherited from {@link AbilityEffectData}, so moves and
 * abilities use the same hardcoded primitive definitions. The move contributes
 * only activation context: when the row fires, which hit it belongs to, optional
 * extra conditions, and an optional chance roll.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MoveEffectData extends AbilityEffectData {

    /** {@link MoveEffectTrigger} name. */
    public String trigger;

    /** Zero-based component index for ON_HIT; null means every hit component. */
    public Integer hitComponentIndex;

    /** Optional predicate evaluated after this row's move trigger occurs. */
    public AbilityConditionData condition;

    /** Whether to roll {@link #activationChance} after trigger and condition match. */
    public Boolean activationChanceEnabled;

    /** Activation probability as a fraction in [0, 1]. */
    public Double activationChance;

    /** Optional CTM progression for the move-effect activation chance. */
    public java.util.Map<String, TechniqueMasteryProgressionData> activationMasteryProgression;

    @Override
    public MoveEffectData copy() {
        MoveEffectData copy = new MoveEffectData();
        copy.copyFrom(this);
        return copy;
    }

    @Override
    public void copyFrom(AbilityEffectData source) {
        super.copyFrom(source);
        if (source instanceof MoveEffectData moveEffect) {
            trigger = moveEffect.trigger;
            hitComponentIndex = moveEffect.hitComponentIndex;
            condition = moveEffect.condition == null ? null : moveEffect.condition.copy();
            activationChanceEnabled = moveEffect.activationChanceEnabled;
            activationChance = moveEffect.activationChance;
            activationMasteryProgression = TechniqueMasteryProgressions.copy(
                moveEffect.activationMasteryProgression);
        }
    }

    @JsonIgnore
    public MoveEffectTrigger resolvedTrigger() {
        return MoveEffectTrigger.fromName(trigger);
    }

    @JsonIgnore
    public AbilityConditionData resolvedCondition() {
        return condition == null ? AbilityConditionData.always() : condition.copy();
    }

    @JsonIgnore
    public double effectiveActivationChance() {
        if (!Boolean.TRUE.equals(activationChanceEnabled)) return 1.0;
        return activationChance == null
            ? 1.0 : Math.max(0.0, Math.min(1.0, activationChance));
    }

    public double resolvedActivationChance(int mastery) {
        return TechniqueMasteryResolver.resolvePercent(
            activationMasteryProgression,
            TechniqueMasteryProgressions.ACTIVATION_CHANCE,
            effectiveActivationChance(),
            mastery);
    }

    public boolean matches(MoveEffectTrigger expected, int componentIndex) {
        if (expected == null || resolvedTrigger() != expected) return false;
        return expected != MoveEffectTrigger.ON_HIT
            || hitComponentIndex == null
            || hitComponentIndex == componentIndex;
    }

    /** Convert a shared coded primitive to the legacy runtime value object. */
    @JsonIgnore
    public StatusEffect toCodedStatusEffect() {
        if (!AbilityEffectType.CODED_MOVE_ACTION.name().equalsIgnoreCase(type)) {
            throw new IllegalStateException("Move effect is not a coded action.");
        }
        return StatusEffect.coded(
            codedAbilityKey, codedAction, codedTarget, codedStackCount,
            codedParameters, masteryProgression);
    }

    /** Return a user-facing validation error, or null when this row is valid. */
    public String validationError(int hitComponentCount, boolean masteryEligible) {
        AbilityEffectType effectType;
        try {
            effectType = AbilityEffectType.fromName(type);
        } catch (Exception exception) {
            return "Choose a valid effect type.";
        }
        if (!effectType.isMoveEffect()) {
            return effectType.displayName() + " cannot be activated by a move.";
        }
        String effectError = effectType.validationError(this);
        if (effectError != null) return effectError;

        MoveEffectTrigger moveTrigger;
        try {
            moveTrigger = resolvedTrigger();
        } catch (Exception exception) {
            return "Choose when the move effect fires.";
        }
        if (effectType.isMoveAvailabilityConstraint()
            && moveTrigger != MoveEffectTrigger.AVAILABILITY) {
            return effectType.displayName() + " must use Move availability.";
        }
        if (!effectType.isMoveAvailabilityConstraint()
            && moveTrigger == MoveEffectTrigger.AVAILABILITY) {
            return "Only move availability constraints may use Move availability.";
        }
        if (effectType.isMoveAvailabilityConstraint()
            && !AbilityConditionType.ALWAYS.name().equalsIgnoreCase(resolvedCondition().type)) {
            return "Move availability constraints must always apply.";
        }
        if (effectType.isMoveAvailabilityConstraint()
            && (Boolean.TRUE.equals(activationChanceEnabled)
                || activationMasteryProgression != null
                || masteryProgression != null)) {
            return "Move availability constraints cannot use chance or mastery progression.";
        }
        if (effectType.isAccuracyPriority()
            && moveTrigger != MoveEffectTrigger.ACCURACY_CHECK) {
            return effectType.displayName() + " must use Accuracy check.";
        }
        if (!effectType.isAccuracyPriority()
            && moveTrigger == MoveEffectTrigger.ACCURACY_CHECK) {
            return "Only Never Miss and Never Hit may use Accuracy check.";
        }
        if (effectType.isAccuracyPriority()
            && !AbilityConditionType.ALWAYS.name().equalsIgnoreCase(resolvedCondition().type)) {
            return "Accuracy priority must always apply while its move is active.";
        }
        if (effectType.isAccuracyPriority() && moveTag != null && !moveTag.isBlank()) {
            return "Move accuracy priority cannot use an affected-moves filter.";
        }
        if (effectType.isAccuracyPriority()
            && (Boolean.TRUE.equals(activationChanceEnabled)
                || activationMasteryProgression != null)) {
            return "Accuracy priority cannot roll an activation chance.";
        }
        if (moveTrigger == MoveEffectTrigger.ON_HIT
            && hitComponentIndex != null
            && (hitComponentIndex < 0 || hitComponentIndex >= hitComponentCount)) {
            return "The selected hit component no longer exists.";
        }
        if (moveTrigger != MoveEffectTrigger.ON_HIT && hitComponentIndex != null) {
            return "Only on-hit effects may select a hit component.";
        }
        if (CodedAbilityRegistry.executesBeforeHit(this)
            && moveTrigger != MoveEffectTrigger.ON_HIT) {
            return "This pre-hit coded effect must use On hit.";
        }
        if (CodedAbilityRegistry.executesBeforeHit(this)
            && !com.jjktbf.model.character.AbilityEffectTarget.ENEMY.name()
                .equalsIgnoreCase(target)) {
            return "This pre-hit coded effect must target the move target.";
        }

        String conditionError = AbilityConditionType.validationError(resolvedCondition());
        if (conditionError != null) return conditionError;
        if (Boolean.TRUE.equals(activationChanceEnabled)
            && (activationChance == null || !Double.isFinite(activationChance)
                || activationChance < 0.0 || activationChance > 1.0)) {
            return "Activation chance must be between 0% and 100%.";
        }
        if (!masteryEligible && hasMasteryProgression()) {
            return "Only INNATE_TECHNIQUE moves may use mastery progression.";
        }
        if (activationMasteryProgression != null) {
            java.util.Set<String> allowed = Boolean.TRUE.equals(activationChanceEnabled)
                ? java.util.Set.of(TechniqueMasteryProgressions.ACTIVATION_CHANCE)
                : java.util.Set.of();
            String progressionError = TechniqueMasteryProgressions.validationError(
                activationMasteryProgression, allowed);
            if (progressionError != null) return progressionError;
            for (int mastery = 0; mastery <= CharacterStats.MAX_STAT; mastery++) {
                double chance;
                try {
                    chance = resolvedActivationChance(mastery);
                } catch (RuntimeException exception) {
                    return "Invalid activation chance progression at CTM " + mastery + ".";
                }
                if (chance < 0.0 || chance > 1.0) {
                    return "Activation chance is outside 0%-100% at CTM " + mastery + ".";
                }
            }
        }
        return null;
    }

    private boolean hasMasteryProgression() {
        if (masteryProgression != null && !masteryProgression.isEmpty()) return true;
        if (activationMasteryProgression != null
            && !activationMasteryProgression.isEmpty()) return true;
        return hasMasteryProgression(condition) || hasMasteryProgression(returnCondition);
    }

    private static boolean hasMasteryProgression(AbilityConditionData value) {
        if (value == null) return false;
        if (value.masteryProgression != null && !value.masteryProgression.isEmpty()) return true;
        return value.children != null
            && value.children.stream().anyMatch(MoveEffectData::hasMasteryProgression);
    }
}
