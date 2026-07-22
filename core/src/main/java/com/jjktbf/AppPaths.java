package com.jjktbf;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/**
 * Resolves all on-disk locations the game reads from and writes to, in a way
 * that is independent of the process <em>current working directory</em>.
 *
 * <h3>Why this exists</h3>
 * Historically the repositories and crash log used relative paths
 * (e.g. {@code new File("data/characters")}, {@code battle_crash.log}),
 * resolved against whatever directory the JVM was launched from. That works
 * when running from the project root in an IDE, but a packaged application
 * lives in a <b>read-only</b> install directory and can be launched from
 * anywhere. Writing there fails (or, worse, silently scatters files).
 *
 * <h3>Solution</h3>
 * All mutable state is kept under a per-user application-data directory that
 * is specific to this game and stable across launches and upgrades:
 * <ul>
 *   <li><b>macOS:</b> {@code ~/Library/Application Support/JujutsuKaisenFighter/}</li>
     *   <li><b>Windows:</b> {@code %APPDATA%\JujutsuKaisenFighter\}</li>
     *   <li><b>Linux / other:</b> {@code ~/.jujutsukaisenfighter/}</li>
     * </ul>
     * Local tools can override this with {@code -Djjktbf.data.root=/path} or
     * {@code JJKTBF_DATA_ROOT}; this is useful when launching two test clients
     * that need independent guest credentials.
 * Because this directory is outside the install location, installing a newer
 * version of the game never deletes a player's data.
 *
 * <h3>Seeding</h3>
 * On first launch the bundled default game-data JSON (shipped on the classpath
 * under {@code data/...}) is copied into the per-user data directory. Editor
 * changes persist while the player runs the same game version. Installing a
 * newer release replaces every bundled game-data catalog with the definitions
 * shipped in that release.
 *
 * <p>This class is pure Java (no libGDX) so it can live in the {@code core}
 * module and be used by both the repositories and the desktop launcher.
 */
public final class AppPaths {

    /** Display / folder name of the application. */
    public static final String APP_NAME = "Jujutsu Kaisen Fighter";

    /** Optional JVM property used to isolate local client profiles. */
    public static final String DATA_ROOT_SYSTEM_PROPERTY = "jjktbf.data.root";

    /** Environment equivalent of {@link #DATA_ROOT_SYSTEM_PROPERTY}. */
    public static final String DATA_ROOT_ENVIRONMENT_VARIABLE = "JJKTBF_DATA_ROOT";

    /**
     * JVM property ({@code -Djjktbf.authoring=true}) that makes the running game
     * edit the <em>source</em> game-data files (the ones bundled into releases)
     * instead of the per-user copies. Intended for the developer's main build:
     * changes saved in authoring mode become the shipped defaults once
     * committed and released. Released builds never set this flag, so players
     * keep the normal per-user behavior.
     */
    public static final String AUTHORING_SYSTEM_PROPERTY = "jjktbf.authoring";

    /** Filesystem-safe folder name used under the per-user data root. */
    private static final String APP_DIR_NAME = "JujutsuKaisenFighter";

    /** Classpath prefix for the bundled default game data. */
    private static final String BUNDLED_DATA_PREFIX = "data";

