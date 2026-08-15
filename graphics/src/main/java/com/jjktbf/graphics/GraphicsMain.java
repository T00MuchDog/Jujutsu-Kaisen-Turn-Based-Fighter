package com.jjktbf.graphics;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Graphics;
import com.jjktbf.AppPaths;
import com.jjktbf.graphics.launch.DesktopLaunchOptions;
import com.jjktbf.graphics.launch.DesktopPlatform;

/**
 * Desktop entry point for the graphics mode.
 *
 * Configures the LibGDX LWJGL3 window and launches JJKGame.
 *
 * Build from the repository root with:
 *   mvn -Drevision=1.4.1 -pl graphics -am package
 *
 * See README.md for profile-specific launch commands on macOS and Windows.
 * UI profile overrides are parsed centrally by DesktopLaunchOptions.
 */
public class GraphicsMain {

    public static void main(String[] args) {
        DesktopLaunchOptions launchOptions;
        try {
            launchOptions = DesktopLaunchOptions.parse(args);
        } catch (IllegalArgumentException invalidOptions) {
            System.err.println("Could not launch: " + invalidOptions.getMessage());
            return;
        }

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
        // Launch in fullscreen. The mechanism differs by OS because the
        // *native* fullscreen experience differs:
        //   - macOS: the "green traffic-light" fullscreen is a distinct native
        //     API (NSWindow -toggleFullScreen:). GLFW's exclusive fullscreen
        //     does NOT produce it — it just stretches a borderless window over
        //     the desktop. So on macOS we start as a normal decorated window
        //     and invoke the native toggle once the window exists. This is
        //     exactly what pressing the green button does: the app gets its
        //     own Space.
        //   - Windows/Linux: there is no "separate Space" concept; the
        //     standard fullscreen is exclusive fullscreen, which setFullscreen
        //     Mode(...) already does correctly at creation time.
        boolean mac = launchOptions.hostPlatform() == DesktopPlatform.MAC;
        boolean macNativeFullscreen = mac && !launchOptions.windowed();
        if (launchOptions.windowed()) {
            config.setWindowedMode(launchOptions.windowWidth(), launchOptions.windowHeight());
        } else if (!mac) {
            config.setFullscreenMode(Lwjgl3ApplicationConfiguration.getDisplayMode());
        } else {
            // Start windowed; the green-button toggle needs a real window.
            config.setWindowedMode(1024, 600);
        }
        // Cocoa native fullscreen requires a resizable NSWindow. Keep the old
        // normal-Mac behavior while still allowing explicit fixed policies later.
        config.setResizable(macNativeFullscreen || launchOptions.windowed());
        config.setForegroundFPS(60);
        config.useVsync(true);

        JJKGame game = new JJKGame(launchOptions);
        if (macNativeFullscreen) {
            // toggleFullScreen: must be called on the UI/render thread AFTER
            // the GLFW window exists. Hooking the end of create() and posting
            // a runnable defers the call to the next render frame, by which
            // point the window + GL context are live.
            game.setOnCreatedAction(() -> Gdx.app.postRunnable(
                GraphicsMain::enterMacNativeFullscreen));
        }

        // The launching JVM still requires -XstartOnFirstThread on macOS.
        new Lwjgl3Application(game, config);
    }

    /**
     * Toggle the window into macOS native fullscreen — the green-button
     * "separate Space" mode — by calling NSWindow -toggleFullScreen: via the
     * Objective-C runtime. Wrapped so any failure (non-mac build, missing
     * native binding, headless) is caught and logged rather than killing the
     * app; the game still runs windowed in that case.
     */
    private static void enterMacNativeFullscreen() {
        try {
            long windowHandle = ((Lwjgl3Graphics) Gdx.graphics)
                .getWindow().getWindowHandle();
            long cocoaWindow = org.lwjgl.glfw.GLFWNativeCocoa
                .glfwGetCocoaWindow(windowHandle);
            long toggleSelector = org.lwjgl.system.macosx.ObjCRuntime
                .sel_registerName("toggleFullScreen:");
            long objcMsgSend = org.lwjgl.system.macosx.ObjCRuntime
                .getLibrary().getFunctionAddress("objc_msgSend");
            // objc_msgSend(cocoaWindow, "toggleFullScreen:", nil)
            org.lwjgl.system.JNI.invokePPV(
                cocoaWindow, toggleSelector, org.lwjgl.system.macosx.ObjCRuntime.nil,
                objcMsgSend);
        } catch (Throwable t) {
            System.err.println("Warning: could not enter native fullscreen: " + t);
        }
    }
}
