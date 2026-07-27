package com.jjktbf.graphics.ui.editor;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.ui.Widget;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.scenes.scene2d.utils.FocusListener;
import com.badlogic.gdx.utils.Align;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.StatTier;

import java.util.function.IntConsumer;

/**
 * A stat editor row: Label + TextField (type a value) + Slider with the min
 * and max labels at the edges.
 *
 * Mirrors the Pokémon Showdown stat-box feel: a clickable text field next to a
 * slider with the bounds shown on either side.
 *
 * Text editing is free-form while the field has keyboard focus: the user can
 * type, delete and edit digits like any text box (digits only, no live
 * clamping). The value is parsed, clamped to {@code [min,max]} and pushed to
 * {@code onChange} only when the field loses focus or the user presses Enter.
 * Dragging the slider commits immediately, as before.
 */
public class StatField extends Table {

    private static final float NAME_LABEL_WIDTH = 165f;
    private static final float EDGE_LABEL_WIDTH = 24f;
    private static final float VALUE_FIELD_WIDTH = 56f;
    private static final float TIER_MARKER_WIDTH = 2f;

    private final int min;
    private final int max;

    private final TextField valueField;
    private final Slider slider;
    private final IntConsumer onChange;
    private final Runnable onEdited;
    private boolean suppress = false;

    /**
     * @param name     label shown on the left (e.g. "Strength")
     * @param initial  starting value
     * @param min      minimum allowed value (left slider label)
     * @param max      maximum allowed value (right slider label)
     * @param onChange fires on every value change with the clamped int
     * @param onEdited fires when the user changes the slider or types in the field
     * @param disabled when true the field+slider are read-only (e.g. CTM with no technique)
     */
    public StatField(String name, int initial, int min, int max,
                      IntConsumer onChange, Runnable onEdited, boolean disabled, Skin skin) {
        super(skin);
        this.min      = min;
        this.max      = max;
        this.onChange = onChange;
        this.onEdited = onEdited;

        // Fixed minimum label width keeps every slider's left edge aligned when
        // several StatFields stack vertically in the stats section.
        Label nameLabel = new Label(name, skin);
        Label minLabel = new Label(String.valueOf(min), skin, "small");
        minLabel.setAlignment(Align.right);
        minLabel.setColor(skin.get("text-dim", Color.class));
        Label maxLabel = new Label(String.valueOf(max), skin, "small");
        maxLabel.setColor(skin.get("text-dim", Color.class));

        Slider.SliderStyle sliderStyle = skin.get("default-horizontal", Slider.SliderStyle.class);
        add(nameLabel).left().minWidth(NAME_LABEL_WIDTH).padRight(8);

        // Min edge label
        add(minLabel).width(EDGE_LABEL_WIDTH).padRight(4);

        // Slider
        slider = new TierSlider(min, max, sliderStyle, skin.getDrawable("white-pixel"));
        slider.setDisabled(disabled);
        slider.setValue(clamp(initial));
        add(slider).growX().padRight(4);

        // Max edge label
        add(maxLabel).width(EDGE_LABEL_WIDTH).padRight(6);

        // Numeric text field (type a value). Digits only — the field is
        // free-form while focused; clamping happens on commit (focus loss /
        // Enter), not on every keystroke, so intermediate edits like "5" while
        // typing "50" don't snap to the min.
        valueField = new HoverTextField(String.valueOf(clamp(initial)), skin);
        valueField.setTextFieldFilter((TextField tf, char c) -> Character.isDigit(c));
        valueField.setDisabled(disabled);
        // Fixed-ish width for the numeric input
        Container<TextField> fc = new Container<>(valueField);
        fc.width(VALUE_FIELD_WIDTH);
        add(fc).right();

        wire();
    }

