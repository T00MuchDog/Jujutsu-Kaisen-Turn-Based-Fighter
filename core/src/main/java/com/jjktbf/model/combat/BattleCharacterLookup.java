package com.jjktbf.model.combat;

import com.jjktbf.model.character.Character;

import java.util.Optional;

/**
 * Read-only lookup of canonical character definitions by id, used by the combat
 * engine to resolve summon targets (e.g. {@code Move.summonCharacterId}) without
 * loading files or depending on a repository inside the engine.
 *
 * <p>The server-side authoritative session injects a lookup backed by its loaded
 * {@code ContentCatalog}; local/headless tests inject a small fixture. Lookups
 * return {@link Optional#empty()} for an unknown id so the caller can fail loudly
 * with a domain-appropriate error rather than an NPE.
 */
@FunctionalInterface
public interface BattleCharacterLookup {

    /** Resolve a canonical character definition by id, if present. */
    Optional<Character> findCharacter(String characterId);
}
