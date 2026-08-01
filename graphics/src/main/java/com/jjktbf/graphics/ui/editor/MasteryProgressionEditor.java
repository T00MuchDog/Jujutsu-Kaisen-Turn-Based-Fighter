package com.jjktbf.graphics.ui.editor;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.jjktbf.graphics.ui.DynamicSelectBox;
import com.jjktbf.model.progression.TechniqueMasteryProgressionData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** Per-field formula/benchmark editor with a live CTM 0..300 preview. */
public final class MasteryProgressionEditor extends Table {

    private final String field;
    private final IntSupplier literalValue;
    private final Supplier<Map<String, TechniqueMasteryProgressionData>> getter;
    private final Consumer<Map<String, TechniqueMasteryProgressionData>> setter;
    private final Runnable onDirty;
    private final Skin skin;
    private final Container<Actor> details = new Container<>();

    public MasteryProgressionEditor(
        String field,
        IntSupplier literalValue,
        Supplier<Map<String, TechniqueMasteryProgressionData>> getter,
        Consumer<Map<String, TechniqueMasteryProgressionData>> setter,
        Runnable onDirty,
        Skin skin
    ) {
        super(skin);
        this.field = field;
        this.literalValue = literalValue;
        this.getter = getter;
        this.setter = setter;
        this.onDirty = onDirty == null ? () -> { } : onDirty;
        this.skin = skin;
        defaults().left().pad(3).growX();
        rebuild();
    }

    private void rebuild() {
        clearChildren();
        CheckBox enabled = new CheckBox(" Scale this value with CTM", skin);
        enabled.setChecked(progression() != null);
        enabled.addListener(change(() -> {
            if (enabled.isChecked()) {
                TechniqueMasteryProgressionData data = new TechniqueMasteryProgressionData();
                data.mode = TechniqueMasteryProgressionData.FORMULA;
                data.formula = String.valueOf(literalValue.getAsInt());
                put(data);
            } else {
                removeProgression();
            }
            onDirty.run();
            rebuild();
        }));
        add(enabled).row();
        details.fill(true, false);
        details.setActor(enabled.isChecked() ? buildDetails() : null);
        add(details).growX().row();
    }

    private Actor buildDetails() {
        TechniqueMasteryProgressionData data = progression();
        Table table = new Table(skin);
        table.defaults().left().pad(3).growX();

        SelectBox<String> mode = new DynamicSelectBox<>(skin);
        mode.setItems("Formula", "Benchmarks");
        mode.setSelected(TechniqueMasteryProgressionData.BENCHMARKS.equals(data.mode)
            ? "Benchmarks" : "Formula");
        mode.addListener(change(() -> {
            if ("Benchmarks".equals(mode.getSelected())) {
                data.mode = TechniqueMasteryProgressionData.BENCHMARKS;
                data.formula = null;
                data.benchmarks = new ArrayList<>();
                data.benchmarks.add(new TechniqueMasteryProgressionData.BenchmarkData(
                    0, literalValue.getAsInt()));
            } else {
                data.mode = TechniqueMasteryProgressionData.FORMULA;
                data.formula = String.valueOf(literalValue.getAsInt());
                data.benchmarks = null;
            }
            onDirty.run();
            rebuild();
        }));
        addRow(table, "Progression mode", mode);

        if (TechniqueMasteryProgressionData.BENCHMARKS.equals(data.mode)) {
            buildBenchmarks(table, data);
        } else {
            data.mode = TechniqueMasteryProgressionData.FORMULA;
            if (data.formula == null) data.formula = String.valueOf(literalValue.getAsInt());
            TextField formula = new HoverTextField(data.formula, skin);
            Label preview = previewLabel(data);
            formula.addListener(change(() -> {
                data.formula = formula.getText();
                preview.setText(previewText(data));
                onDirty.run();
            }));
            addRow(table, "Formula", formula);
            Label hint = new Label(
                "Use ctm, integers, + - * / %, parentheses, min, max, and clamp. Result floors to an integer.",
                skin, "small");
            hint.setColor(skin.get("text-dim", Color.class));
            hint.setWrap(true);
            table.add(hint).colspan(2).width(500f).growX().row();
            table.add(preview).colspan(2).width(500f).growX().row();
        }
        return table;
    }

