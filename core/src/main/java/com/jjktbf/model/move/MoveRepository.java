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
 * Moves are stored in data/moves/all_moves.json.
 *
 * ID scheme:
 *   Every move has a 6-digit zero-padded numeric string ID: "000000", "000001", …
 *   IDs are assigned automatically and are sequential with no gaps.
 *   When a move is deleted the remaining moves are resequenced so IDs remain
 *   contiguous (0..n-1 for n moves). The order is preserved.
 *
 * Technique names are plain strings stored on MoveData.requiredTechniqueName.
 * A separate technique-id registry (TechniqueRepository) handles technique→id mapping.
 */
public class MoveRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);

    private final File dataFile;

    /**
     * Ordered list — position determines ID after every resequence.
     * We do NOT use a Map keyed by id because ids are reassigned on delete.
     */
    private final List<MoveData> store = new ArrayList<>();

    public MoveRepository(String dataDirectory) {
        this.dataFile = new File(dataDirectory, "all_moves.json");
    }

    // -------------------------------------------------------------------------
    // Load
    // -------------------------------------------------------------------------

    public void load() throws IOException {
        if (!dataFile.exists()) {
            seedFromCoreMoves();
            resequence();
            save();
            return;
        }
        List<MoveData> list = MAPPER.readValue(dataFile, new TypeReference<>() {});
        store.clear();
        store.addAll(list);
        // Ensure IDs are consistent (handle old-format string IDs gracefully)
        resequence();
    }

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
        store.clear();
        for (Move m : coreMoves) {
            store.add(MoveData.fromMove(m));
        }
    }

    // -------------------------------------------------------------------------
    // ID management
    // -------------------------------------------------------------------------

    /** Format an integer index as a 6-digit zero-padded string. */
    public static String formatId(int index) {
        return String.format("%06d", index);
    }

    /**
     * Reassign IDs to all moves in order: 000000, 000001, …
     * Call after every delete so there are never gaps.
     */
    private void resequence() {
        for (int i = 0; i < store.size(); i++) {
            store.get(i).id = formatId(i);
        }
    }

    /** Return the ID that the next new move will receive. */
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

    public List<MoveData> getAll() {
        return Collections.unmodifiableList(store);
    }

    public Optional<MoveData> findById(String id) {
        return store.stream().filter(m -> id.equals(m.id)).findFirst();
    }

    public boolean exists(String id) {
        return store.stream().anyMatch(m -> id.equals(m.id));
    }

    /**
     * Append a new move. The id field is ignored — a fresh sequential ID is assigned.
     */
    public void add(MoveData md) {
        md.id = nextId();
        store.add(md);
    }

    /**
     * Replace an existing move in-place (by its current id).
     * The id on md must match an existing entry.
     */
    public void update(MoveData md) {
        for (int i = 0; i < store.size(); i++) {
            if (store.get(i).id.equals(md.id)) {
                store.set(i, md);
                return;
            }
        }
        throw new NoSuchElementException("No move with id: " + md.id);
    }

    /**
     * Delete a move by id, then resequence all remaining IDs.
     * Returns true if the move existed and was removed.
     */
    public boolean delete(String id) {
        boolean removed = store.removeIf(m -> id.equals(m.id));
        if (removed) resequence();
        return removed;
    }

    public int size() { return store.size(); }

    public File getDataFile() { return dataFile; }
}