    private static final String BUNDLED_MOVES = "data/moves/all_moves.json";
    private static final String BUNDLED_CHARACTERS = "data/characters/all_characters.json";
    private static final String BUNDLED_ABILITIES = "data/abilities/all_abilities.json";
    private static final String BUNDLED_TECHNIQUES = "data/techniques/all_techniques.json";
    private static final String BUNDLED_VERSION = "jjktbf-version.properties";
    private static final String DATA_VERSION_FILE = "data-release-version";
    private static final List<String> BUNDLED_DATA_FILES = List.of(
        BUNDLED_MOVES,
        BUNDLED_ABILITIES,
        BUNDLED_TECHNIQUES,
        BUNDLED_CHARACTERS
    );
    private static final String LEGACY_CHARACTER_SPRITE_PREFIX = "assets/characters/";
    private static final String CHARACTER_SPRITE_PREFIX = "assets/sprites/characters/";
    private static final String YUJI_FRONT_SPRITE = CHARACTER_SPRITE_PREFIX + "yuji_frontsprite.png";
    private static final String MEGUMI_FRONT_SPRITE = CHARACTER_SPRITE_PREFIX + "megumi_frontsprite.png";
    private static final Map<String, String> LEGACY_DEFAULT_CHARACTER_SPRITES = Map.of(
        "assets/characters/test_a.png", YUJI_FRONT_SPRITE,
        "assets/characters/test_b.png", YUJI_FRONT_SPRITE,
        "assets/characters/test_c.png", MEGUMI_FRONT_SPRITE,
        "assets/sprites/characters/test_a.png", YUJI_FRONT_SPRITE,
        "assets/sprites/characters/test_b.png", YUJI_FRONT_SPRITE,
        "assets/sprites/characters/test_c.png", MEGUMI_FRONT_SPRITE
    );

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);

    private AppPaths() {}

    // -------------------------------------------------------------------------
    // Per-user root
    // -------------------------------------------------------------------------

    /**
     * The configured or per-user application-data root, created if absent.
     * Stable across launches and upgrades; outside the read-only install
     * directory unless explicitly overridden for development.
     */
    public static Path root() {
        Path base;
        boolean explicitlyConfigured = false;
        String configuredRoot = System.getProperty(DATA_ROOT_SYSTEM_PROPERTY);
        if (configuredRoot == null || configuredRoot.isBlank()) {
            configuredRoot = System.getenv(DATA_ROOT_ENVIRONMENT_VARIABLE);
        }
        if (configuredRoot != null && !configuredRoot.isBlank()) {
            explicitlyConfigured = true;
            base = Paths.get(configuredRoot.trim()).toAbsolutePath().normalize();
        } else {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("mac") || os.contains("darwin")) {
                base = Paths.get(System.getProperty("user.home"),
                                 "Library", "Application Support", APP_DIR_NAME);
            } else if (os.contains("win")) {
                String appdata = System.getenv("APPDATA");
                if (appdata != null && !appdata.isBlank()) {
                    base = Paths.get(appdata, APP_DIR_NAME);
                } else {
                    base = Paths.get(System.getProperty("user.home"), APP_DIR_NAME);
                }
            } else {
                // Linux / unspecified Unix.
                String xdg = System.getenv("XDG_DATA_HOME");
                if (xdg != null && !xdg.isBlank()) {
                    base = Paths.get(xdg, APP_DIR_NAME.toLowerCase());
                } else {
                    base = Paths.get(System.getProperty("user.home"),
                                     "." + APP_DIR_NAME.toLowerCase());
                }
            }
        }
        try {
            Files.createDirectories(base);
        } catch (IOException e) {
            if (explicitlyConfigured) {
                throw new IllegalStateException(
                    "The configured application data root cannot be created: " + base,
                    e
                );
            }
            // Falling back to a relative dir keeps the game playable even if
            // the canonical location is unwritable; the path is still fixed.
            base = Paths.get(APP_DIR_NAME.toLowerCase());
        }
        return base;
    }

    /** Directory holding the editable game-data JSON ({@code data/...}). */
    public static Path dataDir() {
        return root().resolve("data");
    }

    /**
     * The crash / diagnostic log file, under {@code <root>/logs/}.
     */
    public static Path logFile() {
        Path logs = root().resolve("logs");
        try {
            Files.createDirectories(logs);
        } catch (IOException ignored) {
            // Best effort; the caller falls back below if needed.
        }
        return logs.resolve("battle_crash.log");
    }

    /**
     * Append a throwable (with a timestamped header and full stack trace) to the
     * diagnostic log. Used by network/UI layers to record failures that must not
     * break their caller (e.g. listener callbacks) but should not vanish silently.
     * Best-effort: any IO error is swallowed so this never throws.
     */
    public static void logException(Throwable failure) {
        if (failure == null) return;
        try {
            java.io.PrintWriter pw = new java.io.PrintWriter(
                new java.io.FileWriter(logFile().toFile(), true));
            try {
                pw.println("===== " + java.time.Instant.now() + " =====");
                failure.printStackTrace(pw);
            } finally {
                pw.close();
            }
        } catch (Exception ignored) {
            // Logging is best-effort; never propagate.
        }
    }

    // -------------------------------------------------------------------------
    // Path resolution for the repositories
    // -------------------------------------------------------------------------

    /**
     * Optional override pinning the repository root used by
     * {@link #authoringDataDir()}. Useful when the game is launched from a
     * working directory that is not the project root.
     */
    public static final String AUTHORING_ROOT_SYSTEM_PROPERTY = "jjktbf.authoring.root";

    /**
     * {@code true} when authoring mode is active
     * ({@code -Djjktbf.authoring=true}). In this mode the game reads and writes
     * the <em>source</em> game-data files &mdash; the ones bundled into releases
     * &mdash; instead of the per-user copies, so edits saved from the developer's
     * main build become the shipped defaults once committed and released.
     * Released builds never set this flag, so players keep the per-user behavior.
     */
    public static boolean isAuthoringMode() {
        return Boolean.getBoolean(AUTHORING_SYSTEM_PROPERTY);
    }

    /**
     * Locate the source {@code data} directory (the tracked catalog files that
     * get bundled into releases) for use in authoring mode. Detection starts
     * from the current working directory (or the path pinned by
     * {@code -Djjktbf.authoring.root}) and walks up looking for a directory
     * whose {@code data/} folder contains the bundled move and character
     * catalogs.
     *
     * @return the source {@code data} directory, or {@code null} if it could
     *         not be located (callers fall back to the per-user directory)
     */
    public static Path authoringDataDir() {
        String configured = System.getProperty(AUTHORING_ROOT_SYSTEM_PROPERTY);
        Path start = (configured != null && !configured.isBlank())
            ? Paths.get(configured.trim()).toAbsolutePath().normalize()
            : Paths.get(".").toAbsolutePath().normalize();
        Path candidate = start;
        for (int i = 0; i < 16 && candidate != null; i++) {
            Path data = candidate.resolve("data");
            if (isSourceDataDir(data)) return data;
            candidate = candidate.getParent();
        }
        return null;
    }

    private static boolean isSourceDataDir(Path data) {
        return Files.isDirectory(data)
            && Files.isRegularFile(data.resolve("moves/all_moves.json"))
            && Files.isRegularFile(data.resolve("characters/all_characters.json"));
    }

    /**
     * Resolve the repository-relative data directory (e.g. {@code data/characters})
     * onto the per-user data directory, returning the absolute path as a
     * {@link java.io.File} for the existing repository code.
     *
     * <p>In authoring mode the location is redirected to the tracked source
     * {@code data/} directory (see {@link #authoringDataDir()}) so the
     * repositories edit the files that ship in releases.
     *
     * <p>Callers keep passing the same relative strings they always did; this
     * method transparently relocates them to a stable, writable location.
     */
    public static java.io.File resolve(String relativeDataDir) {
        if (relativeDataDir == null || relativeDataDir.isBlank()) {
            return dataDir().toFile();
        }
        // The repositories pass paths like "data/characters". Drop a leading
        // "data/" so it maps under <root>/data/characters rather than
        // <root>/data/data/characters.
        String rel = relativeDataDir.replace('\\', '/');
        String sub = rel;
        if (rel.equals("data") || rel.startsWith("data/")) {
            sub = rel.substring("data".length());
            while (sub.startsWith("/")) sub = sub.substring(1);
        }
        Path target;
        if (isAuthoringMode()) {
            Path source = authoringDataDir();
            if (source != null) {
                target = sub.isEmpty() ? source : source.resolve(sub);
            } else {
                // Source dir not found: fall back to the per-user dir with a
                // warning rather than failing, so the game stays playable.
                System.err.println("Warning: authoring mode requested but the source data/ directory "
                    + "could not be located from " + Paths.get(".").toAbsolutePath()
                    + "; using the per-user data directory. Pin it with -D"
                    + AUTHORING_ROOT_SYSTEM_PROPERTY + "=/path/to/repo.");
                target = sub.isEmpty() ? dataDir() : dataDir().resolve(sub);
            }
        } else {
            target = sub.isEmpty() ? dataDir() : dataDir().resolve(sub);
        }
        try {
            Files.createDirectories(target);
        } catch (IOException ignored) {
            // Repository load()/save() will surface a clearer error on failure.
        }
        return target.toFile();
    }

    // -------------------------------------------------------------------------
    // First-run seeding from the classpath
    // -------------------------------------------------------------------------

    /**
     * Seed missing data files on first launch. When the bundled game version
     * changes, replace every editable game-data catalog with the new release.
     * The recorded version is updated only after all catalogs have been copied.
     *
     * <p>In authoring mode this is a no-op: the repositories edit the source
     * catalog files directly, and overwriting them with the (possibly stale)
     * bundled copies would discard uncommitted edits and create a feedback loop.
     */
    public static void seedDataIfAbsent() {
        if (isAuthoringMode()) return;
        ClassLoader cl = loader();
        try {
            String bundledVersion = bundledGameVersion(cl);
            if (bundledVersion.equals(savedDataVersion())) {
                copyBundledData(cl, false);
            } else {
                copyBundledData(cl, true);
                Files.writeString(root().resolve(DATA_VERSION_FILE), bundledVersion);
            }
        } catch (IOException e) {
            writeSeedError("bundled game data", e);
        }
    }

    private static String bundledGameVersion(ClassLoader cl) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = cl.getResourceAsStream(BUNDLED_VERSION)) {
            if (in == null) {
                throw new IOException("Missing bundled game version resource");
            }
            properties.load(in);
        }
        String version = properties.getProperty("game.version");
        if (version == null || version.isBlank() || version.contains("${")) {
            throw new IOException("Invalid bundled game version");
        }
        return version.trim();
    }

    private static String savedDataVersion() throws IOException {
        Path versionFile = root().resolve(DATA_VERSION_FILE);
        if (!Files.isRegularFile(versionFile)) return "";
        return Files.readString(versionFile).trim();
    }

    private static void copyBundledData(ClassLoader cl, boolean replaceExisting) throws IOException {
        List<StagedDataFile> staged = new ArrayList<>();
        try {
            for (String resource : BUNDLED_DATA_FILES) {
                Path destination = dataDir().resolve(resource.substring("data/".length()));
                if (!replaceExisting && Files.exists(destination)) continue;
                Files.createDirectories(destination.getParent());
                Path temporary = Files.createTempFile(destination.getParent(), ".seed-", ".tmp");
                staged.add(new StagedDataFile(temporary, destination));
                try (InputStream in = cl.getResourceAsStream(resource);
                     OutputStream out = Files.newOutputStream(temporary)) {
                    if (in == null) throw new IOException("Missing bundled data resource: " + resource);
                    in.transferTo(out);
                }
            }
            for (StagedDataFile file : staged) {
                try {
                    Files.move(file.temporary(), file.destination(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                    Files.move(file.temporary(), file.destination(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } finally {
            for (StagedDataFile file : staged) Files.deleteIfExists(file.temporary());
        }
    }

    private record StagedDataFile(Path temporary, Path destination) {}

    /**
     * Refresh bundled moves by name so release balance changes replace stale
     * local definitions. Player-created moves without a bundled name remain.
     * Basic Strike and Basic Block are additionally migrated to their mandated
     * free-move status.
     *
     * @return true when at least one bundled move was refreshed or appended
     */
    static boolean mergeBundledMoves(Path destination, InputStream bundledMoves) throws IOException {
        boolean refreshed = mergeBundledDefinitions(destination, bundledMoves,
            AppPaths::replaceBundledDefinition);
        return markBaselineMovesFree(destination) || refreshed;
    }

    /**
     * Refresh bundled characters by name so release stats, loadouts, and other
     * authored fields replace stale local definitions. Player-created characters
     * without a bundled name remain.
     *
     * @return true when saved character data was migrated, refreshed, or appended
     */
    static boolean mergeBundledCharacters(Path destination, InputStream bundledCharacters) throws IOException {
        return mergeBundledCharacters(destination, bundledCharacters, Map.of(), Map.of());
    }

    static boolean mergeBundledCharacters(
        Path destination,
        InputStream bundledCharacters,
        Map<String, String> moveIdMappings,
        Map<String, String> abilityIdMappings
    ) throws IOException {
        boolean migrated = migrateLegacyCharacterSpritePaths(destination);
        boolean merged = mergeBundledDefinitions(destination, bundledCharacters,
            (saved, bundled) -> replaceBundledCharacterDefinition(
                saved, bundled, moveIdMappings, abilityIdMappings));
        return migrated || merged;
    }

    /** Update superseded sprite paths used by shipped character data. */
    private static boolean migrateLegacyCharacterSpritePaths(Path destination) throws IOException {
        List<LinkedHashMap<String, Object>> saved = MAPPER.readValue(
            destination.toFile(), new TypeReference<>() {});
        boolean changed = false;
        for (Map<String, Object> character : saved) {
            Object spriteAsset = character.get("spriteAsset");
            if (spriteAsset instanceof String path) {
                String migrated = migrateLegacyCharacterSpritePath(path);
                if (migrated.equals(path)) continue;
                character.put("spriteAsset", migrated);
                changed = true;
            }
        }
        if (changed) MAPPER.writeValue(destination.toFile(), saved);
        return changed;
    }

    private static String migrateLegacyCharacterSpritePath(String path) {
        String normalized = path.replace('\\', '/');
        String replacement = LEGACY_DEFAULT_CHARACTER_SPRITES.get(normalized);
        if (replacement != null) return replacement;
        if (normalized.startsWith(LEGACY_CHARACTER_SPRITE_PREFIX)) {
            return CHARACTER_SPRITE_PREFIX
                + normalized.substring(LEGACY_CHARACTER_SPRITE_PREFIX.length());
        }
        return path;
    }

    /**
     * Refresh bundled abilities by name so release changes replace stale local
     * definitions. Player-created abilities without a bundled name remain.
     *
     * @return true when at least one bundled ability was refreshed or appended
     */
    static boolean mergeBundledAbilities(Path destination, InputStream bundledAbilities) throws IOException {
        return mergeBundledDefinitions(destination, bundledAbilities,
            AppPaths::replaceBundledDefinition);
    }

    /**
     * Append default techniques that do not already exist in a player's saved list.
     * A matching bundled technique also refreshes its authored tree metadata.
     *
     * @return true when at least one bundled technique was appended or its tree changed
     */
    static boolean mergeBundledTechniques(Path destination, InputStream bundledTechniques) throws IOException {
        return mergeBundledTechniques(destination, bundledTechniques, Map.of(), Map.of());
    }

    static boolean mergeBundledTechniques(
        Path destination,
        InputStream bundledTechniques,
        Map<String, String> moveIdMappings,
        Map<String, String> abilityIdMappings
    ) throws IOException {
        return mergeBundledDefinitions(destination, bundledTechniques,
            (saved, bundled) -> mergeBundledTechniqueTreeState(
                saved, bundled, moveIdMappings, abilityIdMappings));
    }

    @FunctionalInterface
    private interface DefinitionStateMerger {
        boolean merge(
            LinkedHashMap<String, Object> saved,
            LinkedHashMap<String, Object> bundled
        );
    }

    private static boolean mergeBundledDefinitions(Path destination, InputStream bundledDefinitions)
        throws IOException {
        return mergeBundledDefinitions(destination, bundledDefinitions, null);
    }

    private static boolean mergeBundledDefinitions(
        Path destination,
        InputStream bundledDefinitions,
        DefinitionStateMerger stateMerger
    ) throws IOException {
        List<LinkedHashMap<String, Object>> saved = MAPPER.readValue(
            destination.toFile(), new TypeReference<>() {});
        List<LinkedHashMap<String, Object>> bundled = MAPPER.readValue(
            bundledDefinitions, new TypeReference<>() {});

        Map<String, LinkedHashMap<String, Object>> savedByName = new LinkedHashMap<>();
        for (LinkedHashMap<String, Object> definition : saved) {
            String name = normalizedDefinitionName(definition);
            if (name != null) savedByName.putIfAbsent(name, definition);
        }

        boolean changed = false;
        Set<String> processedBundledNames = new HashSet<>();
        for (LinkedHashMap<String, Object> definition : bundled) {
            String name = normalizedDefinitionName(definition);
            if (name == null || !processedBundledNames.add(name)) continue;

            LinkedHashMap<String, Object> target = savedByName.get(name);
            if (target == null) {
                target = new LinkedHashMap<>(definition);
                target.put("id", String.format("%06d", saved.size()));
                saved.add(target);
                savedByName.put(name, target);
                changed = true;
            }
            if (stateMerger != null && stateMerger.merge(target, definition)) {
                changed = true;
            }
        }

        if (changed) MAPPER.writeValue(destination.toFile(), saved);
        return changed;
    }

    private static Map<String, String> bundledToSavedIdMappings(ClassLoader cl, String resource) {
        Path destination = dataDir().resolve(resource.substring("data/".length()));
        if (!Files.isRegularFile(destination)) return Map.of();
        try (InputStream input = cl.getResourceAsStream(resource)) {
            if (input == null) return Map.of();
            List<LinkedHashMap<String, Object>> saved = MAPPER.readValue(
                destination.toFile(), new TypeReference<>() {});
            List<LinkedHashMap<String, Object>> bundled = MAPPER.readValue(
                input, new TypeReference<>() {});
            Map<String, String> savedIdsByName = new LinkedHashMap<>();
            for (int index = 0; index < saved.size(); index++) {
                Map<String, Object> definition = saved.get(index);
                String name = normalizedDefinitionName(definition);
                if (name != null) {
                    savedIdsByName.putIfAbsent(name, String.format("%06d", index));
                }
            }

            Map<String, String> mappings = new LinkedHashMap<>();
            for (int index = 0; index < bundled.size(); index++) {
                Map<String, Object> definition = bundled.get(index);
                String sourceId = identifierOf(definition);
                String targetId = savedIdsByName.get(normalizedDefinitionName(definition));
                if (targetId == null) continue;
                mappings.put(String.format("%06d", index), targetId);
                if (sourceId != null) mappings.put(sourceId, targetId);
            }
            return mappings;
        } catch (IOException e) {
            writeSeedError(resource + " ID mapping", e);
            return Map.of();
        }
    }

    private static boolean mergeBundledTechniqueTreeState(
        LinkedHashMap<String, Object> saved,
        LinkedHashMap<String, Object> bundled,
        Map<String, String> moveIdMappings,
        Map<String, String> abilityIdMappings
    ) {
        if (!bundled.containsKey("skillTree")) return false;
        return replaceField(saved, "skillTree", remapTechniqueTree(
            bundled.get("skillTree"), moveIdMappings, abilityIdMappings));
    }

    private static boolean replaceBundledDefinition(
        LinkedHashMap<String, Object> saved,
        LinkedHashMap<String, Object> bundled
    ) {
        LinkedHashMap<String, Object> replacement = bundledDefinitionWithSavedId(saved, bundled);
        if (saved.equals(replacement)) return false;
        saved.clear();
        saved.putAll(replacement);
        return true;
    }

    private static boolean replaceBundledCharacterDefinition(
        LinkedHashMap<String, Object> saved,
        LinkedHashMap<String, Object> bundled,
        Map<String, String> moveIdMappings,
        Map<String, String> abilityIdMappings
    ) {
        LinkedHashMap<String, Object> replacement = bundledDefinitionWithSavedId(saved, bundled);
        remapIdentifierList(replacement, "moveIds", moveIdMappings);
        remapIdentifierList(replacement, "abilityIds", abilityIdMappings);
        remapIdentifierList(replacement, "availableAbilityIds", abilityIdMappings);
        if (saved.equals(replacement)) return false;
        saved.clear();
        saved.putAll(replacement);
        return true;
    }

    private static LinkedHashMap<String, Object> bundledDefinitionWithSavedId(
        Map<String, Object> saved,
        Map<String, Object> bundled
    ) {
        LinkedHashMap<String, Object> replacement = new LinkedHashMap<>();
        Object savedId = saved.get("id");
        for (Map.Entry<String, Object> entry : bundled.entrySet()) {
            replacement.put(entry.getKey(), "id".equals(entry.getKey()) && savedId != null
                ? deepCopyJsonValue(savedId)
                : deepCopyJsonValue(entry.getValue()));
        }
        if (!replacement.containsKey("id") && savedId != null) {
            replacement.put("id", deepCopyJsonValue(savedId));
        }
        return replacement;
    }

    private static void remapIdentifierList(
        Map<String, Object> definition,
        String field,
        Map<String, String> idMappings
    ) {
        if (!definition.containsKey(field)) return;
        definition.put(field, remapIdentifierList(definition.get(field), idMappings));
    }

    private static Object remapTechniqueTree(
        Object tree,
        Map<String, String> moveIdMappings,
        Map<String, String> abilityIdMappings
    ) {
        Object copy = deepCopyJsonValue(tree);
        if (!(copy instanceof List<?> nodes)) return copy;
        Map<String, String> moves = moveIdMappings == null ? Map.of() : moveIdMappings;
        Map<String, String> abilities = abilityIdMappings == null ? Map.of() : abilityIdMappings;
        for (Object candidate : nodes) {
            if (!(candidate instanceof Map<?, ?>)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> node = (Map<String, Object>) candidate;
            Object type = node.get("contentType");
            Object contentId = node.get("contentId");
            if (!(type instanceof String contentType) || !(contentId instanceof String sourceId)) continue;
            Map<String, String> mappings = "MOVE".equalsIgnoreCase(contentType)
                ? moves : "ABILITY".equalsIgnoreCase(contentType) ? abilities : Map.of();
            String mappedId = mappings.get(sourceId);
            if (mappedId != null) node.put("contentId", mappedId);
        }
        return copy;
    }

    private static Object remapIdentifierList(Object value, Map<String, String> idMappings) {
        if (!(value instanceof List<?> values)) return deepCopyJsonValue(value);
        Map<String, String> mappings = idMappings == null ? Map.of() : idMappings;
        List<Object> remapped = new ArrayList<>(values.size());
        for (Object entry : values) {
            if (entry instanceof String id && mappings.containsKey(id)) {
                remapped.add(mappings.get(id));
            } else {
                remapped.add(deepCopyJsonValue(entry));
            }
        }
        return remapped;
    }

    private static Object deepCopyJsonValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), deepCopyJsonValue(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof List<?> values) {
            List<Object> copy = new ArrayList<>(values.size());
            for (Object entry : values) copy.add(deepCopyJsonValue(entry));
            return copy;
        }
        return value;
    }

    private static boolean replaceField(Map<String, Object> target, String field, Object value) {
        if (value == null) {
            if (!target.containsKey(field)) return false;
            target.remove(field);
            return true;
        }
        if (target.containsKey(field) && Objects.equals(target.get(field), value)) return false;
        target.put(field, value);
        return true;
    }

    /** Keep the two guaranteed baseline moves free in older player move data. */
    private static boolean markBaselineMovesFree(Path destination) throws IOException {
        List<LinkedHashMap<String, Object>> saved = MAPPER.readValue(
            destination.toFile(), new TypeReference<>() {});
        boolean changed = false;
        for (Map<String, Object> move : saved) {
            String name = normalizedDefinitionName(move);
            if (!"basic strike".equals(name) && !"basic block".equals(name)) continue;
            if (!Boolean.TRUE.equals(move.get("isFreeMove"))) {
                move.put("isFreeMove", true);
                changed = true;
            }
        }
        if (changed) MAPPER.writeValue(destination.toFile(), saved);
        return changed;
    }

    private static String normalizedDefinitionName(Map<String, Object> definition) {
        Object name = definition.get("name");
        if (!(name instanceof String text) || text.isBlank()) return null;
        return text.trim().toLowerCase(Locale.ROOT);
    }

    private static String identifierOf(Map<String, Object> definition) {
        Object id = definition.get("id");
        if (!(id instanceof String text) || text.isBlank()) return null;
        return text;
    }

    /**
     * If seeding a data file fails, record the reason next to the data dir so a
     * player can recover without needing console output. Best-effort only.
     */
    private static void writeSeedError(String resource, IOException cause) {
        try {
            Path err = root().resolve("seed_error.log");
            String msg = java.time.Instant.now()
                + "  could not seed " + resource + " : " + cause + System.lineSeparator();
            Files.writeString(err, msg,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Nothing more we can do.
        }
    }

    /**
     * Recursively delete a directory tree. Intended for tests and manual reset
     * only; production code must never wipe the per-user data dir.
     */
    public static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walk(dir)
             .sorted(Comparator.reverseOrder())
             .forEach(p -> {
                 try { Files.deleteIfExists(p); } catch (IOException ignored) {}
             });
    }

    private static ClassLoader loader() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        return cl != null ? cl : AppPaths.class.getClassLoader();
    }
}