    private void buildBenchmarks(Table table, TechniqueMasteryProgressionData data) {
        if (data.benchmarks == null || data.benchmarks.isEmpty()) {
            data.benchmarks = new ArrayList<>();
            data.benchmarks.add(new TechniqueMasteryProgressionData.BenchmarkData(
                0, literalValue.getAsInt()));
        }
        Label preview = previewLabel(data);
        for (int index = 0; index < data.benchmarks.size(); index++) {
            int rowIndex = index;
            TechniqueMasteryProgressionData.BenchmarkData benchmark = data.benchmarks.get(index);
            Table row = new Table(skin);
            TextField mastery = integerField(benchmark.mastery);
            mastery.setDisabled(index == 0);
            TextField value = integerField(benchmark.value);
            mastery.addListener(change(() -> {
                Integer parsed = parseInteger(mastery.getText());
                if (parsed != null) benchmark.mastery = parsed;
                preview.setText(previewText(data));
                onDirty.run();
            }));
            value.addListener(change(() -> {
                Integer parsed = parseInteger(value.getText());
                if (parsed != null) benchmark.value = parsed;
                preview.setText(previewText(data));
                onDirty.run();
            }));
            row.add(new Label("CTM", skin)).padRight(3);
            row.add(mastery).width(70).padRight(6);
            row.add(new Label("Value", skin)).padRight(3);
            row.add(value).width(90);
            if (index > 0) {
                TextButton remove = new TextButton("X", skin);
                remove.addListener(change(() -> {
                    data.benchmarks.remove(rowIndex);
                    onDirty.run();
                    rebuild();
                }));
                row.add(remove).padLeft(5);
            }
            table.add(row).colspan(2).growX().row();
        }
        TextButton add = new TextButton("+ Add benchmark", skin);
        add.setDisabled(data.benchmarks.get(data.benchmarks.size() - 1).mastery >= 300);
        add.addListener(change(() -> {
            if (add.isDisabled()) return;
            TechniqueMasteryProgressionData.BenchmarkData last =
                data.benchmarks.get(data.benchmarks.size() - 1);
            data.benchmarks.add(new TechniqueMasteryProgressionData.BenchmarkData(
                Math.min(300, last.mastery + 20), last.value));
            onDirty.run();
            rebuild();
        }));
        table.add(add).colspan(2).left().row();
        table.add(preview).colspan(2).width(500f).growX().row();
    }

    private Label previewLabel(TechniqueMasteryProgressionData data) {
        Label label = new Label(previewText(data), skin, "small");
        label.setWrap(true);
        return label;
    }

    static String previewText(TechniqueMasteryProgressionData data) {
        String error = data == null ? "Progression is missing." : data.validationError();
        if (error != null) return "Invalid progression: " + error;
        StringBuilder text = new StringBuilder("Preview: ");
        for (int mastery = 0; mastery <= 300; mastery += 20) {
            if (mastery > 0) text.append(" | ");
            text.append(mastery).append(": ").append(data.resolve(mastery));
        }
        return text.toString();
    }

    private TechniqueMasteryProgressionData progression() {
        Map<String, TechniqueMasteryProgressionData> values = getter.get();
        return values == null ? null : values.get(field);
    }

    private void put(TechniqueMasteryProgressionData progression) {
        Map<String, TechniqueMasteryProgressionData> values = getter.get();
        if (values == null) values = new LinkedHashMap<>();
        else values = new LinkedHashMap<>(values);
        values.put(field, progression);
        setter.accept(values);
    }

    private void removeProgression() {
        Map<String, TechniqueMasteryProgressionData> values = getter.get();
        if (values == null) return;
        values = new LinkedHashMap<>(values);
        values.remove(field);
        setter.accept(values.isEmpty() ? null : values);
    }

    private TextField integerField(int value) {
        TextField field = new HoverTextField(String.valueOf(value), skin);
        field.setTextFieldFilter((textField, character) ->
            Character.isDigit(character) || character == '-');
        return field;
    }

    private static Integer parseInteger(String value) {
        if (value == null || value.isBlank() || "-".equals(value)) return null;
        try { return Integer.valueOf(value); }
        catch (NumberFormatException ignored) { return null; }
    }

    private static void addRow(Table table, String label, Actor actor) {
        table.add(new Label(label, table.getSkin())).padRight(8);
        table.add(actor).growX().row();
    }

    private static ChangeListener change(Runnable action) {
        return new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                action.run();
            }
        };
    }
}
