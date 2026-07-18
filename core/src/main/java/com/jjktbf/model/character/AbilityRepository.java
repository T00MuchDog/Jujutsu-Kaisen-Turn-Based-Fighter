package com.jjktbf.model.character;

import com.fasterxml.jackson.core.type.TypeReference;
import com.jjktbf.model.repo.BaseRepository;

import java.util.List;

/**
 * Persistent repository for ability definitions ({@code data/abilities/all_abilities.json}).
 *
 * ID scheme and behaviour are inherited from {@link BaseRepository}: 6-digit
 * zero-padded sequential ids, resequenced on delete.
 *
 * On first run (no file), seeds from the bundled classpath default
 * ({@code data/abilities/all_abilities.json}).
 */
public class AbilityRepository extends BaseRepository<AbilityData> {

    public AbilityRepository(String dataDirectory) {
        super(dataDirectory, "all_abilities.json");
    }

    @Override protected String idOf(AbilityData d)             { return d.id; }
    @Override protected void assignId(AbilityData d, String id) { d.id = id; }
    @Override protected String entityName()                     { return "ability"; }
    @Override protected TypeReference<List<AbilityData>> typeReference() {
        return new TypeReference<>() {};
    }

    @Override protected String bundledResourcePath() {
        return "data/abilities/all_abilities.json";
    }
}
