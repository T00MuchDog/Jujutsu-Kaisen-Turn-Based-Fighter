package com.jjktbf.graphics.ui.editor;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.jjktbf.model.move.MoveTag;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A grid of toggle checkboxes for {@link MoveTag}s, with a live read-out of the
 * derived {@link com.jjktbf.model.move.MoveCategory MoveCategory}.
 *
 * Tags are the canonical representation — the category is computed from them.
 * Every toggle fires {@code onChange} with the current tag set so the caller can
 * refresh dependent UI (e.g. show/hide the base-power field).
 */
public class TagPicker extends Table {

    private final Set<MoveTag> selected = new LinkedHashSet<>();
    private final Consumer<Set<MoveTag>> onChange;
    private final Label categoryLabel;

    public TagPicker(Set<MoveTag> initial, Consumer<Set<MoveTag>> onChange, Skin skin) {
        super(skin);
        this.onChange = onChange;
        if (initial != null) this.selected.addAll(initial);

        defaults().pad(4);
        add(new Label("Tags", skin)).left().colspan(99).row();

        int perRow = 3;
        int col = 0;
        for (MoveTag tag : MoveTag.values()) {
            CheckBox cb = new CheckBox(pretty(tag.name()), skin);
            cb.setChecked(selected.contains(tag));
            cb.addListener(new ChangeListener() {
                @Override public void changed(ChangeEvent event, Actor actor) {
                    if (cb.isChecked()) selected.add(tag);
                    else                selected.remove(tag);
                    refreshCategory();
                    if (onChange != null) onChange.accept(new LinkedHashSet<>(selected));
                }
            });
            add(cb).left();
            if (++col >= perRow) { row(); col = 0; }
        }
        if (col != 0) row();

        // Derived category read-out
        Table catRow = new Table(skin);
        catRow.add(new Label("Category:", skin)).left().padRight(8);
        categoryLabel = new Label("", skin, "small");
        categoryLabel.setColor(skin.get("text-dim", com.badlogic.gdx.graphics.Color.class));
        catRow.add(categoryLabel).left();
        add(catRow).left().colspan(99).padTop(4).row();

        refreshCategory();
    }

    private void refreshCategory() {
        // Reuse MoveData's derivation by building a transient tags list.
        com.jjktbf.model.move.MoveData md = new com.jjktbf.model.move.MoveData();
        md.tags = selected.stream().map(MoveTag::name).toList();
        try {
            categoryLabel.setText(md.derivedCategory().name());
        } catch (Exception e) {
            categoryLabel.setText("(invalid combination)");
        }
    }

    public Set<MoveTag> getSelected() {
        return new LinkedHashSet<>(selected);
    }

    private static String pretty(String enumName) {
        String[] parts = enumName.toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }
}
