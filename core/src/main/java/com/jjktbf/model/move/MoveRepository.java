package com.jjktbf.model.move;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Persistent repository for move definitions.
 *
 * Moves are stored as a single JSON file: data/moves/all_moves.json
 * The repository operates on MoveData DTOs for persistence and converts
 * to/from Move domain objects as needed.
 *
 * On first run (no file), it seeds the file from CoreMoves hardcoded definitions
 * so existing content is not lost.
 */
public class MoveRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);

    private final File dataFile;

    /** In-memory store: id → MoveData */
    private final Map<String, MoveData> store = new LinkedHashMap<>();

    public MoveRepository(String dataDirectory) {
        this.dataFile = new File(dataDirectory, "all_moves.json");
    }

    // -------------------------------------------------------------------------
    // Load
    // -------------------------------------------------------------------------

    /**
     * Load all moves from disk. If the file does not exist, seeds from CoreMoves
     * and writes the seed file.
     */
    public void load() throws IOException {
        if (!dataFile.exists()) {
            seedFromCoreMoves();
            save();
            return;
        }
        List<MoveData> list = MAPPER.readValue(dataFile, new TypeReference<>() {});
        store.clear();
        for (MoveData md : list) {
            store.put(md.id, md);
        }
    }

    /** Seed from CoreMoves when no data file exists yet. */
    private void seedFromCoreMoves() {
        List<Move> coreMoves = List.of(
            CoreMoves.basicPunch(),
            CoreMoves.basicBlock(),
            CoreMoves.heavyPunch(),
            CoreMoves.rapidStrikes(),
            CoreMoves.divekick(),
            CoreMoves.cursedStrike(),
            CoreMoves.divergentFist(),
            CoreMoves.dismantle(),
            CoreMoves.cleave(),
            CoreMoves.sukunaFleshyStrike(),
            CoreMoves.cursedEnergyArmor(),
            CoreMoves.ironwall()
        );
        for (Move m : coreMoves) {
            store.put(m.getId(), MoveData.fromMove(m));
        }
    }

    // -------------------------------------------------------------------------
    // Save
    // -------------------------------------------------------------------------

    /** Write all moves to disk. Creates parent directories if needed. */
    public void save() throws IOException {
        dataFile.getParentFile().mkdirs();
        MAPPER.writeValue(dataFile, new ArrayList<>(store.values()));
    }

    // -------------------------------------------------------------------------
    // CRUD
    // -------------------------------------------------------------------------

    public List<MoveData> getAll() {
        return new ArrayList<>(store.values());
    }

    public Optional<MoveData> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    public boolean exists(String id) {
        return store.containsKey(id);
    }

    /** Insert or replace a move. Does NOT auto-save — call save() explicitly. */
    public void upsert(MoveData md) {
        store.put(md.id, md);
    }

    /** Delete a move by id. Returns true if it existed. */
    public boolean delete(String id) {
        return store.remove(id) != null;
    }

    public File getDataFile() { return dataFile; }
}
