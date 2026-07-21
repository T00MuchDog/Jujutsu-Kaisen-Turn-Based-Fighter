package com.jjktbf.model.repo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.jjktbf.AppPaths;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

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
 *   <li>{@link #bundledResourcePath()} — classpath path of the bundled default
 *       JSON used to seed on first run (e.g. {@code "data/moves/all_moves.json"}),
 *       or {@code null} for no bundled default.</li>
 *   <li>{@link #seed()} — last-resort fallback when the file is absent AND no
 *       bundled resource is available (default: no-op).</li>
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
     * @param dataDirectory relative directory holding the JSON file
     *                      (e.g. {@code "data/characters"}). Resolved against
      *                      the per-user application-data directory via
      *                      {@link AppPaths#resolve(String)}, so the game works
      *                      regardless of the current working directory.
     * @param fileName      the JSON file name (e.g. {@code "all_moves.json"}).
     */
    protected BaseRepository(String dataDirectory, String fileName) {
        this.dataFile = new File(AppPaths.resolve(dataDirectory), fileName);
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

    /**
     * Classpath path of the bundled default data for this repository
     * (e.g. {@code "data/moves/all_moves.json"}), or {@code null} if the
     * repository has no bundled default. When the data file is absent on first
     * run, {@link #load()} seeds from this resource so the fallback matches the
     * shipped canonical data rather than any hard-coded seed.
     */
    protected String bundledResourcePath() {
        return null;
    }

    /**
     * Seed initial records when the data file does not yet exist AND no bundled
     * classpath resource is available. Default implementation does nothing;
     * subclasses override only when they need a built-in fallback that is not
     * shipped as JSON.
     */
    protected void seed() {}

    /** Entity noun for error messages ({@code "move"}, {@code "character"}, …). */
    protected abstract String entityName();

    // -------------------------------------------------------------------------
    // Load / save
    // -------------------------------------------------------------------------

    /**
     * Load from disk. If the file is absent, seed from the bundled classpath
     * resource (see {@link #bundledResourcePath()}) when available, falling back
     * to {@link #seed()}; then persist and resequence. The resequence keeps IDs
     * consistent regardless of what's on disk.
     */
    public void load() throws IOException {
        if (!dataFile.exists()) {
            seedFromBundledResource();
            resequence();
            save();
            return;
        }
        List<D> list = MAPPER.readValue(dataFile, typeReference());
        store.clear();
        store.addAll(list);
        resequence();
    }

    /**
     * Populate the store from the bundled classpath resource. If the resource is
     * absent (e.g. the data directory is not on this module's classpath, as in
     * some unit tests), falls back to {@link #seed()}.
     */
    private void seedFromBundledResource() throws IOException {
        String resource = bundledResourcePath();
        if (resource != null) {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            if (loader == null) {
                loader = BaseRepository.class.getClassLoader();
            }
            try (InputStream in = loader.getResourceAsStream(resource)) {
                if (in != null) {
                    List<D> bundled = MAPPER.readValue(in, typeReference());
                    store.clear();
                    store.addAll(bundled);
                    return;
                }
            }
        }
        seed();
    }

    /**
     * Persist the store to disk (pretty-printed JSON) atomically: the data is
     * written to a temp file in the same directory, fsynced, then moved over the
     * target file. A crash mid-write therefore leaves the previous file intact
     * rather than truncating it. Creates parent directories as needed.
     */
    public void save() throws IOException {
        File parent = dataFile.getParentFile();
        if (parent != null) parent.mkdirs();
        Path target = dataFile.toPath();
        Path directory = target.getParent();
        if (directory == null) {
            throw new IOException("Data file has no parent directory: " + dataFile);
        }
        FileAttribute<?>[] attrs = posixOwnerOnlyAttributes(directory);
        Path temp = Files.createTempFile(directory, ".repo-", ".tmp", attrs);
        boolean moved = false;
        try {
            // Serialize to memory first so a serialisation failure does not leave
            // a partial temp file that looks valid on disk.
            byte[] json = MAPPER.writeValueAsBytes(store);
            Files.write(temp, json);
            try {
                Files.move(temp, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
            // Re-apply restrictive permissions after the move when supported,
            // since REPLACE_EXISTING may keep the destination's prior mode.
            applyPosixOwnerOnly(target);
        } finally {
            if (!moved) Files.deleteIfExists(temp);
        }
    }

    private static FileAttribute<?>[] posixOwnerOnlyAttributes(Path directory) {
        try {
            if (directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-------");
                return new FileAttribute<?>[] { PosixFilePermissions.asFileAttribute(perms) };
            }
        } catch (RuntimeException ignored) {
            // Best-effort: fall back to platform defaults.
        }
        return new FileAttribute<?>[0];
    }

    private static void applyPosixOwnerOnly(Path path) {
        try {
            if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
            }
        } catch (RuntimeException | IOException ignored) {
            // Best-effort permission hardening; non-fatal.
        }
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
