package com.jjktbf.model.repo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Persistent, sequential-id repository base for the editor DTOs
 * ({@code MoveData}, {@code CharacterData}, {@code AbilityData}).
 *
 * <h3>Storage</h3>
 * Each subclass owns a JSON file at {@code <dataDirectory>/all_<entity>.json}
 * (e.g. {@code all_moves.json}), serialised via Jackson with indent output.
 *
 * <h3>ID scheme</h3>
 * IDs are 6-digit zero-padded sequential strings ({@code "000000"}, {@code "000001"}, …)
 * keyed by list position. They are <b>not</b> stable: deleting an entry
 * {@link #resequence() resequences} every remaining ID so there are never gaps.
 * The next id a new record will receive is {@link #nextId()}.
 *
 * <h3>Subclass responsibilities</h3>
 * <ul>
 *   <li>{@link #idOf(Object)} / {@link #assignId(Object, String)} — read/write the id field.</li>
 *   <li>{@link #typeReference()} — Jackson deserialisation target.</li>
 *   <li>{@link #seed()} — initial records when the file is absent on first run.</li>
 *   <li>{@link #entityName()} — used in error messages ({@code "move"} / {@code "character"} / …).</li>
 * </ul>
 *
 * @param <D> the DTO type this repository persists.
 */
public abstract class BaseRepository<D> {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);

    /** Ordered store — list position determines ID after every resequence. */
    private final List<D> store = new ArrayList<>();

    private final File dataFile;

    /**
     * @param dataDirectory directory holding the JSON file.
     * @param fileName      the JSON file name (e.g. {@code "all_moves.json"}).
     */
    protected BaseRepository(String dataDirectory, String fileName) {
        this.dataFile = new File(dataDirectory, fileName);
    }

    // -------------------------------------------------------------------------
    // Abstract hooks
    // -------------------------------------------------------------------------

    /** Read the id field off a DTO. */
    protected abstract String idOf(D dto);

    /** Write the id field onto a DTO. */
    protected abstract void assignId(D dto, String id);

    /** Jackson deserialisation target for a list of D. */
    protected abstract TypeReference<List<D>> typeReference();

    /** Seed initial records when the data file does not yet exist. */
    protected abstract void seed();

    /** Entity noun for error messages ({@code "move"}, {@code "character"}, …). */
    protected abstract String entityName();

    // -------------------------------------------------------------------------
    // Load / save
    // -------------------------------------------------------------------------

    /**
     * Load from disk. If the file is absent, {@link #seed()} then persist.
     * Always {@link #resequence() resequences} so IDs are consistent regardless
     * of what's on disk.
     */
    public void load() throws IOException {
        if (!dataFile.exists()) {
            seed();
            resequence();
            save();
            return;
        }
        List<D> list = MAPPER.readValue(dataFile, typeReference());
        store.clear();
        store.addAll(list);
        resequence();
    }

    /** Persist the store to disk (pretty-printed JSON), creating dirs as needed. */
    public void save() throws IOException {
        dataFile.getParentFile().mkdirs();
        MAPPER.writeValue(dataFile, store);
    }

    // -------------------------------------------------------------------------
    // ID management
    // -------------------------------------------------------------------------

    /** Format an index as a 6-digit zero-padded string. */
    public static String formatId(int index) {
        return String.format("%06d", index);
    }

    /** The id the next new record will receive. */
    public String nextId() {
        return formatId(store.size());
    }

    /**
     * Reassign IDs to all records in order (0..n-1). Call after every delete so
     * IDs stay contiguous.
     */
    protected void resequence() {
        for (int i = 0; i < store.size(); i++) {
            assignId(store.get(i), formatId(i));
        }
    }

    // -------------------------------------------------------------------------
    // CRUD
    // -------------------------------------------------------------------------

    /** Unmodifiable view of all records, in id order. */
    public List<D> getAll() {
        return Collections.unmodifiableList(store);
    }

    public Optional<D> findById(String id) {
        return store.stream().filter(d -> id.equals(idOf(d))).findFirst();
    }

    public boolean exists(String id) {
        return store.stream().anyMatch(d -> id.equals(idOf(d)));
    }

    /**
     * Append a new record. If the record's id is blank, the canonical next id is
     * assigned first; a non-blank id is kept as-is (used by the move seed).
     */
    public void add(D dto) {
        if (idOf(dto) == null || idOf(dto).isBlank()) {
            assignId(dto, nextId());
        }
        store.add(dto);
    }

    /**
     * Replace an existing record in-place (matched by its current id).
     * @throws NoSuchElementException if no record has that id.
     */
    public void update(D dto) {
        String id = idOf(dto);
        for (int i = 0; i < store.size(); i++) {
            if (idOf(store.get(i)).equals(id)) {
                store.set(i, dto);
                return;
            }
        }
        throw new NoSuchElementException("No " + entityName() + " with id: " + id);
    }

    /**
     * Delete by id, then resequence remaining IDs. Returns whether a record was
     * removed.
     */
    public boolean delete(String id) {
        boolean removed = store.removeIf(d -> id.equals(idOf(d)));
        if (removed) resequence();
        return removed;
    }

    public int size() { return store.size(); }

    public File getDataFile() { return dataFile; }
}
