package com.jjktbf.graphics.ui.editor;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.utils.Align;

import java.util.function.IntConsumer;

/**
 * A stat editor row: Label + TextField (type a value) + Slider with the min
 * and max labels at the edges, all kept in sync and clamped to {@code [min,max]}.
 *
 * Mirrors the Pokémon Showdown stat-box feel: a clickable text field next to a
 * slider with the bounds shown on either side.
 *
 * Every change — whether typed in the field or dragged on the slider — fires
 * the supplied {@link IntConsumer} with the clamped value.
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

        Label nameLabel = new Label(name, skin);
        add(nameLabel).left().padRight(8);

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

        // Numeric text field (type a value)
        valueField = new HoverTextField(String.valueOf(clamp(initial)), skin);
        valueField.setTextFieldFilter((TextField tf, char c) ->
            Character.isDigit(c) || (c == '-' && tf.getCursorPosition() == 0));
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

        valueField.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                if (suppress) return;
                String s = valueField.getText().trim();
                if (s.isEmpty()) return;
                try {
                    int v = clamp(Integer.parseInt(s));
                    if ((int) slider.getValue() != v) slider.setValue(v);
                    onChange.accept(v);
                } catch (NumberFormatException ignored) { /* keep typing */ }
            }
        });
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
