package com.jjktbf.model.technique;

import com.jjktbf.model.character.AbilityData;
import com.jjktbf.model.character.AbilityRepository;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveData;
import com.jjktbf.model.move.MoveRepository;
import com.jjktbf.model.move.MoveTag;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An innate cursed technique domain view.
 *
 * <p>Move/ability membership is discovered by querying the child repositories
 * for entries that reference this technique's {@link #name}:
 * <ul>
 *   <li>{@link #moves(int, MoveRepository)} — every {@link MoveData} whose
 *       {@code requiredTechniqueId == name}, filtered to those whose
 *       {@code cursedTechniqueMastery} prerequisite the character meets, sorted
 *       ascending by that prerequisite (the progression ladder).</li>
 *   <li>{@link #abilities(int, AbilityRepository)} — every {@link AbilityData}
 *       with {@code sourceType == "TECHNIQUE"} and {@code sourceValue == name},
 *       filtered to {@code masteryThreshold <= mastery}, sorted ascending.</li>
 * </ul>
 *
 * <p>Authored node positions and multi-part unlock requirements are persisted on
 * {@link InnateTechniqueData#skillTree}. These methods remain lightweight
 * repository discovery helpers for callers that only need mastery filtering.
 *
 * <p>Technique identity is by {@link #name} (case-insensitive), matching the
 * existing string-based coupling on {@code MoveData.requiredTechniqueId} and
 * {@code CharacterData.innateTechniqueName}.
 */
public final class InnateTechnique {

    private final String id;
    private final String name;
    private final String description;

    public InnateTechnique(String id, String name, String description) {
        this.id          = id;
        this.name        = name;
        this.description = description;
    }

    public String getId()          { return id; }
    public String getName()        { return name; }
    public String getDescription() { return description; }

    // -------------------------------------------------------------------------
    // Discovery
    // -------------------------------------------------------------------------

    /**
     * The moves that belong to this technique and are unlocked at or below the
     * given cursed-technique-mastery value, sorted ascending by their CTM
     * prerequisite (the progression ladder). Moves without a CTM prerequisite
     * are treated as CTM 0 (always unlocked) and appear first.
     *
     * @param ctm      the character's cursed-technique-mastery value
     * @param moveRepo the move repository to query
     */
    public List<MoveData> moves(int ctm, MoveRepository moveRepo) {
        return moveRepo.getAll().stream()
            .filter(md -> nameMatches(md.requiredTechniqueId))
            .filter(md -> ctmPrereqOf(md) <= ctm)
            .sorted(Comparator.comparingInt(InnateTechnique::ctmPrereqOf))
            .toList();
    }

    /**
     * The technique-sourced abilities unlocked at or below the given mastery
     * value, sorted ascending by their {@link AbilityData#masteryThreshold}.
     *
     * @param mastery     the character's governing mastery value (CTM for the
     *                    innate technique; a Copy ability may substitute another)
     * @param abilityRepo the ability repository to query
     */
    public List<AbilityData> abilities(int mastery, AbilityRepository abilityRepo) {
        return abilityRepo.getAll().stream()
            .filter(ad -> "TECHNIQUE".equalsIgnoreCase(ad.sourceType)
                       && nameMatches(ad.sourceValue))
            .filter(ad -> (ad.masteryThreshold) <= mastery)
            .sorted(Comparator.comparingInt(ad -> ad.masteryThreshold))
            .toList();
    }

    // -------------------------------------------------------------------------
    // Identity
    // -------------------------------------------------------------------------

    /** Case-insensitive name match against a nullable reference string. */
    private boolean nameMatches(String other) {
        return other != null && other.equalsIgnoreCase(name);
    }

    /**
     * Extract the {@code cursedTechniqueMastery} prerequisite from a move's
     * prerequisite map, resolving via the {@link MoveTag} convention that
     * innate-technique moves are gated by CTM. Returns 0 if absent (treated as
     * always unlocked) so starter moves with no explicit CTM prereq still appear.
     */
    private static int ctmPrereqOf(MoveData md) {
        if (md.prerequisites == null) return 0;
        // Look up by the canonical CTM field name and its aliases.
        Integer v = lookupStat(md.prerequisites, "cursedTechniqueMastery");
        if (v == null) v = lookupStat(md.prerequisites, "cursedtechniquemastery");
        if (v == null) v = lookupStat(md.prerequisites, "ctm");
        return v == null ? 0 : v;
    }

    /** Case-insensitive, whitespace-insensitive key lookup on a prereq map. */
    private static Integer lookupStat(Map<String, Integer> map, String key) {
        String norm = normalise(key);
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            if (normalise(e.getKey()).equals(norm)) return e.getValue();
        }
        return null;
    }

    private static String normalise(String s) {
        return s == null ? "" : s.toLowerCase().replace("_", "").replace(" ", "");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof InnateTechnique other)) return false;
        return name != null && name.equalsIgnoreCase(other.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name == null ? null : name.toLowerCase());
    }

    @Override
    public String toString() {
        return "InnateTechnique{" + name + "}";
    }
}
