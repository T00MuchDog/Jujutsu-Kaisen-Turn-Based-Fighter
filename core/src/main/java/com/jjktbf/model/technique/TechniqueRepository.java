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
 * <p>On first run with no data file and no bundled resource the repository
 * starts empty.
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
