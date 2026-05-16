package com.jjktbf.graphics.screens.editors;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.jjktbf.graphics.AssetLoader;
import com.jjktbf.graphics.JJKGame;
import com.jjktbf.model.character.CharacterData;
import com.jjktbf.model.character.CharacterRepository;
import com.jjktbf.model.character.StatKey;

import java.io.IOException;
import java.util.List;

/**
 * Graphical Character Editor screen.
 *
 * Left panel  — scrollable list of characters.
 * Right panel — stat summary for the selected character (all 10 stats,
 *               HP, AP Bar, move count, innate technique).
 *
 * Full stat editing is a Phase 4 task. This screen provides a usable
 * read/navigate view as a foundation.
 *
 * Controls:
 *   UP / DOWN — navigate list
 *   ESC       — back to main menu
 */
public class CharacterEditorScreen implements Screen {

    private static final String DATA_DIR = "data/characters";

    private static final StatKey[] STAT_ORDER = {
        StatKey.VITALITY, StatKey.STRENGTH, StatKey.DURABILITY, StatKey.SPEED,
        StatKey.COMBAT_ABILITY,
        StatKey.CURSED_ENERGY_RESERVES, StatKey.CURSED_ENERGY_EFFICIENCY,
        StatKey.CURSED_ENERGY_OUTPUT, StatKey.JUJUTSU_SKILL,
        StatKey.CURSED_TECHNIQUE_MASTERY
    };

    private final JJKGame             game;
    private final AssetLoader         assets;
    private final SpriteBatch         batch;
    private final CharacterRepository repo;

    private List<CharacterData> characters;
    private int    cursor    = 0;
    private String loadError = null;

    public CharacterEditorScreen(JJKGame game, AssetLoader assets) {
        this.game   = game;
        this.assets = assets;
        this.batch  = new SpriteBatch();
        this.repo   = new CharacterRepository(DATA_DIR);
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
            characters = repo.getAll();
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
        if (characters == null || characters.isEmpty()) return;

        if (Gdx.input.isKeyJustPressed(Input.Keys.UP)) {
            cursor = (cursor - 1 + characters.size()) % characters.size();
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.DOWN)) {
            cursor = (cursor + 1) % characters.size();
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
        assets.fontMedium.draw(batch, "CHARACTER EDITOR", 20, sh - 20);
        assets.fontSmall.setColor(Color.DARK_GRAY);
        assets.fontSmall.draw(batch, "ESC: back", sw - 100, sh - 20);

        if (loadError != null) {
            assets.fontSmall.setColor(Color.RED);
            assets.fontSmall.draw(batch, loadError, 20, sh * 0.5f);
            batch.end();
            return;
        }

        if (characters == null || characters.isEmpty()) {
            assets.fontSmall.setColor(Color.YELLOW);
            assets.fontSmall.draw(batch,
                "No characters. Use the CLI Character Editor to create some.", 20, sh * 0.5f);
            batch.end();
            return;
        }

        // Left list
        float listX  = 20f;
        float listY  = sh - 50f;
        float lineH  = 16f;
        int   visible = (int) ((sh - 80) / lineH);
        int   start   = Math.max(0, cursor - visible / 2);
        int   end     = Math.min(characters.size(), start + visible);

        for (int i = start; i < end; i++) {
            CharacterData cd = characters.get(i);
            String line = cd.id + "  " + cd.name;
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
        if (cursor < 0 || cursor >= characters.size()) return;
        CharacterData cd = characters.get(cursor);

        assets.fontSmall.setColor(Color.WHITE);
        assets.fontSmall.draw(batch, cd.name, x, topY);
        assets.fontSmall.setColor(Color.LIGHT_GRAY);
        String innate = cd.innateTechniqueName != null ? cd.innateTechniqueName : "None";
        assets.fontSmall.draw(batch, "Technique: " + innate, x, topY - lineH);

        // Stats
        assets.fontSmall.setColor(Color.WHITE);
        for (int i = 0; i < STAT_ORDER.length; i++) {
            StatKey key = STAT_ORDER[i];
            int val = key.get(cd);
            String display = val == 0 ? "N/A" : String.valueOf(val);
            assets.fontSmall.draw(batch,
                padRight(key.label, 28) + display,
                x, topY - (i + 2) * lineH);
        }

        // Move count
        int moveCount = cd.moveIds != null ? cd.moveIds.size() : 0;
        assets.fontSmall.setColor(Color.LIGHT_GRAY);
        assets.fontSmall.draw(batch, "Moves assigned: " + moveCount,
                x, topY - (STAT_ORDER.length + 3) * lineH);
    }

    private static String padRight(String s, int n) {
        return String.format("%-" + n + "s", s);
    }
}
