package com.jjktbf.graphics;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.jjktbf.AppPaths;

/**
 * Desktop entry point for the graphics mode.
 *
 * Configures the LibGDX LWJGL3 window and launches JJKGame.
 *
 * To run from source (always authoring mode — run from the repo root):
 *   mvn -Drevision=1.2.12 -pl core,graphics -am clean verify
 *   java -XstartOnFirstThread -Djjktbf.authoring=true -jar graphics/target/graphics-1.2.12.jar   (macOS)
 *   java -Djjktbf.authoring=true -jar graphics/target/graphics-1.2.12.jar                        (Windows/Linux)
 *
 * The authoring flag makes editors save to the tracked source data/ files so
 * changes ship in the next release; a yellow AUTHORING badge in the top-right
 * corner confirms it is active. There is intentionally no separate player-mode
 * command from source — to play, run the downloaded release app, whose data
 * lives in the per-user folder separate from the repository.
 */
public class GraphicsMain {

    public static void main(String[] args) {
        // First-run / upgrade-safe seeding: copy bundled game-data JSON into the
        // per-user data directory. Editor data persists for a game version and
        // is replaced only after launching a newer release.
        // Must run before any repository is constructed (those read the files).
        try {
            AppPaths.seedDataIfAbsent();
        } catch (Throwable t) {
            // Seeding failure is non-fatal: repositories fall back to their
            // built-in seeds. Log so it is diagnosable.
            System.err.println("Warning: could not seed user data dir: " + t);
        }

        // Capture any thread's uncaught exception to a file so crashes (which
        // often die silently or show a native dialog that hides the trace) are
        // recoverable. Written to the per-user logs dir so it is reachable from
        // a packaged app regardless of working directory.
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                java.io.PrintWriter pw = new java.io.PrintWriter(
                    new java.io.FileWriter(AppPaths.logFile().toFile(), true));
                pw.println("===== " + java.time.Instant.now()
                           + "  (thread: " + t.getName() + ") =====");
                e.printStackTrace(pw);
                pw.close();
            } catch (Exception ignored) {}
        });

        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();

        config.setTitle(AppPaths.APP_NAME);
        config.setWindowedMode(1024, 600);
        config.setResizable(true);
        config.setForegroundFPS(60);
        config.useVsync(true);

        // macOS: LibGDX requires the render loop to run on the main thread.
        // This is handled automatically by Lwjgl3Application on macOS.
        new Lwjgl3Application(new JJKGame(), config);
    }
}
