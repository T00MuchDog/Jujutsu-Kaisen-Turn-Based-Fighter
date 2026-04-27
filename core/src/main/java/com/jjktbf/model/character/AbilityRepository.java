package com.jjktbf.model.character;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Persistent repository for ability definitions.
 *
 * Mirrors MoveRepository and CharacterRepository in structure and ID scheme.
 * Stored at: data/abilities/all_abilities.json
 *
 * ID scheme: 6-digit zero-padded sequential integers.
 * Deleting an ability resequences all IDs — no gaps.
 *
 * On first run (no file), seeds Six Eyes and Infinity as example abilities.
 */
public class AbilityRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);

    private final File dataFile;
    private final List<AbilityData> store = new ArrayList<>();

    public AbilityRepository(String dataDirectory) {
        this.dataFile = new File(dataDirectory, "all_abilities.json");
    }

    // -------------------------------------------------------------------------
    // Load
    // -------------------------------------------------------------------------

    public void load() throws IOException {
        if (!dataFile.exists()) {
            seedExamples();
            resequence();
            save();
            return;
        }
        List<AbilityData> list = MAPPER.readValue(dataFile, new TypeReference<>() {});
        store.clear();
        store.addAll(list);
        resequence();
    }

    private void seedExamples() {
        store.clear();

        // Six Eyes
        AbilityData sixEyes = new AbilityData();
        sixEyes.name        = "Six Eyes";
        sixEyes.flavourText = "A rare ocular Jujutsu passed down through the Gojo clan once every several "
            + "generations. The eyes perceive cursed energy with such clarity that virtually none is wasted "
            + "during technique activation. Their bearer is said to be born once in a blue moon.";
        sixEyes.mechanicText = "Unlocks the LIMITLESS innate technique. Sets CURSED_ENERGY_EFFICIENCY to "
            + "MAX (300). Reduces all CE costs to their MINIMUM. Grants +20 ACCURACY on all moves. "
            + "Grants +80 BONUS_POINTS to the point-buy budget.";
        sixEyes.category    = "PASSIVE";
        sixEyes.sourceType  = "CHARACTER";
        sixEyes.effects     = List.of(
            AbilityEffectData.unlockTechnique("Limitless"),
            AbilityEffectData.statSetMax("cursedEnergyEfficiency"),
            AbilityEffectData.ceCostToMinimum(null),
            AbilityEffectData.moveAccuracyAdd(null, 20),
            AbilityEffectData.statBonusPoints(80)
        );
        store.add(sixEyes);

        // Infinity (granted by Limitless technique)
        AbilityData infinity = new AbilityData();
        infinity.name        = "Infinity";
        infinity.flavourText = "The core application of Limitless — an invisible wall of slowed space "
            + "that surrounds the user at all times. Attacks approach but never arrive, "
            + "asymptotically closing the distance without ever reaching their target.";
        infinity.mechanicText = "Requires the LIMITLESS technique. At the start of each round, "
            + "automatically applies BARRIER to self (FIGHT_START). Drains 15 CE at the start of "
            + "each round (COST_CE_PER_ROUND) to sustain.";
        infinity.category    = "PASSIVE";
        infinity.sourceType  = "TECHNIQUE";
        infinity.sourceValue = "Limitless";
        infinity.effects = List.of(
            eff(AbilityEffectType.AUTO_STATUS_APPLY, e -> {
                e.stringValue = "BARRIER"; e.target = "SELF"; e.timing = "ROUND_START";
            }),
            eff(AbilityEffectType.COST_CE_PER_ROUND, e -> e.intValue = 15)
        );
        store.add(infinity);
    }

    /** Inline builder helper for seeding — avoids verbose constructors. */
    @FunctionalInterface
    private interface Configurator { void configure(AbilityEffectData e); }

    private static AbilityEffectData eff(AbilityEffectType type, Configurator config) {
        AbilityEffectData e = new AbilityEffectData();
        e.type = type.name();
        config.configure(e);
        return e;
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

    public List<AbilityData> getAll() {
        return Collections.unmodifiableList(store);
    }

    public Optional<AbilityData> findById(String id) {
        return store.stream().filter(a -> id.equals(a.id)).findFirst();
    }

    public boolean exists(String id) {
        return store.stream().anyMatch(a -> id.equals(a.id));
    }

    public void add(AbilityData ad) {
        ad.id = nextId();
        store.add(ad);
    }

    public void update(AbilityData ad) {
        for (int i = 0; i < store.size(); i++) {
            if (store.get(i).id.equals(ad.id)) { store.set(i, ad); return; }
        }
        throw new NoSuchElementException("No ability with id: " + ad.id);
    }

    public boolean delete(String id) {
        boolean removed = store.removeIf(a -> id.equals(a.id));
        if (removed) resequence();
        return removed;
    }

    public int size() { return store.size(); }

    public File getDataFile() { return dataFile; }
}
