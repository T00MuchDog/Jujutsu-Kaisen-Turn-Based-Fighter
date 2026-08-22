package com.jjktbf.model.weapon;

import com.fasterxml.jackson.core.type.TypeReference;
import com.jjktbf.model.repo.BaseRepository;

import java.util.List;

/**
 * Persistent repository for cursed tool definitions
 * ({@code data/tools/all_tools.json}).
 *
 * <p>ID scheme and CRUD behaviour are inherited from {@link BaseRepository}.
 * Tools are referenced by characters via {@code equippedCursedToolIds}.
 */
public class CursedToolRepository extends BaseRepository<CursedToolData> {

    public CursedToolRepository(String dataDirectory) {
        super(dataDirectory, "all_tools.json");
    }

    @Override protected String bundledResourcePath() { return "data/tools/all_tools.json"; }

    @Override protected String idOf(CursedToolData d)             { return d.id; }
    @Override protected void assignId(CursedToolData d, String id){ d.id = id; }
    @Override protected String entityName()                       { return "cursed tool"; }
    @Override protected TypeReference<List<CursedToolData>> typeReference() {
        return new TypeReference<>() {};
    }
}
