package com.jjktbf.graphics.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.jjktbf.graphics.ui.battle.BattleUiAssets;
import com.jjktbf.model.combat.BattleCombatant;

/** Pixel-framed portrait, HP, and CE display for one execution combatant. */
public class CombatantPanel {

    private static final float BAR_HEIGHT = 28f;
    private static final float BAR_GAP = 8f;
    private static final float NAME_PLATE_HEIGHT = 34f;
    private static final float NAME_PLATE_PAD = 16f;
    private final GlyphLayout nameLayout = new GlyphLayout();

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

        float barWidth = spriteWidth + 34f;
        float barX = x - 17f;
        float barY = y - BAR_HEIGHT * 2f - BAR_GAP - 20f;
        hpBar = new StatusBar("HP", new Color(0.260f, 0.820f, 0.360f, 1f));
        hpBar.setBounds(barX, barY + BAR_HEIGHT + BAR_GAP, barWidth, BAR_HEIGHT);
        ceBar = new StatusBar("CE", new Color(0.220f, 0.500f, 0.940f, 1f));
        ceBar.setBounds(barX, barY, barWidth, BAR_HEIGHT);
    }

    public void update(BattleCombatant combatant) {
        hpBar.setValues(combatant.getCurrentHp(), combatant.getEffectiveCombatStats().getMaxHp());
        ceBar.setValues(combatant.getCurrentCe(), combatant.getEffectiveCombatStats().getMaxCursedEnergy());
    }

    public void draw(Batch batch, BitmapFont font, String name, float delta) {
        draw(batch, font, font, name, delta);
    }

    /**
     * Draw with separate fonts for the name plate and the resource bars, so the
     * name can be rendered larger without crowding the HP/CE labels.
     */
    public void draw(Batch batch, BitmapFont nameFont, BitmapFont barFont, String name, float delta) {
        float frameX = x - 10f;
        float frameY = y - 10f;
        ui.palette.draw(batch, frameX, frameY, spriteWidth + 20f, spriteHeight + 20f);
        batch.draw(sprite, x, y, spriteWidth, spriteHeight);

        // Name plate sized to the text so a larger name font never truncates.
        nameLayout.setText(nameFont, name);
        float plateWidth = Math.max(spriteWidth + 20f, nameLayout.width + NAME_PLATE_PAD * 2f);
        float plateX = frameX + (spriteWidth + 20f - plateWidth) / 2f;
        float plateY = y + spriteHeight + 8f;
        ui.header.draw(batch, plateX, plateY, plateWidth, NAME_PLATE_HEIGHT);
        nameFont.setColor(Color.WHITE);
        nameFont.draw(batch, name,
            frameX + (spriteWidth + 20f - nameLayout.width) / 2f,
            plateY + NAME_PLATE_HEIGHT / 2f + nameLayout.height / 2f);
        hpBar.update(delta);
        ceBar.update(delta);
        hpBar.draw(batch, barFont, ui);
        ceBar.draw(batch, barFont, ui);
    }
}
