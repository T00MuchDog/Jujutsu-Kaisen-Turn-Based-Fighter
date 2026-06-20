package com.jjktbf.graphics.ui.editor;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.jjktbf.model.move.MoveTag;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A grid of toggle checkboxes for {@link MoveTag}s, with a live read-out of the
 * derived {@link com.jjktbf.model.move.MoveCategory MoveCategory}.
 *
 * Tags are the canonical representation — the category is computed from them.
 * Every toggle fires {@code onChange} with the current tag set so the caller can
 * refresh dependent UI (e.g. show/hide the base-power field).
 *
 * Coupling rule enforced in the UI: whenever INNATE_TECHNIQUE or
 * NON_INNATE_TECHNIQUE is selected, CURSED_ENERGY is force-selected and locked
 * (greyed out + unclickable). It unlocks only when neither technique tag is on.
 * This mirrors the engine invariant that technique moves imply cursed-energy use
 * (see {@link MoveTag}).
 */
public class TagPicker extends Table {

    /** Tags that, if any is selected, force CURSED_ENERGY on and lock it. */
    private static final Set<MoveTag> TECHNIQUE_TAGS = Set.of(
        MoveTag.INNATE_TECHNIQUE, MoveTag.NON_INNATE_TECHNIQUE);

    private final Set<MoveTag> selected = new LinkedHashSet<>();
    private final Consumer<Set<MoveTag>> onChange;
    private final Label categoryLabel;
    private final Skin skin;

    /** Per-tag checkbox, so the lock logic can toggle individual ones. */
    private final Map<MoveTag, CheckBox> checkboxes = new java.util.EnumMap<>(MoveTag.class);

    public TagPicker(Set<MoveTag> initial, Consumer<Set<MoveTag>> onChange, Skin skin) {
        super(skin);
        this.skin = skin;
        this.onChange = onChange;
        if (initial != null) this.selected.addAll(initial);

        defaults().pad(4);
        add(new Label("Tags", skin)).left().colspan(99).row();

        int perRow = 3;
        int col = 0;
        for (MoveTag tag : MoveTag.values()) {
            CheckBox cb = new CheckBox(pretty(tag.name()), skin);
            cb.setChecked(selected.contains(tag));
            checkboxes.put(tag, cb);
            cb.addListener(new ChangeListener() {
                @Override public void changed(ChangeEvent event, Actor actor) {
                    // Apply the user's toggle intent.
                    if (cb.isChecked()) selected.add(tag);
                    else                selected.remove(tag);
                    enforceCoupling();
                    applyLocks();
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
        categoryLabel.setColor(skin.get("text-dim", Color.class));
        catRow.add(categoryLabel).left();
        add(catRow).left().colspan(99).padTop(4).row();

        // Apply the coupling rule to the initial selection, then the locks.
        enforceCoupling();
        applyLocks();
        refreshCategory();
    }

    /**
     * If any technique tag is selected, force CURSED_ENERGY on. Otherwise leave
     * CE alone (the user may toggle it freely).
     */
    private void enforceCoupling() {
        boolean anyTechnique = selected.stream().anyMatch(TECHNIQUE_TAGS::contains);
        if (anyTechnique) {
            selected.add(MoveTag.CURSED_ENERGY);
        }
    }

    /**
     * Enable/disable + grey the CURSED_ENERGY checkbox based on whether a
     * technique tag is selected. A locked checkbox is also re-checked to ensure
     * it can't drift from the enforced state.
     */
    private void applyLocks() {
        boolean locked = selected.stream().anyMatch(TECHNIQUE_TAGS::contains);
        CheckBox ce = checkboxes.get(MoveTag.CURSED_ENERGY);
        if (ce == null) return;
        ce.setDisabled(locked);
        Color lockedColor   = skin.get("text-dim", Color.class);
        Color normalColor   = skin.get("text-dark", Color.class);
        ce.getLabel().setColor(locked ? lockedColor : normalColor);
        if (locked) {
            // Keep the box visually checked while locked.
            ce.setChecked(true);
        }
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
