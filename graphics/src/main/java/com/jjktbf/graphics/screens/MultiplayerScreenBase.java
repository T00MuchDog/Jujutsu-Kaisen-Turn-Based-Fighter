package com.jjktbf.graphics.screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import com.jjktbf.graphics.AssetLoader;
import com.jjktbf.graphics.JJKGame;
import com.jjktbf.graphics.multiplayer.ApiClientException;

import java.io.UncheckedIOException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** Shared Scene2D lifecycle and visual chrome for multiplayer screens. */
abstract class MultiplayerScreenBase implements Screen {
    protected enum StatusTone {
        NORMAL,
        ERROR,
        OK
    }

    protected final JJKGame game;
    protected final AssetLoader assets;
    protected final Stage stage;
    protected final Table root;

    private volatile long lifecycleGeneration;
    private volatile boolean visible;
    private boolean disposed;

    protected MultiplayerScreenBase(JJKGame game, AssetLoader assets) {
        this.game = game;
        this.assets = assets;
        this.stage = new Stage(new ScreenViewport());
        this.root = new Table();
        root.setFillParent(true);
        root.pad(22f);
        stage.addActor(root);

        stage.addCaptureListener(new InputListener() {
            @Override
            public boolean keyDown(InputEvent event, int keycode) {
                if (keycode != Input.Keys.ESCAPE && keycode != Input.Keys.BACK) {
                    return false;
                }
                onBackRequested();
                event.cancel();
                return true;
            }
        });
    }

    @Override
    public final void show() {
        if (disposed) {
            return;
        }
        visible = true;
        long generation = ++lifecycleGeneration;
        stage.unfocusAll();
        Gdx.input.setCatchKey(Input.Keys.BACK, true);
        Gdx.input.setInputProcessor(stage);
        onShown(generation);
    }

    @Override
    public final void render(float delta) {
        Gdx.gl.glClearColor(0.804f, 0.863f, 0.980f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        stage.act(delta);
        stage.draw();
    }

    @Override
    public final void resize(int width, int height) {
        stage.getViewport().update(width, height, true);
        onResized(width, height);
    }

    @Override
    public final void hide() {
        if (!visible) {
            return;
        }
        visible = false;
        lifecycleGeneration++;
        stage.cancelTouchFocus();
        Gdx.input.setCatchKey(Input.Keys.BACK, false);
        onHidden();
    }

    @Override
    public final void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        visible = false;
        lifecycleGeneration++;
        onDisposed();
        stage.dispose();
    }

    @Override
    public final void pause() {
    }

    @Override
    public final void resume() {
    }

    protected abstract void onShown(long generation);

    protected abstract void onBackRequested();

    protected void onHidden() {
    }

    protected void onDisposed() {
    }

    protected void onResized(int width, int height) {
    }

    protected final long generation() {
        return lifecycleGeneration;
    }

    /** Safe to call from worker threads before launching a follow-up operation. */
    protected final boolean isGenerationVisible(long expectedGeneration) {
        return !disposed && visible && lifecycleGeneration == expectedGeneration;
    }

    /** Marshals a callback to LibGDX and rejects callbacks for hidden/reused screens. */
    protected final void postIfCurrent(long expectedGeneration, Runnable callback) {
        Gdx.app.postRunnable(() -> {
            if (isGenerationVisible(expectedGeneration) && game.getScreen() == this) {
                callback.run();
            }
        });
    }

    protected final void postIfCurrentOrElse(
        long expectedGeneration,
        Runnable callback,
        Runnable staleCallback
    ) {
        Gdx.app.postRunnable(() -> {
            if (isGenerationVisible(expectedGeneration) && game.getScreen() == this) {
                callback.run();
            } else if (staleCallback != null) {
                staleCallback.run();
            }
        });
    }

    protected final Table header(String title, String subtitle) {
        Table header = new Table(assets.editorSkin);
        header.setBackground(assets.editorSkin.getDrawable("battle-header"));
        header.pad(12f, 16f, 12f, 16f);

        Label titleLabel = new Label(title, assets.editorSkin, "title");
        titleLabel.setAlignment(Align.left);
        header.add(titleLabel).growX().left();
        if (subtitle != null && !subtitle.isBlank()) {
            // small-white base: the header sits on dark-blue chrome, and Label
            // actor colour multiplies the style fontColour, so a TEXT_DARK "small"
            // label recoloured periwinkle renders as dark navy.
            Label subtitleLabel = new Label(subtitle, assets.editorSkin, "small-white");
            subtitleLabel.setColor(new Color(0.720f, 0.800f, 0.950f, 1f));
            header.add(subtitleLabel).right().padLeft(12f);
        }
        return header;
    }

    protected final Label wrappedLabel(String text, String style) {
        Label label = new Label(text == null ? "" : text, assets.editorSkin, style);
        label.setWrap(true);
        label.setAlignment(Align.left);
        return label;
    }

    protected final TextButton button(String text, String style, Runnable action) {
        TextButton button = new TextButton(text, assets.editorSkin, style);
        button.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                if (!button.isDisabled()) {
                    action.run();
                }
            }
        });
        return button;
    }

    protected final void setStatus(Label label, String text, StatusTone tone) {
        label.setText(text == null ? "" : text);
        Color color = switch (tone) {
            case ERROR -> assets.editorSkin.get("text-error", Color.class);
            case OK -> assets.editorSkin.get("text-ok", Color.class);
            case NORMAL -> assets.editorSkin.get("text-dim", Color.class);
        };
        label.setColor(color);
    }

    protected static String userError(Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof ApiClientException apiFailure) {
            return "[" + apiFailure.code() + "] " + apiFailure.userMessage();
        }
        if (cause instanceof UncheckedIOException) {
            return "Guest account data could not be read or saved.";
        }
        if (cause instanceof IllegalStateException
            && cause.getMessage() != null
            && !cause.getMessage().isBlank()) {
            return cause.getMessage();
        }
        return "The multiplayer request failed. Please try again.";
    }

    protected static String errorCode(Throwable failure) {
        Throwable cause = unwrap(failure);
        return cause instanceof ApiClientException apiFailure
            ? apiFailure.code() : "MULTIPLAYER_ERROR";
    }

    protected static boolean isAmbiguousFailure(Throwable failure) {
        Throwable cause = unwrap(failure);
        return cause instanceof ApiClientException apiFailure
            && apiFailure.kind() != ApiClientException.Kind.HTTP_ERROR
            && apiFailure.kind() != ApiClientException.Kind.CLIENT_CLOSED;
    }

    protected static void logFailure(String operation, Throwable failure) {
        Throwable cause = unwrap(failure);
        String detail = cause instanceof ApiClientException apiFailure
            ? apiFailure.kind() + "/" + apiFailure.code()
            : cause.getClass().getSimpleName();
        // Keep the short stderr line for the console, but also persist the full
        // stack trace so multiplayer failures are diagnosable after the fact
        // (the console line alone drops the cause).
        System.err.println("Multiplayer " + operation + " failed: " + detail);
        com.jjktbf.AppPaths.logException(failure);
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
            && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
