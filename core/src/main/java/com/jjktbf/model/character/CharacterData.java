package com.jjktbf.model.character;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveData;
import com.jjktbf.model.move.MoveRepository;

import java.util.*;

/**
 * DTO for serialising/deserialising a character to/from JSON.
 *
 * ID scheme: 6-digit zero-padded integer string, auto-assigned by CharacterRepository.
 *
 * Innate technique is stored as a plain name string (e.g. "Shrine").
 * Move pool is stored as a list of 6-digit move IDs referencing MoveRepository.
 * If a move ID cannot be resolved at load time, it is skipped with a warning.
 *
 * Abilities are stub objects (name + description only) for now.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CharacterData {

    public String id;       // 6-digit auto-assigned, e.g. "000000"
    public String name;
    /** Player-facing character flavour text shown on selection pages. */
    public String description = "";
    /** Relative graphics resource path, e.g. {@code assets/characters/yuji.png}. */
    public String spriteAsset;

    /**
     * Human-readable innate technique name (e.g. "Shrine", "Blood Manipulation").
     * Null means no innate technique.
     * Matched case-insensitively against Move.requiredTechniqueId when loading moves.
     * Will be replaced by a Technique ID reference once the Technique class is implemented.
     */
    public String innateTechniqueName;

    // --- Stats (all integers, range 10–300, baseline 80) ---
    public int vitality               = 80;
    public int strength               = 80;
    public int durability             = 80;
    public int speed                  = 80;
    public int cursedEnergyReserves   = 80;
    public int cursedEnergyEfficiency = 80;
    public int cursedEnergyOutput     = 80;
    public int jujutsuSkill           = 80;
    public int combatAbility          = 80;
    public int cursedTechniqueMastery = 80;

    /**
     * 6-digit move IDs from MoveRepository, in order of assignment.
     * Guaranteed moves (Basic Punch, Basic Block) should always be first.
     */
    public List<String> moveIds;

    /**
     * 6-digit ability IDs from AbilityRepository.
     * Resolved to Ability domain objects at load time via toCharacter().
     */
    public List<String> abilityIds;

    // -------------------------------------------------------------------------
    // Derived helpers
    // -------------------------------------------------------------------------

    public CharacterStats toCharacterStats() {
        return new CharacterStats.Builder()
            .vitality(vitality)
            .strength(strength)
            .durability(durability)
            .speed(speed)
            .cursedEnergyReserves(cursedEnergyReserves)
            .cursedEnergyEfficiency(cursedEnergyEfficiency)
            .cursedEnergyOutput(cursedEnergyOutput)
            .jujutsuSkill(jujutsuSkill)
            .combatAbility(combatAbility)
            .cursedTechniqueMastery(cursedTechniqueMastery)
            .build();
    }

    /** Compute derived combat stats for display purposes (no character object needed). */
    public CombatStats toCombatStats() {
        return new CombatStats(toCharacterStats());
    }

    /**
     * Build a Character domain object, resolving move IDs from the given repository.
     * Move IDs that don't exist in the repository are skipped with a console warning.
     */
    public Character toCharacter(MoveRepository moveRepo) {
        return toCharacter(moveRepo, null);
    }

    public Character toCharacter(MoveRepository moveRepo, AbilityRepository abilityRepo) {
        CharacterStats stats = toCharacterStats();
        List<Move> moves = new ArrayList<>();
        List<Ability> abilities = new ArrayList<>();

        if (moveIds != null) {
            for (String moveId : moveIds) {
                var found = moveRepo.findById(moveId);
                if (found.isPresent()) {
                    try {
                        moves.add(found.get().toMove());
                    } catch (Exception e) {
                        System.err.println("[WARN] Could not build move " + moveId + ": " + e.getMessage());
                    }
                } else {
                    System.err.println("[WARN] Move ID " + moveId + " not found in repository — skipped for character '" + name + "'");
                }
            }
        }

        if (abilityRepo != null && abilityIds != null) {
            for (String abilityId : abilityIds) {
                var found = abilityRepo.findById(abilityId);
                if (found.isPresent()) {
                    abilities.add(new Ability(found.get()));
                } else {
                    System.err.println("[WARN] Ability ID " + abilityId + " not found in repository — skipped for character '" + name + "'");
                }
            }
        }

        return new SorcererCharacter(id, name, stats, innateTechniqueName, moves, abilities);
    }

    // -------------------------------------------------------------------------
    // Conversion: CharacterData → CharacterData (from domain Character)
    // -------------------------------------------------------------------------

    public static CharacterData fromCharacter(Character character) {
        CharacterData d = new CharacterData();
        d.id                    = character.getId();
        d.name                  = character.getName();
        d.innateTechniqueName   = character.getInnateTechniqueName();

        CharacterStats cs = character.getBaseStats();
        d.vitality               = cs.getVitality();
        d.strength               = cs.getStrength();
        d.durability             = cs.getDurability();
        d.speed                  = cs.getSpeed();
        d.cursedEnergyReserves   = cs.getCursedEnergyReserves();
        d.cursedEnergyEfficiency = cs.getCursedEnergyEfficiency();
        d.cursedEnergyOutput     = cs.getCursedEnergyOutput();
        d.jujutsuSkill           = cs.getJujutsuSkill();
        d.combatAbility          = cs.getCombatAbility();
        d.cursedTechniqueMastery = cs.getCursedTechniqueMastery();

        d.moveIds = character.getKnownMoves().stream()
            .map(Move::getId)
            .toList();

        return d;
    }
}
