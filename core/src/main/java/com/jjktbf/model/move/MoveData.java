package com.jjktbf.model.move;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Plain data object (DTO) for serialising/deserialising a Move to/from JSON.
 *
 * All fields are public for Jackson compatibility — this is deliberately a
 * simple data bag, not a domain object. The domain Move is immutable and
 * built via Move.Builder; MoveData is the mutable JSON representation.
 *
 * JSON file path: data/moves/<id>.json  OR  data/moves/all_moves.json
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MoveData {

    public String  id;
    public String  name;
    public String  description;

    /** MoveCategory enum name, e.g. "PHYSICAL", "INNATE_TECHNIQUE" */
    public String  category;

    public int     basePower;
    public double  baseAccuracy   = 1.0;
    public boolean neverMiss      = false;

    public int     apCost;
    public int     unleashPoint;

    public int     baseCeCost     = 0;
    public int     minCeCost      = 0;
    public int     maxCeCost      = 0;

    /** InterruptType enum name */
    public String  interruptType  = "NONE";

    /** DefenseType enum name */
    public String  defenseType    = "NONE";
    public int     defenseBuffDuration = -1;
    public int     defenseBuffAmount   = 0;

    /** List of on-hit StatusEffect descriptors */
    public List<StatusEffectData> onHitEffects;

    /** List of self StatusEffect descriptors */
    public List<StatusEffectData> selfEffects;

    /** Prerequisite stats: {"strength": 80, "speed": 60, ...} */
    public Map<String, Integer> prerequisites;

    /** Required innate technique id, e.g. "SHRINE". Null if none. */
    public String  requiredTechniqueId;

    public boolean isGuaranteedMove = false;

    // -------------------------------------------------------------------------
    // Status effect sub-DTO
    // -------------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class StatusEffectData {
        /** StatusEffectType enum name */
        public String type;
        public int    durationRounds = 1;
        public double magnitude      = 1.0;
    }

    // -------------------------------------------------------------------------
    // Conversion: MoveData → Move (domain object)
    // -------------------------------------------------------------------------

    public Move toMove() {
        Move.Builder b = new Move.Builder(id)
            .name(name)
            .description(description != null ? description : "")
            .category(MoveCategory.valueOf(category))
            .basePower(basePower)
            .baseAccuracy(baseAccuracy)
            .neverMiss(neverMiss)
            .apCost(apCost)
            .unleashPoint(unleashPoint)
            .baseCeCost(baseCeCost)
            .minCeCost(minCeCost)
            .maxCeCost(maxCeCost)
            .interruptType(InterruptType.valueOf(interruptType))
            .defenseType(DefenseType.valueOf(defenseType))
            .defenseBuffDuration(defenseBuffDuration)
            .defenseBuffAmount(defenseBuffAmount)
            .requiredTechniqueId(requiredTechniqueId)
            .guaranteedMove(isGuaranteedMove);

        if (prerequisites != null)  b.prerequisites(prerequisites);
        if (onHitEffects  != null)  b.onHitEffects(toStatusEffects(onHitEffects));
        if (selfEffects   != null)  b.selfEffects(toStatusEffects(selfEffects));

        return b.build();
    }

    private static List<StatusEffect> toStatusEffects(List<StatusEffectData> dtos) {
        if (dtos == null) return List.of();
        return dtos.stream()
            .map(d -> new StatusEffect(StatusEffectType.valueOf(d.type), d.durationRounds, d.magnitude))
            .toList();
    }

    // -------------------------------------------------------------------------
    // Conversion: Move (domain object) → MoveData
    // -------------------------------------------------------------------------

    public static MoveData fromMove(Move move) {
        MoveData d = new MoveData();
        d.id                  = move.getId();
        d.name                = move.getName();
        d.description         = move.getDescription();
        d.category            = move.getCategory().name();
        d.basePower           = move.getBasePower();
        d.baseAccuracy        = move.getBaseAccuracy();
        d.neverMiss           = move.isNeverMiss();
        d.apCost              = move.getApCost();
        d.unleashPoint        = move.getUnleashPoint();
        d.baseCeCost          = move.getBaseCeCost();
        d.minCeCost           = move.getMinCeCost();
        d.maxCeCost           = move.getMaxCeCost();
        d.interruptType       = move.getInterruptType().name();
        d.defenseType         = move.getDefenseType().name();
        d.defenseBuffDuration = move.getDefenseBuffDuration();
        d.defenseBuffAmount   = move.getDefenseBuffAmount();
        d.requiredTechniqueId = move.getRequiredTechniqueId();
        d.isGuaranteedMove    = move.isGuaranteedMove();
        d.prerequisites       = move.getPrerequisites().isEmpty() ? null : move.getPrerequisites();

        if (!move.getOnHitEffects().isEmpty()) {
            d.onHitEffects = move.getOnHitEffects().stream().map(e -> {
                StatusEffectData sd = new StatusEffectData();
                sd.type            = e.getType().name();
                sd.durationRounds  = e.getDurationRounds();
                sd.magnitude       = e.getMagnitude();
                return sd;
            }).toList();
        }
        if (!move.getSelfEffects().isEmpty()) {
            d.selfEffects = move.getSelfEffects().stream().map(e -> {
                StatusEffectData sd = new StatusEffectData();
                sd.type            = e.getType().name();
                sd.durationRounds  = e.getDurationRounds();
                sd.magnitude       = e.getMagnitude();
                return sd;
            }).toList();
        }
        return d;
    }
}
