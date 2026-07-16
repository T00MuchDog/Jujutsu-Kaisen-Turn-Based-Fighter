package com.jjktbf.graphics.multiplayer;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClientNetworkConfigTest {
    @Test
    void loadsLocalDefaults() {
        ClientNetworkConfig config = ClientNetworkConfig.from(
            new Properties(), Map.of());

        assertEquals(ClientNetworkConfig.DEFAULT_HTTP_URL,
            config.httpBaseUri().toString());
        assertEquals(ClientNetworkConfig.DEFAULT_WEBSOCKET_URL,
            config.webSocketUri().toString());
    }

    @Test
    void systemPropertiesOverrideEnvironmentAndTrailingSlashesAreRemoved() {
        Properties properties = new Properties();
        properties.setProperty(
            ClientNetworkConfig.HTTP_SYSTEM_PROPERTY, "https://game.example/api/");
        properties.setProperty(
            ClientNetworkConfig.WEBSOCKET_SYSTEM_PROPERTY,
            "wss://game.example/socket/");

        ClientNetworkConfig config = ClientNetworkConfig.from(properties, Map.of(
            ClientNetworkConfig.HTTP_ENVIRONMENT_VARIABLE, "http://environment.example",
            ClientNetworkConfig.WEBSOCKET_ENVIRONMENT_VARIABLE, "ws://environment.example/ws"
        ));

        assertEquals("https://game.example/api", config.httpBaseUri().toString());
        assertEquals("wss://game.example/socket", config.webSocketUri().toString());
    }

    @Test
    void environmentOverridesDefaults() {
        ClientNetworkConfig config = ClientNetworkConfig.from(new Properties(), Map.of(
            ClientNetworkConfig.HTTP_ENVIRONMENT_VARIABLE, "https://game.example",
            ClientNetworkConfig.WEBSOCKET_ENVIRONMENT_VARIABLE,
            "wss://game.example/ws/matches"
        ));

        assertEquals("https://game.example", config.httpBaseUri().toString());
        assertEquals("wss://game.example/ws/matches", config.webSocketUri().toString());
    }

    @Test
    void rejectsWrongSchemesAndUnsafeBaseComponents() {
        assertThrows(IllegalArgumentException.class, () ->
            new ClientNetworkConfig(
                "ftp://game.example", "wss://game.example/ws/matches"));
        assertThrows(IllegalArgumentException.class, () ->
            new ClientNetworkConfig(
                "https://game.example", "https://game.example/ws/matches"));
        assertThrows(IllegalArgumentException.class, () ->
            new ClientNetworkConfig(
                "https://user:secret@game.example", "wss://game.example/ws"));
        assertThrows(IllegalArgumentException.class, () ->
            new ClientNetworkConfig(
                "https://game.example?token=secret", "wss://game.example/ws"));
    }

    @Test
    void reconnectBackoffIsBounded() {
        ClientNetworkConfig config = new ClientNetworkConfig(
            ClientNetworkConfig.DEFAULT_HTTP_URL,
            ClientNetworkConfig.DEFAULT_WEBSOCKET_URL
        );

        assertEquals(Duration.ofSeconds(1), config.reconnectDelay(1));
        assertEquals(Duration.ofSeconds(2), config.reconnectDelay(2));
        assertEquals(Duration.ofSeconds(4), config.reconnectDelay(3));
        assertEquals(Duration.ofSeconds(8), config.reconnectDelay(4));
        assertEquals(Duration.ofSeconds(8), config.reconnectDelay(5));
        assertThrows(IllegalArgumentException.class, () -> config.reconnectDelay(0));
        assertThrows(IllegalArgumentException.class, () -> config.reconnectDelay(6));
    }
}
