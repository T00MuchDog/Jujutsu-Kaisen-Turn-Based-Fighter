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
        // Capture any thread's uncaught exception to a file so crashes (which
        // often die silently or show a native dialog that hides the trace) are
        // recoverable. Diagnostic.
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                java.io.PrintWriter pw = new java.io.PrintWriter(
                    new java.io.FileWriter("battle_crash.log", true));
                pw.println("===== " + java.time.Instant.now()
                           + "  (thread: " + t.getName() + ") =====");
                e.printStackTrace(pw);
                pw.close();
            } catch (Exception ignored) {}
        });

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
