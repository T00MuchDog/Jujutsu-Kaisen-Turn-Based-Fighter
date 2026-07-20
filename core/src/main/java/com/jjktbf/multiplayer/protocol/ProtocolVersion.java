package com.jjktbf.multiplayer.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Central compatibility values shared by multiplayer clients and servers. */
public final class ProtocolVersion {

    public static final String GAME_VERSION = loadGameVersion();
    public static final int PROTOCOL_VERSION = 3;
    public static final String STANDARD_RULESET = "STANDARD";

    private ProtocolVersion() {
    }

    private static String loadGameVersion() {
        Properties properties = new Properties();
        try (InputStream input = ProtocolVersion.class.getResourceAsStream(
            "/jjktbf-version.properties")) {
            if (input == null) {
                throw new IllegalStateException("Missing multiplayer version resource");
            }
            properties.load(input);
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
        String version = properties.getProperty("game.version");
        if (version == null || version.isBlank() || version.contains("${")) {
            throw new IllegalStateException("Invalid multiplayer game version resource");
        }
        return version.trim();
    }

    /** Returns whether all compatibility values identify this protocol revision. */
    public static boolean isCompatible(String gameVersion, int protocolVersion, String ruleset) {
        return GAME_VERSION.equals(gameVersion)
            && PROTOCOL_VERSION == protocolVersion
            && STANDARD_RULESET.equals(ruleset);
    }
}
