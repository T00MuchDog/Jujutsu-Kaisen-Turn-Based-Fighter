package com.jjktbf.graphics.screens.editors;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.jjktbf.graphics.AssetLoader;
import com.jjktbf.graphics.JJKGame;
import com.jjktbf.model.move.MoveData;
import com.jjktbf.model.move.MoveRepository;

import java.io.IOException;
import java.util.List;

/**
 * Graphical Move Editor screen.
 *
 * Left panel  — scrollable list of all moves.
 * Right panel — read-only summary of the selected move's fields.
 *
 * Editing is intentionally deferred: the full form (all fields, dropdowns,
 * tag pickers) is a Phase 4 task. This screen provides a usable read/navigate
 * view as a foundation to build upon.
 *
 * Controls:
 *   UP / DOWN  — navigate list
 *   ESC        — back to main menu
 */
public class MoveEditorScreen implements Screen {

    private static final String DATA_DIR = "data/moves";

    private final JJKGame        game;
    private final AssetLoader    assets;
    private final SpriteBatch    batch;
    private final MoveRepository repo;

    private List<MoveData> moves;
    private int            cursor    = 0;
    private String         loadError = null;

    public MoveEditorScreen(JJKGame game, AssetLoader assets) {
        this.game   = game;
        this.assets = assets;
        this.batch  = new SpriteBatch();
        this.repo   = new MoveRepository(DATA_DIR);
    }

    // -------------------------------------------------------------------------
    // Screen lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void show() {
        cursor    = 0;
        loadError = null;
        try {
            repo.load();
            moves = repo.getAll();
        } catch (IOException e) {
            loadError = "Load failed: " + e.getMessage();
        }
    }

    @Override
    public void render(float delta) {
        clear();
        handleInput();
        draw();
    }

    @Override public void resize(int w, int h) { batch.getProjectionMatrix().setToOrtho2D(0, 0, w, h); }
    @Override public void pause()  {}
    @Override public void resume() {}
    @Override public void hide()   {}
    @Override public void dispose() { batch.dispose(); }

    // -------------------------------------------------------------------------
    // Input
    // -------------------------------------------------------------------------

    private void handleInput() {
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) { game.showMainMenu(); return; }
        if (moves == null || moves.isEmpty()) return;

        if (Gdx.input.isKeyJustPressed(Input.Keys.UP)) {
            cursor = (cursor - 1 + moves.size()) % moves.size();
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.DOWN)) {
            cursor = (cursor + 1) % moves.size();
        }
    }

    // -------------------------------------------------------------------------
    // Draw
    // -------------------------------------------------------------------------

    private void clear() {
        Gdx.gl.glClearColor(0, 0, 0, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
    }

    private void draw() {
        float sw = Gdx.graphics.getWidth();
        float sh = Gdx.graphics.getHeight();

        batch.begin();

        // Header
        assets.fontMedium.setColor(Color.WHITE);
        assets.fontMedium.draw(batch, "MOVE EDITOR", 20, sh - 20);
        assets.fontSmall.setColor(Color.DARK_GRAY);
        assets.fontSmall.draw(batch, "ESC: back", sw - 100, sh - 20);

        if (loadError != null) {
            assets.fontSmall.setColor(Color.RED);
            assets.fontSmall.draw(batch, loadError, 20, sh * 0.5f);
            batch.end();
            return;
        }

        if (moves == null || moves.isEmpty()) {
            assets.fontSmall.setColor(Color.YELLOW);
            assets.fontSmall.draw(batch, "No moves. Use the CLI Move Editor to create some.", 20, sh * 0.5f);
            batch.end();
            return;
        }

        // Left list panel
        float listX  = 20f;
        float listY  = sh - 50f;
        float lineH  = 16f;
        int   visible = (int) ((sh - 80) / lineH);
        int   start   = Math.max(0, cursor - visible / 2);
        int   end     = Math.min(moves.size(), start + visible);

        for (int i = start; i < end; i++) {
            MoveData md = moves.get(i);
            String line = md.id + "  " + md.name;
            if (i == cursor) {
                assets.fontSmall.setColor(Color.YELLOW);
                line = "> " + line;
            } else {
                assets.fontSmall.setColor(Color.WHITE);
                line = "  " + line;
            }
            assets.fontSmall.draw(batch, line, listX, listY - (i - start) * lineH);
        }

        // Right detail panel
        drawDetail(sw * 0.5f, sh - 50f, lineH);

        // Footer
        assets.fontSmall.setColor(Color.DARK_GRAY);
        assets.fontSmall.draw(batch, "UP/DOWN: navigate", 20, 10);

        batch.end();
    }

    private void drawDetail(float x, float topY, float lineH) {
        if (cursor < 0 || cursor >= moves.size()) return;
        MoveData md = moves.get(cursor);

        assets.fontSmall.setColor(Color.WHITE);
        String[] lines = {
            "ID:      " + md.id,
            "Name:    " + md.name,
            "Tags:    " + (md.tags != null ? String.join(", ", md.tags) : "—"),
            "AP Cost: " + md.apCost,
            "Unleash: " + md.unleashPoint,
            "Power:   " + (md.basePower == 0 ? "N/A" : md.basePower),
            "Acc:     " + (md.neverMiss ? "Never miss" : (int)(md.baseAccuracy * 100) + "%"),
            "CE Cost: " + (md.baseCeCost == 0 ? "N/A" : md.baseCeCost),
            "Defense: " + md.defenseType,
            "Free:    " + md.isFreeMove,
            "Req Tec: " + (md.requiredTechniqueId != null ? md.requiredTechniqueId : "—"),
        };

        for (int i = 0; i < lines.length; i++) {
            assets.fontSmall.draw(batch, lines[i], x, topY - i * lineH);
        }

        // Description wrapped
        if (md.description != null && !md.description.isBlank()) {
            assets.fontSmall.setColor(Color.LIGHT_GRAY);
            assets.fontSmall.draw(batch, "Desc: " + truncate(md.description, 40),
                    x, topY - lines.length * lineH - 4);
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
