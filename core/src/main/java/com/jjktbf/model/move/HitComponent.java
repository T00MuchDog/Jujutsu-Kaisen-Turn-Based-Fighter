package com.jjktbf.model.move;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** One immutable damage instance emitted by an attacking move. */
public final class HitComponent {

    /**
     * Per-component accuracy sentinel: a value greater than 1.0 means
     * "inherit the parent move's baseAccuracy", preserving the legacy behaviour
     * for components authored before per-hit accuracy existed. Real values are
     * clamped to [0.0, 1.0].
     */
    public static final double INHERIT_MOVE_ACCURACY = Double.MAX_VALUE;

    private final int basePower;
    private final Set<MoveTag> tags;
    private final MoveCategory category;
    private final int delayTicks;
    private final boolean requiresPreviousConnection;
    private final boolean avoidable;
    private final double baseAccuracy;
    private final List<StatusEffect> onHitEffects;

    public HitComponent(
        int basePower,
        Set<MoveTag> tags,
        int delayTicks,
        boolean requiresPreviousConnection,
        boolean avoidable
    ) {
        this(basePower, tags, delayTicks, requiresPreviousConnection, avoidable,
            INHERIT_MOVE_ACCURACY, null);
    }

    public HitComponent(
        int basePower,
        Set<MoveTag> tags,
        int delayTicks,
        boolean requiresPreviousConnection,
        boolean avoidable,
        double baseAccuracy,
        List<StatusEffect> onHitEffects
    ) {
        if (basePower < 0) throw new IllegalArgumentException("component basePower must be nonnegative");
        if (delayTicks < 0) throw new IllegalArgumentException("component delayTicks must be nonnegative");
        if (tags == null || tags.isEmpty()) {
            throw new IllegalArgumentException("component damage tags are required");
        }

        EnumSet<MoveTag> copy = EnumSet.noneOf(MoveTag.class);
        for (MoveTag tag : tags) {
            if (tag == null || !MoveTag.TYPE_TAGS.contains(tag)) {
                throw new IllegalArgumentException(
                    "component tags may contain only damage-type tags: " + tags);
            }
            copy.add(tag);
        }
        if (copy.contains(MoveTag.INNATE_TECHNIQUE)
            || copy.contains(MoveTag.NON_INNATE_TECHNIQUE)) {
            copy.remove(MoveTag.CURSED_ENERGY);
        }

        this.basePower = basePower;
        this.tags = Collections.unmodifiableSet(copy);
        this.category = categoryFromTags(copy);
        this.delayTicks = delayTicks;
        this.requiresPreviousConnection = requiresPreviousConnection;
        this.avoidable = avoidable;
        this.baseAccuracy = baseAccuracy;
        this.onHitEffects = onHitEffects == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(onHitEffects));
    }

    public HitComponent(
        int basePower,
        MoveCategory category,
        int delayTicks,
        boolean requiresPreviousConnection,
        boolean avoidable
    ) {
        this(basePower, damageTags(category), delayTicks, requiresPreviousConnection, avoidable);
    }

    public HitComponent(int basePower, MoveCategory category, int delayTicks) {
        this(basePower, category, delayTicks, false, true);
    }

    public int getBasePower() { return basePower; }
    public Set<MoveTag> getTags() { return tags; }
    public MoveCategory getCategory() { return category; }
    public int getDelayTicks() { return delayTicks; }
    public boolean requiresPreviousConnection() { return requiresPreviousConnection; }
    public boolean isAvoidable() { return avoidable; }
    public boolean isBlackFlashEligible() { return category.isBlackFlashEligible(); }

    /**
     * Per-hit base accuracy as a fraction [0.0, 1.0], or
     * {@link #INHERIT_MOVE_ACCURACY} when this component should fall back to the
     * parent move's {@link Move#getBaseAccuracy()}.
     */
    public double getBaseAccuracy() { return baseAccuracy; }

    /** True when this component defines its own accuracy rather than inheriting. */
    public boolean hasOwnAccuracy() { return baseAccuracy <= 1.0; }

    /** On-hit status effects applied when this specific component connects. */
    public List<StatusEffect> getOnHitEffects() { return onHitEffects; }

    private static Set<MoveTag> damageTags(MoveCategory category) {
        if (category == null
            || category == MoveCategory.UTILITY
            || category == MoveCategory.DEFENSIVE) {
            throw new IllegalArgumentException("component category must be damaging");
        }
        return category.getTags();
    }

    /** Mirrors move-category derivation while accepting CE implied by technique tags. */
    private static MoveCategory categoryFromTags(Set<MoveTag> tags) {
        boolean physical = tags.contains(MoveTag.PHYSICAL);
        boolean innate = tags.contains(MoveTag.INNATE_TECHNIQUE);
        boolean nonInnate = tags.contains(MoveTag.NON_INNATE_TECHNIQUE);
        boolean cursedEnergy = tags.contains(MoveTag.CURSED_ENERGY);

        if (physical && innate && nonInnate) {
            return MoveCategory.PHYSICAL_INNATE_NON_INNATE_TECHNIQUE;
        }
        if (physical && innate) return MoveCategory.PHYSICAL_INNATE_TECHNIQUE;
        if (physical && nonInnate) return MoveCategory.PHYSICAL_NON_INNATE_TECHNIQUE;
        if (innate && nonInnate) return MoveCategory.INNATE_NON_INNATE_TECHNIQUE;
        if (physical && cursedEnergy) return MoveCategory.PHYSICAL_CURSED_ENERGY;
        if (innate) return MoveCategory.INNATE_TECHNIQUE;
        if (nonInnate) return MoveCategory.NON_INNATE_TECHNIQUE;
        if (physical) return MoveCategory.PHYSICAL;
        if (cursedEnergy) return MoveCategory.CURSED_ENERGY;
        throw new IllegalArgumentException("component tags do not produce a damaging category: " + tags);
    }
}
