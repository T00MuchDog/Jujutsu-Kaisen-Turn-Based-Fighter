package com.jjktbf.graphics.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.math.Rectangle;
import com.jjktbf.graphics.ui.battle.BattleUiAssets;
import com.jjktbf.model.combat.BattleCombatant;

/** Pokemon-style field sprite and separate resource card for one combatant. */
public class CombatantPanel {

    private final GlyphLayout nameLayout = new GlyphLayout();

    private final Texture sprite;
    private final Texture basePlate;
    private final BattleUiAssets ui;
    private final StatusBar hpBar;
    private final StatusBar ceBar;
    private final Rectangle plateBounds;
    private final Rectangle spriteBounds;
    private final Rectangle hudBounds;
    private final float hpBarTop;
    private final float hudScale;

    /**
     * The plate and sprite occupy the battlefield while the HUD is positioned on
     * the opposite side, matching the classic monster-battle composition.
     */
    public CombatantPanel(Texture sprite, Texture basePlate, BattleUiAssets ui,
                           Rectangle plateBounds, Rectangle spriteBounds, Rectangle hudBounds,
                           float hudScale) {
        this.sprite = sprite;
        this.basePlate = basePlate;
        this.ui = ui;
        this.plateBounds = new Rectangle(plateBounds);
        this.spriteBounds = new Rectangle(spriteBounds);
        this.hudBounds = new Rectangle(hudBounds);
        this.hudScale = hudScale;

        hpBar = new StatusBar("HP", new Color(0.260f, 0.820f, 0.360f, 1f));
        ceBar = new StatusBar("CE", new Color(0.220f, 0.500f, 0.940f, 1f));

        float inset = Math.max(10f * hudScale, hudBounds.height * 0.11f);
        float gap = Math.max(4f * hudScale, hudBounds.height * 0.045f);
        float barHeight = Math.max(18f * hudScale, Math.min(25f * hudScale,
            (hudBounds.height - inset * 2f - 25f * hudScale - gap) / 2f));
        float barWidth = hudBounds.width - inset * 2f;
        float ceY = hudBounds.y + inset;
        ceBar.setBounds(hudBounds.x + inset, ceY, barWidth, barHeight);
        hpBar.setBounds(hudBounds.x + inset, ceY + barHeight + gap, barWidth, barHeight);
        hpBarTop = ceY + barHeight * 2f + gap;
    }

    public void update(BattleCombatant combatant) {
        update(
            combatant.getCurrentHp(),
            combatant.getMaxHp(),
            combatant.getCurrentCe(),
            combatant.getMaxCursedEnergy()
        );
    }

    /** Updates the shared HUD from an immutable authoritative snapshot. */
    public void update(int currentHp, int maxHp, int currentCe, int maxCe) {
        hpBar.setValues(currentHp, maxHp);
        ceBar.setValues(currentCe, maxCe);
    }

    public void draw(Batch batch, BitmapFont nameFont, BitmapFont barFont, String name, float delta) {
        batch.setColor(Color.WHITE);
        if (basePlate != null) {
            batch.draw(basePlate, plateBounds.x, plateBounds.y, plateBounds.width, plateBounds.height);
        }
        batch.draw(sprite, spriteBounds.x, spriteBounds.y, spriteBounds.width, spriteBounds.height);

        // Offset dark frame creates the hard lower-right shadow used by the reference HUD.
        ui.palette.draw(batch, hudBounds.x + 7f * hudScale, hudBounds.y - 7f * hudScale,
            hudBounds.width, hudBounds.height);
        ui.card.draw(batch, hudBounds.x, hudBounds.y, hudBounds.width, hudBounds.height);

        float originalScaleX = nameFont.getData().scaleX;
        float originalScaleY = nameFont.getData().scaleY;
        nameFont.getData().setScale(originalScaleX * hudScale, originalScaleY * hudScale);
        nameLayout.setText(nameFont, name);
        float availableNameWidth = hudBounds.width - 28f * hudScale;
        if (nameLayout.width > availableNameWidth) {
            float fittedScale = availableNameWidth / nameLayout.width;
            nameFont.getData().setScale(
                originalScaleX * hudScale * fittedScale,
                originalScaleY * hudScale * fittedScale);
            nameLayout.setText(nameFont, name);
        }
        nameFont.setColor(BattleUiAssets.TEXT);
        float originalNameY = hudBounds.y + hudBounds.height - 10f * hudScale;
        float currentGap = originalNameY - nameFont.getCapHeight() - hpBarTop;
        float nameY = currentGap > 0f ? originalNameY - currentGap / 2f : originalNameY;
        nameFont.draw(batch, name, hudBounds.x + 15f * hudScale, nameY);
        nameFont.getData().setScale(originalScaleX, originalScaleY);

        hpBar.update(delta);
        ceBar.update(delta);
        float originalBarScaleX = barFont.getData().scaleX;
        float originalBarScaleY = barFont.getData().scaleY;
        barFont.getData().setScale(originalBarScaleX * hudScale, originalBarScaleY * hudScale);
        hpBar.draw(batch, barFont, ui);
        ceBar.draw(batch, barFont, ui);
        barFont.getData().setScale(originalBarScaleX, originalBarScaleY);
    }
}
