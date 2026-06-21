package com.jjktbf.model.technique;

import com.fasterxml.jackson.core.type.TypeReference;
import com.jjktbf.model.repo.BaseRepository;

import java.util.List;
import java.util.Optional;

/**
 * Persistent repository for innate technique definitions
 * ({@code data/techniques/all_techniques.json}).
 *
 * <p>ID scheme and CRUD behaviour are inherited from {@link BaseRepository}.
 *
 * <p>A technique's move/ability contents are <b>not</b> stored here — they are
 * discovered at runtime via {@link InnateTechnique#moves} and
 * {@link InnateTechnique#abilities}, which query the move and ability
 * repositories for entries referencing the technique's name.
 *
 * <p>On first run (no file), seeds the techniques referenced by the existing
 * seed data: <b>Shrine</b> (Sukuna's innate technique, used by Dismantle /
 * Cleave / Fleshy Strike) and <b>Limitless</b> (Six Eyes' UNLOCK_TECHNIQUE
 * target, Infinity's TECHNIQUE source).
 */
public class TechniqueRepository extends BaseRepository<InnateTechniqueData> {

    public TechniqueRepository(String dataDirectory) {
        super(dataDirectory, "all_techniques.json");
    }

    @Override protected String idOf(InnateTechniqueData d)            { return d.id; }
    @Override protected void assignId(InnateTechniqueData d, String id){ d.id = id; }
    @Override protected String entityName()                            { return "technique"; }
    @Override protected TypeReference<List<InnateTechniqueData>> typeReference() {
        return new TypeReference<>() {};
    }

    @Override protected void seed() {
        InnateTechniqueData shrine = new InnateTechniqueData();
        shrine.name        = "Shrine";
        shrine.description = "Ryomen Sukuna's innate technique. Cleave and Dismantle "
            + "adaptively carve the target; as CTM rises, more refined applications "
            + "(Fleshy Strike, the Domain: Malevolent Shrine) unlock.";
        super.add(shrine);

        InnateTechniqueData limitless = new InnateTechniqueData();
        limitless.name        = "Limitless";
        limitless.description = "The Gojo clan's innate technique — manipulation of "
            + "space at infinity. Brings things to a halt (Infinity / Blue), "
            + "amplifies them (Red), and converges them (Hollow Purple). Granted to "
            + "bearers of Six Eyes.";
        super.add(limitless);
    }

    /** Convenience: case-insensitive name lookup. */
    public Optional<InnateTechniqueData> findByName(String name) {
        if (name == null) return Optional.empty();
        return getAll().stream()
            .filter(t -> name.equalsIgnoreCase(t.name))
            .findFirst();
    }

    /** True if a technique with the given name (case-insensitive) exists. */
    public boolean nameExists(String name) {
        return findByName(name).isPresent();
    }
}
