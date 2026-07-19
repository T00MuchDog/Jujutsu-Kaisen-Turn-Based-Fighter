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
    private final float scale;

    /**
     * @param scale  multiplier for all non-sprite layout dimensions (bar height,
     *               gaps, frame insets, name plate). 1 = original size.
     */
    public CombatantPanel(Texture sprite, BattleUiAssets ui, float x, float y,
                          float spriteWidth, float spriteHeight, float scale) {
        this.sprite = sprite;
        this.ui = ui;
        this.x = x;
        this.y = y;
        this.spriteWidth = spriteWidth;
        this.spriteHeight = spriteHeight;
        this.scale = scale;

        float barWidth = spriteWidth + 34f * scale;
        float barX = x - 17f * scale;
        float scaledBarH = BAR_HEIGHT * scale;
        float scaledGap = BAR_GAP * scale;
        float barY = y - scaledBarH * 2f - scaledGap - 20f * scale;
        hpBar = new StatusBar("HP", new Color(0.260f, 0.820f, 0.360f, 1f));
        hpBar.setBounds(barX, barY + scaledBarH + scaledGap, barWidth, scaledBarH);
        ceBar = new StatusBar("CE", new Color(0.220f, 0.500f, 0.940f, 1f));
        ceBar.setBounds(barX, barY, barWidth, scaledBarH);
    }

    public void update(BattleCombatant combatant) {
        update(
            combatant.getCurrentHp(),
            combatant.getEffectiveCombatStats().getMaxHp(),
            combatant.getCurrentCe(),
            combatant.getEffectiveCombatStats().getMaxCursedEnergy()
        );
    }

    /** Updates the shared HUD from an immutable authoritative snapshot. */
    public void update(int currentHp, int maxHp, int currentCe, int maxCe) {
        hpBar.setValues(currentHp, maxHp);
        ceBar.setValues(currentCe, maxCe);
    }

    public void draw(Batch batch, BitmapFont font, String name, float delta) {
        draw(batch, font, font, name, delta);
    }

    /**
     * Draw with separate fonts for the name plate and the resource bars, so the
     * name can be rendered larger without crowding the HP/CE labels.
     */
    public void draw(Batch batch, BitmapFont nameFont, BitmapFont barFont, String name, float delta) {
        float frameX = x - 10f * scale;
        float frameY = y - 10f * scale;
        float frameW = spriteWidth + 20f * scale;
        float frameH = spriteHeight + 20f * scale;
        ui.palette.draw(batch, frameX, frameY, frameW, frameH);
        batch.draw(sprite, x, y, spriteWidth, spriteHeight);

        // Name plate sized to the text so a larger name font never truncates.
        float plateH = NAME_PLATE_HEIGHT * scale;
        float platePad = NAME_PLATE_PAD * scale;
        nameLayout.setText(nameFont, name);
        float plateWidth = Math.max(frameW, nameLayout.width + platePad * 2f);
        float plateX = frameX + (frameW - plateWidth) / 2f;
        float plateY = y + spriteHeight + 8f * scale;
        ui.header.draw(batch, plateX, plateY, plateWidth, plateH);
        nameFont.setColor(Color.WHITE);
        nameFont.draw(batch, name,
            frameX + (frameW - nameLayout.width) / 2f,
            plateY + plateH / 2f + nameLayout.height / 2f);
        hpBar.update(delta);
        ceBar.update(delta);
        hpBar.draw(batch, barFont, ui);
        ceBar.draw(batch, barFont, ui);
    }
}
