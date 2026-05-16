package com.jjktbf.graphics.screens.editors;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.jjktbf.graphics.AssetLoader;
import com.jjktbf.graphics.JJKGame;
import com.jjktbf.model.character.AbilityData;
import com.jjktbf.model.character.AbilityRepository;

import java.io.IOException;
import java.util.List;

/**
 * Graphical Ability Editor screen.
 *
 * Left panel  — scrollable list of abilities.
 * Right panel — summary of the selected ability: category, source, effects.
 *
 * Full editing is a Phase 4 task. This screen is the navigable read view.
 *
 * Controls:
 *   UP / DOWN — navigate list
 *   ESC       — back to main menu
 */
public class AbilityEditorScreen implements Screen {

    private static final String DATA_DIR = "data/abilities";

    private final JJKGame            game;
    private final AssetLoader        assets;
    private final SpriteBatch        batch;
    private final AbilityRepository  repo;

    private List<AbilityData> abilities;
    private int    cursor    = 0;
    private String loadError = null;

    public AbilityEditorScreen(JJKGame game, AssetLoader assets) {
        this.game   = game;
        this.assets = assets;
        this.batch  = new SpriteBatch();
        this.repo   = new AbilityRepository(DATA_DIR);
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
            abilities = repo.getAll();
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
        if (abilities == null || abilities.isEmpty()) return;

        if (Gdx.input.isKeyJustPressed(Input.Keys.UP)) {
            cursor = (cursor - 1 + abilities.size()) % abilities.size();
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.DOWN)) {
            cursor = (cursor + 1) % abilities.size();
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

        assets.fontMedium.setColor(Color.WHITE);
        assets.fontMedium.draw(batch, "ABILITY EDITOR", 20, sh - 20);
        assets.fontSmall.setColor(Color.DARK_GRAY);
        assets.fontSmall.draw(batch, "ESC: back", sw - 100, sh - 20);

        if (loadError != null) {
            assets.fontSmall.setColor(Color.RED);
            assets.fontSmall.draw(batch, loadError, 20, sh * 0.5f);
            batch.end();
            return;
        }

        if (abilities == null || abilities.isEmpty()) {
            assets.fontSmall.setColor(Color.YELLOW);
            assets.fontSmall.draw(batch,
                "No abilities. Use the CLI Ability Editor to create some.", 20, sh * 0.5f);
            batch.end();
            return;
        }

        // Left list
        float listX  = 20f;
        float listY  = sh - 50f;
        float lineH  = 16f;
        int   visible = (int) ((sh - 80) / lineH);
        int   start   = Math.max(0, cursor - visible / 2);
        int   end     = Math.min(abilities.size(), start + visible);

        for (int i = start; i < end; i++) {
            AbilityData ad = abilities.get(i);
            String line = ad.id + "  " + ad.name + "  [" + ad.category + "]";
            if (i == cursor) {
                assets.fontSmall.setColor(Color.YELLOW);
                line = "> " + line;
            } else {
                assets.fontSmall.setColor(Color.WHITE);
                line = "  " + line;
            }
            assets.fontSmall.draw(batch, line, listX, listY - (i - start) * lineH);
        }

        // Right detail
        drawDetail(sw * 0.5f, sh - 50f, lineH);

        assets.fontSmall.setColor(Color.DARK_GRAY);
        assets.fontSmall.draw(batch, "UP/DOWN: navigate", 20, 10);

        batch.end();
    }

    private void drawDetail(float x, float topY, float lineH) {
        if (cursor < 0 || cursor >= abilities.size()) return;
        AbilityData ad = abilities.get(cursor);

        float y = topY;
        assets.fontSmall.setColor(Color.WHITE);
        assets.fontSmall.draw(batch, ad.name, x, y); y -= lineH;

        assets.fontSmall.setColor(Color.LIGHT_GRAY);
        assets.fontSmall.draw(batch, "Category: " + ad.category, x, y); y -= lineH;

        String src = ad.sourceType != null ? ad.sourceType : "—";
        if (ad.sourceValue != null) src += " (" + ad.sourceValue + ")";
        assets.fontSmall.draw(batch, "Source:   " + src, x, y); y -= lineH;

        // Mechanic text (wrapped naively at char limit)
        if (ad.mechanicText != null && !ad.mechanicText.isBlank()) {
            assets.fontSmall.setColor(Color.CYAN);
            String text = ad.mechanicText;
            int charsPerLine = 36;
            while (text.length() > charsPerLine) {
                int cut = text.lastIndexOf(' ', charsPerLine);
                if (cut <= 0) cut = charsPerLine;
                assets.fontSmall.draw(batch, text.substring(0, cut), x, y); y -= lineH;
                text = text.substring(cut).trim();
            }
            if (!text.isEmpty()) { assets.fontSmall.draw(batch, text, x, y); y -= lineH; }
        }

        // Effects count
        y -= 4;
        assets.fontSmall.setColor(Color.WHITE);
        int effCount = ad.effects != null ? ad.effects.size() : 0;
        assets.fontSmall.draw(batch, "Effects: " + effCount, x, y); y -= lineH;

        if (ad.effects != null) {
            assets.fontSmall.setColor(Color.LIGHT_GRAY);
            for (int i = 0; i < Math.min(ad.effects.size(), 6); i++) {
                var eff = ad.effects.get(i);
                String line = "  " + eff.type + (eff.stat != null ? " [" + eff.stat + "]" : "");
                assets.fontSmall.draw(batch, line, x, y); y -= lineH;
            }
            if (ad.effects.size() > 6) {
                assets.fontSmall.draw(batch, "  ... +" + (ad.effects.size() - 6) + " more", x, y);
            }
        }
    }
}
