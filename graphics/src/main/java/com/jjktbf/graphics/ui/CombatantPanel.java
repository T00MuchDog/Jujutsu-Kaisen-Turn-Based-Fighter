package com.jjktbf.graphics.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.jjktbf.graphics.ui.battle.BattleUiAssets;
import com.jjktbf.model.combat.BattleCombatant;

/** Pixel-framed portrait, HP, and CE display for one execution combatant. */
public class CombatantPanel {

    private static final float BAR_HEIGHT = 24f;
    private static final float BAR_GAP = 6f;

    private final Texture sprite;
    private final BattleUiAssets ui;
    private final StatusBar hpBar;
    private final StatusBar ceBar;
    private final float x;
    private final float y;
    private final float spriteWidth;
    private final float spriteHeight;

    public CombatantPanel(Texture sprite, BattleUiAssets ui, float x, float y,
                          float spriteWidth, float spriteHeight) {
        this.sprite = sprite;
        this.ui = ui;
        this.x = x;
        this.y = y;
        this.spriteWidth = spriteWidth;
        this.spriteHeight = spriteHeight;

        float barWidth = spriteWidth + 22f;
        float barX = x - 11f;
        float barY = y - BAR_HEIGHT * 2f - BAR_GAP - 16f;
        hpBar = new StatusBar("HP", new Color(0.260f, 0.820f, 0.360f, 1f));
        hpBar.setBounds(barX, barY + BAR_HEIGHT + BAR_GAP, barWidth, BAR_HEIGHT);
        ceBar = new StatusBar("CE", new Color(0.220f, 0.500f, 0.940f, 1f));
        ceBar.setBounds(barX, barY, barWidth, BAR_HEIGHT);
    }

    public void update(BattleCombatant combatant) {
        hpBar.setValues(combatant.getCurrentHp(), combatant.getEffectiveCombatStats().getMaxHp());
        ceBar.setValues(combatant.getCurrentCe(), combatant.getEffectiveCombatStats().getMaxCursedEnergy());
    }

    public void draw(Batch batch, BitmapFont font, String name) {
        float frameX = x - 10f;
        float frameY = y - 10f;
        ui.palette.draw(batch, frameX, frameY, spriteWidth + 20f, spriteHeight + 20f);
        batch.draw(sprite, x, y, spriteWidth, spriteHeight);

        ui.header.draw(batch, frameX, y + spriteHeight + 8f, spriteWidth + 20f, 28f);
        font.setColor(Color.WHITE);
        font.draw(batch, name, x + 8f, y + spriteHeight + 27f);
        hpBar.draw(batch, font, ui);
        ceBar.draw(batch, font, ui);
    }
}
