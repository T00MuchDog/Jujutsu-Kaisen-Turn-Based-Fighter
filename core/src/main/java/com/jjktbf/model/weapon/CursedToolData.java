package com.jjktbf.model.weapon;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * DTO for serialising/deserialising a cursed tool to/from JSON
 * ({@code data/tools/all_tools.json}).
 *
 * ID scheme: 6-digit zero-padded integer string, auto-assigned by
 * {@link CursedToolRepository}.
 *
 * <p>A cursed tool is a named weapon of a {@link WeaponType} that a character
 * may equip. Equipping one:
 * <ul>
 *   <li>satisfies the equipped-weapon gate for moves of its weapon type, and</li>
 *   <li>makes those moves cost no cursed energy (the tool channels its own).</li>
 * </ul>
 *
 * <p>The imbued technique name and the granted move/ability lists are optional
 * flavour/authoring hooks: a tool with granted content bestows those moves and
 * abilities on its wielder while equipped, similar to a cursed technique.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CursedToolData {

    /** 6-digit auto-assigned, e.g. "000000". */
    public String id;

    /** Player-facing tool name (e.g. "Split Soul Katana"). Required. */
    public String name;

    /**
     * Stored {@link WeaponType} name (e.g. "KATANA"). Required; parsed loudly
     * via {@link #effectiveWeaponType()}.
     */
    public String weaponType;

    /**
     * Optional human-readable name of the technique imbued into the tool
     * (e.g. "Ratio"). Purely descriptive; matching moves are authored on the
     * tool via {@link #grantedMoveIds} instead.
     */
    public String imbuedTechniqueName;

    /**
     * Optional 6-digit move IDs granted to the wielder while the tool is
     * equipped. Granted moves bypass learning requirements (like GRANT_MOVE).
     */
    public List<String> grantedMoveIds;

    /**
     * Optional 6-digit ability IDs granted to the wielder while the tool is
     * equipped.
     */
    public List<String> grantedAbilityIds;

    /** Parsed {@link #weaponType}; fails loudly on an unknown value. */
    public WeaponType effectiveWeaponType() {
        WeaponType type = WeaponType.fromStoredValue(weaponType);
        if (type == null) {
            throw new IllegalArgumentException(
                "Cursed tool '" + name + "' has no weapon type");
        }
        return type;
    }

    /** Deep copy used by the editor's draft handling. */
    public CursedToolData copy() {
        CursedToolData d = new CursedToolData();
        d.id                    = id;
        d.name                  = name;
        d.weaponType            = weaponType;
        d.imbuedTechniqueName   = imbuedTechniqueName;
        d.grantedMoveIds        = grantedMoveIds != null
            ? new ArrayList<>(grantedMoveIds) : new ArrayList<>();
        d.grantedAbilityIds     = grantedAbilityIds != null
            ? new ArrayList<>(grantedAbilityIds) : new ArrayList<>();
        return d;
    }
}
