package com.jjktbf.model.character;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.jjktbf.model.move.MoveRepository;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Persistent repository for character definitions.
 *
 * Mirrors MoveRepository exactly in structure and ID scheme.
 * Stored at: data/characters/all_characters.json
 *
 * ID scheme: 6-digit zero-padded sequential integers.
 * Deleting a character resequences all IDs — no gaps.
 *
 * On first run (no file), seeds Yuji Itadori and Ryomen Sukuna from CharacterFactory.
 */
public class CharacterRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);

    private final File dataFile;
    private final List<CharacterData> store = new ArrayList<>();

    public CharacterRepository(String dataDirectory) {
        this.dataFile = new File(dataDirectory, "all_characters.json");
    }

    // -------------------------------------------------------------------------
    // Load
    // -------------------------------------------------------------------------

    public void load() throws IOException {
        if (!dataFile.exists()) {
            seedFromFactory();
            resequence();
            save();
            return;
        }
        List<CharacterData> list = MAPPER.readValue(dataFile, new TypeReference<>() {});
        store.clear();
        store.addAll(list);
        resequence(); // ensure IDs are consistent
    }

    private void seedFromFactory() {
        store.clear();

        // Yuji — no innate technique
        // Move IDs correspond to positions in the seeded MoveRepository (000000–000011)
        CharacterData yuji = new CharacterData();
        yuji.name                  = "Yuji Itadori";
        yuji.innateTechniqueName   = null;
        yuji.vitality              = 175;
        yuji.strength              = 210;
        yuji.durability            = 190;
        yuji.speed                 = 200;
        yuji.cursedEnergyReserves  = 160;
        yuji.cursedEnergyEfficiency= 100;
        yuji.cursedEnergyOutput    = 130;
        yuji.jujutsuSkill          = 140;
        yuji.combatAbility         = 220;
        yuji.cursedTechniqueMastery= 10;
        // 000000 Basic Punch, 000001 Basic Block, 000002 Heavy Punch,
        // 000003 Rapid Strikes, 000004 Divekick, 000005 Cursed Strike,
        // 000006 Divergent Fist, 000010 Cursed Energy Armor, 000011 Iron Wall
        yuji.moveIds = java.util.List.of(
            "000000", "000001", "000003", "000002", "000004",
            "000005", "000006", "000010", "000011");
        store.add(yuji);

        // Sukuna — innate technique: Shrine
        CharacterData sukuna = new CharacterData();
        sukuna.name                  = "Ryomen Sukuna";
        sukuna.innateTechniqueName   = "Shrine";
        sukuna.vitality              = 300;
        sukuna.strength              = 280;
        sukuna.durability            = 270;
        sukuna.speed                 = 290;
        sukuna.cursedEnergyReserves  = 300;
        sukuna.cursedEnergyEfficiency= 250;
        sukuna.cursedEnergyOutput    = 295;
        sukuna.jujutsuSkill          = 290;
        sukuna.combatAbility         = 285;
        sukuna.cursedTechniqueMastery= 300;
        // 000000 Basic Punch, 000001 Basic Block, 000002 Heavy Punch,
        // 000003 Rapid Strikes, 000005 Cursed Strike,
        // 000007 Dismantle, 000008 Cleave, 000009 Fleshy Strike,
        // 000010 Cursed Energy Armor, 000011 Iron Wall
        sukuna.moveIds = java.util.List.of(
            "000000", "000001", "000003", "000002", "000005",
            "000007", "000008", "000009", "000010", "000011");
        store.add(sukuna);
    }

    // -------------------------------------------------------------------------
    // ID management
    // -------------------------------------------------------------------------

    public static String formatId(int index) {
        return String.format("%06d", index);
    }

    private void resequence() {
        for (int i = 0; i < store.size(); i++) {
            store.get(i).id = formatId(i);
        }
    }

    public String nextId() {
        return formatId(store.size());
    }

    // -------------------------------------------------------------------------
    // Save
    // -------------------------------------------------------------------------

    public void save() throws IOException {
        dataFile.getParentFile().mkdirs();
        MAPPER.writeValue(dataFile, store);
    }

    // -------------------------------------------------------------------------
    // CRUD
    // -------------------------------------------------------------------------

    public List<CharacterData> getAll() {
        return Collections.unmodifiableList(store);
    }

    public Optional<CharacterData> findById(String id) {
        return store.stream().filter(c -> id.equals(c.id)).findFirst();
    }

    public boolean exists(String id) {
        return store.stream().anyMatch(c -> id.equals(c.id));
    }

    /** Append a new character. ID is auto-assigned; any existing id field is ignored. */
    public void add(CharacterData cd) {
        cd.id = nextId();
        store.add(cd);
    }

    /** Replace an existing character in-place by id. */
    public void update(CharacterData cd) {
        for (int i = 0; i < store.size(); i++) {
            if (store.get(i).id.equals(cd.id)) {
                store.set(i, cd);
                return;
            }
        }
        throw new NoSuchElementException("No character with id: " + cd.id);
    }

    /** Delete by id and resequence. Returns true if something was removed. */
    public boolean delete(String id) {
        boolean removed = store.removeIf(c -> id.equals(c.id));
        if (removed) resequence();
        return removed;
    }

    public int size() { return store.size(); }

    public File getDataFile() { return dataFile; }
}
