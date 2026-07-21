package com.jjktbf.graphics.ui.editor;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.FocusListener;
import com.badlogic.gdx.utils.Align;

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

    private final Skin skin;
    private final String name;
    private final int min;
    private final int max;

    private final TextField valueField;
    private final Slider slider;
    private final IntConsumer onChange;
    private boolean suppress = false;

    /**
     * @param name     label shown on the left (e.g. "Strength")
     * @param initial  starting value
     * @param min      minimum allowed value (left slider label)
     * @param max      maximum allowed value (right slider label)
     * @param onChange fires on every value change with the clamped int
     * @param disabled when true the field+slider are read-only (e.g. CTM with no technique)
     */
    public StatField(String name, int initial, int min, int max,
                     IntConsumer onChange, boolean disabled, Skin skin) {
        super(skin);
        this.skin     = skin;
        this.name     = name;
        this.min      = min;
        this.max      = max;
        this.onChange = onChange;

        // Fixed minimum label width keeps every slider's left edge aligned when
        // several StatFields stack vertically in the stats section.
        Label nameLabel = new Label(name, skin);
        add(nameLabel).left().minWidth(190f).padRight(8);

        // Min edge label
        Label minLabel = new Label(String.valueOf(min), skin, "small");
        minLabel.setColor(skin.get("text-dim", com.badlogic.gdx.graphics.Color.class));
        add(minLabel).padRight(4);

        // Slider
        slider = new Slider(min, max, 1, false, skin);
        slider.setDisabled(disabled);
        slider.setValue(clamp(initial));
        add(slider).growX().padRight(4);

        // Max edge label
        Label maxLabel = new Label(String.valueOf(max), skin, "small");
        maxLabel.setColor(skin.get("text-dim", com.badlogic.gdx.graphics.Color.class));
        add(maxLabel).padRight(6);

        // Numeric text field (type a value). Digits only — the field is
        // free-form while focused; clamping happens on commit (focus loss /
        // Enter), not on every keystroke, so intermediate edits like "5" while
        // typing "50" don't snap to the min.
        valueField = new HoverTextField(String.valueOf(clamp(initial)), skin);
        valueField.setTextFieldFilter((TextField tf, char c) -> Character.isDigit(c));
        valueField.setDisabled(disabled);
        // Fixed-ish width for the numeric input
        Container<TextField> fc = new Container<>(valueField);
        fc.width(56f);
        add(fc).right();

        wire();
    }

    private void wire() {
        slider.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                if (suppress) return;
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

    public int getMin() { return min; }
    public int getMax() { return max; }

    private int clamp(int v) {
        return Math.max(min, Math.min(max, v));
    }
}
