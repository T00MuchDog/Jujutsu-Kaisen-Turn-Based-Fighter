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
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
 * under {@code data/...}) is copied into the per-user data directory. Existing
 * files are never overwritten wholesale. Missing bundled move, character,
 * ability, and technique definitions are appended by name on later launches.
 * Authored technique trees and the matching characters' technique state are
 * refreshed selectively so release tree changes remain available without
 * replacing unrelated player edits.
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

    /** Filesystem-safe folder name used under the per-user data root. */
    private static final String APP_DIR_NAME = "JujutsuKaisenFighter";

    /** Classpath prefix for the bundled default game data. */
    private static final String BUNDLED_DATA_PREFIX = "data";

    private static final String BUNDLED_MOVES = "data/moves/all_moves.json";
    private static final String BUNDLED_CHARACTERS = "data/characters/all_characters.json";
    private static final String BUNDLED_ABILITIES = "data/abilities/all_abilities.json";
    private static final String BUNDLED_TECHNIQUES = "data/techniques/all_techniques.json";
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
     * Map a caller-supplied relative data directory (e.g. {@code "data/characters"})
     * onto the per-user data directory, returning the absolute path as a
     * {@link java.io.File} for the existing repository code.
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
        Path resolved = sub.isEmpty() ? dataDir() : dataDir().resolve(sub);
        try {
            Files.createDirectories(resolved);
        } catch (IOException ignored) {
            // Repository load()/save() will surface a clearer error on failure.
        }
        return resolved.toFile();
    }

    // -------------------------------------------------------------------------
    // First-run seeding from the classpath
    // -------------------------------------------------------------------------

    /**
     * Copy the bundled default game-data JSON (classpath {@code data/...}) into
     * the per-user data directory. Existing records keep their player-owned
     * fields; bundled technique trees and the matching character technique
     * state are refreshed on upgrade.
     *
     * <p>This mirrors the bundled files onto disk using the same classpath
     * layout (e.g. {@code data/characters/all_characters.json}) by walking the
     * known data subdirectories and files. Only the JSON shipped under the
     * classpath {@code data} root is copied.
     */
    public static void seedDataIfAbsent() {
        ClassLoader cl = loader();
        // Moves and abilities must be seeded first: user repositories can have
        // different positional IDs, so later tree and character state is mapped
        // back to the local IDs by content name.
        seedOneSafely(cl, BUNDLED_MOVES, Map.of(), Map.of());
        seedOneSafely(cl, BUNDLED_ABILITIES, Map.of(), Map.of());

        Map<String, String> moveIdMappings = bundledToSavedIdMappings(cl, BUNDLED_MOVES);
        Map<String, String> abilityIdMappings = bundledToSavedIdMappings(cl, BUNDLED_ABILITIES);
        seedOneSafely(cl, BUNDLED_TECHNIQUES, moveIdMappings, abilityIdMappings);
        seedOneSafely(cl, BUNDLED_CHARACTERS, moveIdMappings, abilityIdMappings);
    }

    private static void seedOneSafely(
        ClassLoader cl,
        String resource,
        Map<String, String> moveIdMappings,
        Map<String, String> abilityIdMappings
    ) {
        try {
            seedOne(cl, resource, moveIdMappings, abilityIdMappings);
        } catch (IOException e) {
            writeSeedError(resource, e);
        }
    }

    private static void seedOne(
        ClassLoader cl,
        String resource,
        Map<String, String> moveIdMappings,
        Map<String, String> abilityIdMappings
    ) throws IOException {
        Path dest = dataDir().resolve(resource.substring("data/".length()));
        try (InputStream in = cl.getResourceAsStream(resource)) {
            if (Files.exists(dest)) {
                if (BUNDLED_MOVES.equals(resource) && in != null) {
                    mergeBundledMoves(dest, in);
                } else if (BUNDLED_CHARACTERS.equals(resource) && in != null) {
                    mergeBundledCharacters(dest, in, moveIdMappings, abilityIdMappings);
                } else if (BUNDLED_ABILITIES.equals(resource) && in != null) {
                    mergeBundledAbilities(dest, in);
                } else if (BUNDLED_TECHNIQUES.equals(resource) && in != null) {
                    mergeBundledTechniques(dest, in, moveIdMappings, abilityIdMappings);
                }
                return;
            }
            if (in == null) {
                // The bundled data is missing from this build; the repository
                // will fall back to its built-in seed. Not fatal.
                return;
            }
            Files.createDirectories(dest.getParent());
            if (BUNDLED_TECHNIQUES.equals(resource) || BUNDLED_CHARACTERS.equals(resource)) {
                // A partial profile can already contain moves/abilities with
                // locally resequenced IDs. Start with an empty catalog so the
                // normal merge path can remap its technique references.
                MAPPER.writeValue(dest.toFile(), List.of());
                if (BUNDLED_TECHNIQUES.equals(resource)) {
                    mergeBundledTechniques(dest, in, moveIdMappings, abilityIdMappings);
                } else {
                    mergeBundledCharacters(dest, in, moveIdMappings, abilityIdMappings);
                }
            } else {
                try (OutputStream out = Files.newOutputStream(dest)) {
                    in.transferTo(out);
                }
            }
        }
    }

    /**
     * Append default moves that do not already exist in a player's saved list.
     * Name matching is case-insensitive so player-edited moves are preserved.
     * Basic Strike and Basic Block are additionally migrated to their mandated
     * free-move status.
     *
     * @return true when at least one bundled move was appended
     */
    static boolean mergeBundledMoves(Path destination, InputStream bundledMoves) throws IOException {
        boolean appended = mergeBundledDefinitions(destination, bundledMoves);
        return markBaselineMovesFree(destination) || appended;
    }

    /**
     * Append default characters that do not already exist in a player's saved list.
     * Matching bundled technique ownership and node selections are refreshed while
     * unrelated character fields remain player-owned.
     *
     * @return true when saved character data was migrated or a bundled character was appended
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
            (saved, bundled) -> mergeBundledCharacterTechniqueState(
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
     * Append default abilities that do not already exist in a player's saved list.
     * Name matching is case-insensitive so player-edited abilities are preserved.
     *
     * @return true when at least one bundled ability was appended
     */
    static boolean mergeBundledAbilities(Path destination, InputStream bundledAbilities) throws IOException {
        return mergeBundledDefinitions(destination, bundledAbilities);
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

    private static boolean mergeBundledCharacterTechniqueState(
        LinkedHashMap<String, Object> saved,
        LinkedHashMap<String, Object> bundled,
        Map<String, String> moveIdMappings,
        Map<String, String> abilityIdMappings
    ) {
        boolean bundledHasTechniqueState = bundled.containsKey("innateTechniqueName")
            || bundled.containsKey("availableAbilityIds");
        if (!bundledHasTechniqueState && !hasCharacterTechniqueState(saved)) return false;

        boolean changed = false;
        if (bundled.containsKey("innateTechniqueName")) {
            changed |= replaceField(saved, "innateTechniqueName",
                deepCopyJsonValue(bundled.get("innateTechniqueName")));
        } else {
            changed |= replaceField(saved, "innateTechniqueName", null);
        }
        if (bundled.containsKey("cursedTechniqueMastery")) {
            changed |= replaceField(saved, "cursedTechniqueMastery",
                deepCopyJsonValue(bundled.get("cursedTechniqueMastery")));
        }
        changed |= replaceMappedIdentifierListIfPresent(
            saved, bundled, "moveIds", moveIdMappings);
        changed |= replaceMappedIdentifierListIfPresent(
            saved, bundled, "abilityIds", abilityIdMappings);
        if (bundled.containsKey("availableAbilityIds")) {
            changed |= replaceMappedIdentifierListIfPresent(
                saved, bundled, "availableAbilityIds", abilityIdMappings);
        } else {
            changed |= replaceField(saved, "availableAbilityIds", null);
        }
        return changed;
    }

    private static boolean hasCharacterTechniqueState(Map<String, Object> character) {
        Object technique = character.get("innateTechniqueName");
        if (technique instanceof String name && !name.isBlank()) return true;
        return character.get("availableAbilityIds") instanceof List<?> ids && !ids.isEmpty();
    }

    private static boolean replaceMappedIdentifierListIfPresent(
        Map<String, Object> saved,
        Map<String, Object> bundled,
        String field,
        Map<String, String> idMappings
    ) {
        if (!bundled.containsKey(field)) return false;
        return replaceField(saved, field,
            remapIdentifierList(bundled.get(field), idMappings));
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