    private void wire() {
        slider.addListener(new InputListener() {
            @Override public boolean touchDown(
                InputEvent event, float x, float y, int pointer, int button
            ) {
                if (!slider.isDisabled()) {
                    // Keep the enclosing detail ScrollPane from cancelling this
                    // touch focus when the drag includes vertical movement.
                    event.stop();
                }
                return false;
            }
        });

        slider.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                if (suppress) return;
                onEdited.run();
                int v = (int) slider.getValue();
                valueField.setText(String.valueOf(v));
                onChange.accept(v);
            }
        });

        // Commit on Enter (don't wait for focus loss).
        valueField.addListener(new InputListener() {
            @Override public boolean keyDown(InputEvent event, int keycode) {
                if (keycode == Input.Keys.ENTER) {
                    commit();
                    return true;
                }
                return false;
            }

            @Override public boolean keyTyped(InputEvent event, char character) {
                if (!suppress) onEdited.run();
                return false;
            }
        });

        // Commit when the field loses keyboard focus (e.g. clicking elsewhere).
        valueField.addListener(new FocusListener() {
            @Override public void keyboardFocusChanged(FocusEvent event, Actor actor, boolean focused) {
                if (!focused) commit();
            }
        });
    }

    /**
     * Parse the current text, clamp it to {@code [min,max]} and push the result
     * out. Called on focus loss and Enter. Empty / non-numeric text falls back
     * to the current slider value so the field always settles on something valid.
     */
    private void commit() {
        if (suppress) return;
        String s = valueField.getText().trim();
        int v;
        if (s.isEmpty()) {
            v = (int) slider.getValue();
        } else {
            try {
                v = clamp(Integer.parseInt(s));
            } catch (NumberFormatException ignored) {
                v = (int) slider.getValue();
            }
        }
        suppress = true;
        valueField.setText(String.valueOf(v));
        if ((int) slider.getValue() != v) slider.setValue(v);
        suppress = false;
        onChange.accept(v);
    }

    /** Programmatically set the value without firing change handlers. */
    public void setValueProgrammatic(int v) {
        suppress = true;
        v = clamp(v);
        slider.setValue(v);
        valueField.setText(String.valueOf(v));
        suppress = false;
    }

    /** Enable/disable editing (e.g. CTM locks when no technique). */
    public void setEditable(boolean editable) {
        slider.setDisabled(!editable);
        valueField.setDisabled(!editable);
    }

    /** Adjust the stat by whole slider ticks when it is editable. */
    public boolean adjustBy(int ticks) {
        if (slider.isDisabled()) return false;
        slider.setValue((int) slider.getValue() + ticks);
        return true;
    }

    /** Commit an in-progress numeric edit before applying another control. */
    public void commitTextValue() {
        commit();
    }

    public boolean isTextEditorFocused(Actor actor) {
        return valueField == actor;
    }

    public int getMin() { return min; }
    public int getMax() { return max; }

    private int clamp(int v) {
        return Math.max(min, Math.min(max, v));
    }

    /** Header row whose tier scale uses the exact same horizontal geometry as every stat slider. */
    public static Table tierHeader(Actor leadingActor, Skin skin) {
        Slider.SliderStyle sliderStyle = skin.get("default-horizontal", Slider.SliderStyle.class);
        Table header = new Table(skin);
        header.add(leadingActor).left().top().minWidth(NAME_LABEL_WIDTH).padRight(8);
        header.add().width(EDGE_LABEL_WIDTH).padRight(4);
        header.add(new TierLabels(sliderStyle, skin)).growX().padRight(4);
        header.add().width(EDGE_LABEL_WIDTH).padRight(6);
        header.add().width(VALUE_FIELD_WIDTH);
        return header;
    }

    private static float tierPosition(float width, Slider.SliderStyle style, float statValue) {
        float backgroundLeft = style.background == null ? 0f : style.background.getLeftWidth();
        float backgroundRight = style.background == null ? 0f : style.background.getRightWidth();
        float knobWidth = style.knob == null ? 0f : style.knob.getMinWidth();
        float start = backgroundLeft + knobWidth / 2f;
        float end = width - backgroundRight - knobWidth / 2f;
        float progress = (statValue - CharacterStats.MIN_STAT)
            / (float) (CharacterStats.MAX_STAT - CharacterStats.MIN_STAT);
        return start + progress * (end - start);
    }

    private static final class TierSlider extends Slider {

        private final Drawable marker;

        private TierSlider(float min, float max, SliderStyle style, Drawable marker) {
            super(min, max, 1f, false, style);
            this.marker = marker;
        }

        @Override
        public void draw(Batch batch, float parentAlpha) {
            super.draw(batch, parentAlpha);

            SliderStyle style = getStyle();
            float markerHeight = style.background == null ? 4f : style.background.getMinHeight();
            float markerY = getY() + (getHeight() - markerHeight) / 2f;
            float oldColor = batch.getPackedColor();
            Color actorColor = getColor();
            batch.setColor(Color.GRAY.r, Color.GRAY.g, Color.GRAY.b, actorColor.a * parentAlpha);
            for (StatTier tier : StatTier.values()) {
                float markerX = getX() + tierPosition(getWidth(), style, tier.minimumStat());
                marker.draw(batch, Math.round(markerX - TIER_MARKER_WIDTH / 2f),
                    Math.round(markerY), TIER_MARKER_WIDTH, markerHeight);
            }
            batch.setPackedColor(oldColor);
        }
    }

    private static final class TierLabels extends Widget {

        private static final float LABEL_GAP = 5f;
        private static final float POINTER_SIZE = 14f;
        private static final float LEVEL_GAP = 4f;
        private static final float VERTICAL_DROP = 40f;

        private final Slider.SliderStyle sliderStyle;
        private final BitmapFont font;
        private final Drawable pointer;
        private final GlyphLayout layout = new GlyphLayout();

        private TierLabels(Slider.SliderStyle sliderStyle, Skin skin) {
            this.sliderStyle = sliderStyle;
            font = skin.get("small", Label.LabelStyle.class).font;
            pointer = skin.getDrawable("tier-pointer");
        }

        @Override
        public float getPrefHeight() {
            return (font.getLineHeight() + POINTER_SIZE) * 2f + LEVEL_GAP;
        }

        @Override
        public void draw(Batch batch, float parentAlpha) {
            Color oldFontColor = new Color(font.getColor());
            font.setColor(0f, 0f, 0f, getColor().a * parentAlpha);
            float oldBatchColor = batch.getPackedColor();
            batch.setColor(1f, 1f, 1f, getColor().a * parentAlpha);
            float levelHeight = font.getLineHeight() + POINTER_SIZE;
            float[] lastLabelRight = {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
            StatTier[] tiers = StatTier.values();
            for (StatTier tier : tiers) {
                layout.setText(font, tier.displayName());
                float midpoint = (tier.minimumStat() + tier.maximumStat()) / 2f;
                float pointerX = getX() + tierPosition(getWidth(), sliderStyle, midpoint);
                float labelX = Math.max(getX(), Math.min(pointerX - layout.width / 2f,
                    getX() + getWidth() - layout.width));
                int level = labelX < lastLabelRight[0] + LABEL_GAP ? 1 : 0;
                float pointerY = getY() - VERTICAL_DROP;
                float labelY = pointerY + POINTER_SIZE + layout.height
                    + level * (levelHeight + LEVEL_GAP) / 2f;
                font.draw(batch, layout, labelX, labelY);
                pointer.draw(batch, pointerX - POINTER_SIZE / 2f, pointerY,
                    POINTER_SIZE, POINTER_SIZE);
                lastLabelRight[level] = labelX + layout.width;
            }
            batch.setPackedColor(oldBatchColor);
            font.setColor(oldFontColor);
        }
    }
}
