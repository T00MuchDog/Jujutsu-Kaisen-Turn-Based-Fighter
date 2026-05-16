package com.jjktbf.graphics;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;

/**
 * Desktop entry point for the graphics mode.
 *
 * Configures the LibGDX LWJGL3 window and launches JJKGame.
 *
 * To run:
 *   mvn package -pl graphics
 *   java -jar graphics/target/graphics-1.0-SNAPSHOT.jar
 *
 * Or directly from your IDE by running this class's main() method.
 */
public class GraphicsMain {

    public static void main(String[] args) {
        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();

        config.setTitle("Jujutsu Kaisen — Turn Based Fighter");
        config.setWindowedMode(1024, 600);
        config.setResizable(true);
        config.setForegroundFPS(60);
        config.useVsync(true);

        // macOS: LibGDX requires the render loop to run on the main thread.
        // This is handled automatically by Lwjgl3Application on macOS.
        new Lwjgl3Application(new JJKGame(), config);
    }
}
