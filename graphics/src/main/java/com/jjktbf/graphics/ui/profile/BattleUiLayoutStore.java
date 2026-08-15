package com.jjktbf.graphics.ui.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.jjktbf.AppPaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Loads shipped profile layouts and atomically saves authoring edits to source. */
public final class BattleUiLayoutStore {

    private static final String RESOURCE_DIRECTORY = "assets/ui/battle-layouts";
    private static final String SOURCE_DIRECTORY =
        "graphics/src/main/resources/" + RESOURCE_DIRECTORY;

    private final ObjectMapper mapper = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);
    private final ClassLoader classLoader;
    private final Path sourceRoot;

    public BattleUiLayoutStore() {
        this(resolveAuthoringSourceRoot(), contextClassLoader());
    }

    BattleUiLayoutStore(Path sourceRoot, ClassLoader classLoader) {
        this.sourceRoot = sourceRoot;
        this.classLoader = classLoader;
    }

    public BattleUiLayout load(UiProfile profile) throws IOException {
        Path source = sourcePath(profile);
        BattleUiLayout layout;
        if (source != null && Files.isRegularFile(source)) {
            layout = mapper.readValue(source.toFile(), BattleUiLayout.class);
        } else {
            String resource = resourcePath(profile);
            try (InputStream input = classLoader.getResourceAsStream(resource)) {
                if (input == null) {
                    throw new IOException("Missing bundled battle UI layout " + resource);
                }
                layout = mapper.readValue(input, BattleUiLayout.class);
            }
        }
        layout.validate(profile);
        return layout;
    }

    public Path save(UiProfile profile, BattleUiLayout layout) throws IOException {
        if (sourceRoot == null) {
            throw new IOException(
                "Battle UI layouts can only be saved from an authoring source checkout");
        }
        layout.validate(profile);
        Path target = sourcePath(profile);
        Files.createDirectories(target.getParent());
        byte[] json = mapper.writeValueAsBytes(layout);
        Path temporary = Files.createTempFile(target.getParent(), ".battle-ui-", ".tmp");
        boolean moved = false;
        try {
            try (FileChannel channel = FileChannel.open(
                temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(json);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(temporary, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
            return target;
        } finally {
            if (!moved) Files.deleteIfExists(temporary);
        }
    }

    public Path sourcePath(UiProfile profile) {
        return sourceRoot == null
            ? null
            : sourceRoot.resolve(SOURCE_DIRECTORY).resolve(profile.fileStem() + ".json");
    }

    private static String resourcePath(UiProfile profile) {
        return RESOURCE_DIRECTORY + "/" + profile.fileStem() + ".json";
    }

    private static Path resolveAuthoringSourceRoot() {
        if (!AppPaths.isAuthoringMode()) return null;
        Path data = AppPaths.authoringDataDir();
        if (data == null) return null;
        Path root = data.getParent();
        return Files.isDirectory(root.resolve(SOURCE_DIRECTORY)) ? root : null;
    }

    private static ClassLoader contextClassLoader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader == null ? BattleUiLayoutStore.class.getClassLoader() : loader;
    }
}
