package com.jjktbf.model.character;

import com.fasterxml.jackson.core.type.TypeReference;
import com.jjktbf.model.repo.BaseRepository;

import java.util.List;

/**
 * Persistent repository for character definitions ({@code data/characters/all_characters.json}).
 *
 * ID scheme and behaviour are inherited from {@link BaseRepository}: 6-digit
 * zero-padded sequential ids, resequenced on delete.
 *
 * On first run (no file), seeds from the bundled classpath default
 * ({@code data/characters/all_characters.json}).
 *
 * <b>Note:</b> characters reference move ids by position
 * (see {@link com.jjktbf.model.move.MoveRepository}). Resequencing a move out
 * from under a character's {@code moveIds} orphans the reference — load()
 * resolves move ids leniently (missing moves are skipped with a warning).
 */
public class CharacterRepository extends BaseRepository<CharacterData> {

    public CharacterRepository(String dataDirectory) {
        super(dataDirectory, "all_characters.json");
    }

    @Override protected String idOf(CharacterData d)             { return d.id; }
    @Override protected void assignId(CharacterData d, String id) { d.id = id; }
    @Override protected String entityName()                       { return "character"; }
    @Override protected TypeReference<List<CharacterData>> typeReference() {
        return new TypeReference<>() {};
    }

    @Override protected String bundledResourcePath() {
        return "data/characters/all_characters.json";
    }
}
