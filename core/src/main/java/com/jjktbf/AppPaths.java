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
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * files are never overwritten. Missing bundled move, character, ability, and
 * technique definitions are appended by name on later launches, so player edits
 * survive upgrades while new default content becomes available.
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
     * the per-user data directory. Existing files are preserved, so this is
     * safe to call on every launch and never clobbers player data on upgrade.
     *
     * <p>This mirrors the bundled files onto disk using the same classpath
     * layout (e.g. {@code data/characters/all_characters.json}) by walking the
     * known data subdirectories and files. Only the JSON shipped under the
     * classpath {@code data} root is copied.
     */
    public static void seedDataIfAbsent() {
        ClassLoader cl = loader();
        // Known data files shipped on the classpath under data/ (see graphics
        // POM resources). Listed explicitly so seeding works without a live
        // filesystem walk of the jar, which is awkward to do portably.
        String[] bundled = {
            BUNDLED_CHARACTERS,
            BUNDLED_MOVES,
            BUNDLED_ABILITIES,
            BUNDLED_TECHNIQUES,
        };
        for (String resource : bundled) {
            try {
                seedOne(cl, resource);
            } catch (IOException e) {
                writeSeedError(resource, e);
            }
        }
    }

    private static void seedOne(ClassLoader cl, String resource) throws IOException {
        Path dest = dataDir().resolve(resource.substring("data/".length()));
        try (InputStream in = cl.getResourceAsStream(resource)) {
            if (Files.exists(dest)) {
                if (BUNDLED_MOVES.equals(resource) && in != null) {
                    mergeBundledMoves(dest, in);
                } else if (BUNDLED_CHARACTERS.equals(resource) && in != null) {
                    mergeBundledCharacters(dest, in);
                } else if (BUNDLED_ABILITIES.equals(resource) && in != null) {
                    mergeBundledAbilities(dest, in);
                } else if (BUNDLED_TECHNIQUES.equals(resource) && in != null) {
                    mergeBundledTechniques(dest, in);
                }
                return;
            }
            if (in == null) {
                // The bundled data is missing from this build; the repository
                // will fall back to its built-in seed. Not fatal.
                return;
            }
            Files.createDirectories(dest.getParent());
            try (OutputStream out = Files.newOutputStream(dest)) {
                in.transferTo(out);
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
     * Name matching is case-insensitive so player-edited characters are preserved.
     *
     * @return true when saved character data was migrated or a bundled character was appended
     */
    static boolean mergeBundledCharacters(Path destination, InputStream bundledCharacters) throws IOException {
        boolean migrated = migrateLegacyCharacterSpritePaths(destination);
        return mergeBundledDefinitions(destination, bundledCharacters) || migrated;
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
     * Name matching is case-insensitive so player-edited techniques are preserved.
     *
     * @return true when at least one bundled technique was appended
     */
    static boolean mergeBundledTechniques(Path destination, InputStream bundledTechniques) throws IOException {
        return mergeBundledDefinitions(destination, bundledTechniques);
    }

    private static boolean mergeBundledDefinitions(Path destination, InputStream bundledDefinitions) throws IOException {
        List<LinkedHashMap<String, Object>> saved = MAPPER.readValue(
            destination.toFile(), new TypeReference<>() {});
        List<LinkedHashMap<String, Object>> bundled = MAPPER.readValue(
            bundledDefinitions, new TypeReference<>() {});

        Set<String> savedNames = new HashSet<>();
        for (Map<String, Object> definition : saved) {
            String name = normalizedDefinitionName(definition);
            if (name != null) savedNames.add(name);
        }

        boolean changed = false;
        for (LinkedHashMap<String, Object> definition : bundled) {
            String name = normalizedDefinitionName(definition);
            if (name == null || !savedNames.add(name)) continue;

            LinkedHashMap<String, Object> copy = new LinkedHashMap<>(definition);
            copy.put("id", String.format("%06d", saved.size()));
            saved.add(copy);
            changed = true;
        }

        if (changed) MAPPER.writeValue(destination.toFile(), saved);
        return changed;
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
