package com.jjktbf.graphics.multiplayer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjktbf.AppPaths;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.Set;

/** Atomic private-file persistence for the desktop guest session. */
public final class GuestCredentialsStore {
    public static final String RELATIVE_PATH = "multiplayer/guest-session.json";

    private static final Set<java.nio.file.attribute.PosixFilePermission> DIRECTORY_PERMISSIONS =
        PosixFilePermissions.fromString("rwx------");
    private static final Set<java.nio.file.attribute.PosixFilePermission> FILE_PERMISSIONS =
        PosixFilePermissions.fromString("rw-------");

    private final Path file;
    private final ObjectMapper mapper;

    public GuestCredentialsStore() {
        this(AppPaths.root().resolve(RELATIVE_PATH));
    }

    public GuestCredentialsStore(Path file) {
        this(file, NetworkJson.newMapper());
    }

    public GuestCredentialsStore(Path file, ObjectMapper mapper) {
        this.file = java.util.Objects.requireNonNull(file, "file").toAbsolutePath();
        this.mapper = java.util.Objects.requireNonNull(mapper, "mapper");
    }

    public Path path() {
        return file;
    }

    public Optional<GuestCredentials> load() throws IOException {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            GuestCredentials credentials = mapper.readValue(
                Files.readAllBytes(file), GuestCredentials.class);
            return Optional.of(credentials);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IOException("Stored guest credentials are malformed", exception);
        }
    }

    public void save(GuestCredentials credentials) throws IOException {
        java.util.Objects.requireNonNull(credentials, "credentials");
        Path directory = file.getParent();
        if (directory == null) {
            throw new IOException("Guest credential path has no parent directory");
        }
        createPrivateDirectory(directory);

        boolean posix = supportsPosix(directory);
        FileAttribute<?>[] attributes = posix
            ? new FileAttribute<?>[] {
                PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS)
            }
            : new FileAttribute<?>[0];
        Path temporary = Files.createTempFile(
            directory, ".guest-session-", ".tmp", attributes);
        boolean moved = false;
        try {
            byte[] json = mapper.writeValueAsBytes(credentials);
            OpenOption[] options = {
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
            };
            try (FileChannel channel = FileChannel.open(temporary, options)) {
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(json);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            setPrivateFilePermissions(temporary, posix);
            try {
                Files.move(
                    temporary,
                    file,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
            setPrivateFilePermissions(file, posix);
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    public void clear() throws IOException {
        Files.deleteIfExists(file);
    }

    private static void createPrivateDirectory(Path directory) throws IOException {
        Files.createDirectories(directory);
        if (supportsPosix(directory)) {
            Files.setPosixFilePermissions(directory, DIRECTORY_PERMISSIONS);
        }
    }

    private static void setPrivateFilePermissions(Path path, boolean posix) throws IOException {
        if (posix) {
            Files.setPosixFilePermissions(path, FILE_PERMISSIONS);
        }
    }

    private static boolean supportsPosix(Path path) {
        try {
            return Files.getFileStore(path).supportsFileAttributeView("posix");
        } catch (IOException | UnsupportedOperationException exception) {
            return false;
        }
    }
}
