package com.jjktbf.graphics.ui.editor;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.jjktbf.model.move.MoveTag;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A grid of toggle checkboxes for {@link MoveTag}s.
 *
 * Tags are the canonical representation of a move's nature. Every toggle fires
 * {@code onChange} with the current tag set so the caller can refresh dependent
 * UI (e.g. show/hide the base-power field).
 *
 * Coupling rule enforced in the UI: whenever INNATE_TECHNIQUE or
 * NON_INNATE_TECHNIQUE is selected, CURSED_ENERGY is force-selected and LOCKED.
 * While locked the CE checkbox:
 *   1. shows a light-grey fill (visually distinct from a manual tick),
 *   2. becomes unclickable (its state can't be changed by the user), and
 *   3. loses hover-highlight (it's not interactive).
 * All three effects revert the instant neither technique tag is selected, and
 * CE then behaves exactly like any other tag (manual toggle + hover highlight).
 */
public class TagPicker extends Table {

    /** Tags that, if any is selected, force CURSED_ENERGY on and lock it. */
    private static final Set<MoveTag> TECHNIQUE_TAGS = Set.of(
        MoveTag.INNATE_TECHNIQUE, MoveTag.NON_INNATE_TECHNIQUE);

    private final Set<MoveTag> selected = new LinkedHashSet<>();
    private final Consumer<Set<MoveTag>> onChange;
    private final Skin skin;

    /** Per-tag checkbox, so the lock logic can toggle individual ones. */
    private final Map<MoveTag, CheckBox> checkboxes = new java.util.EnumMap<>(MoveTag.class);

    /** Snapshot of the normal checkbox drawables, to restore after unlocking. */
    private Drawable ceNormalOn;
    private Drawable ceNormalOff;

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
                    if (cb.isChecked()) selected.add(tag);
                    else                selected.remove(tag);
                    enforceCoupling();
                    applyLocks();
                    if (onChange != null) onChange.accept(new LinkedHashSet<>(selected));
                }
            });
            add(cb).left();
            if (++col >= perRow) { row(); col = 0; }
        }
        if (col != 0) row();

        // The CE checkbox needs its OWN style instance so swapping drawables to
        // show the locked state doesn't affect every other checkbox in the skin.
        // Clone the default CheckBox style and attach it to CE only.
        CheckBox ce = checkboxes.get(MoveTag.CURSED_ENERGY);
        if (ce != null) {
            CheckBox.CheckBoxStyle ceStyle = new CheckBox.CheckBoxStyle(ce.getStyle());
            ce.setStyle(ceStyle);
            ceNormalOn  = ceStyle.checkboxOn;
            ceNormalOff = ceStyle.checkboxOff;
        }

        // Apply the coupling rule to the initial selection, then the locks.
        enforceCoupling();
        applyLocks();
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
     * Toggle the locked state of the CURSED_ENERGY checkbox.
     *
     * Locked  : light-grey fill, disabled (unclickable), no hover highlight,
     *           grey text, and force-checked so it can't drift from the
     *           enforced state.
     * Unlocked: normal drawables, enabled, navy text + yellow hover — behaves
     *           like every other tag.
     *
     * Everything is driven through the cloned CheckBoxStyle's colour fields
     * (fontColor / overFontColor / disabledFontColor). We deliberately do NOT
     * touch the label actor's own colour, because that hard override would mask
     * the hover highlight.
     */
    private void applyLocks() {
        boolean locked = selected.stream().anyMatch(TECHNIQUE_TAGS::contains);
        CheckBox ce = checkboxes.get(MoveTag.CURSED_ENERGY);
        if (ce == null) return;

        CheckBox.CheckBoxStyle style = ce.getStyle();
        Color normalColor = skin.get("text-dark", Color.class);
        Color hoverColor  = skin.get("text-hover", Color.class);
        Color lockedColor = skin.get("text-dim", Color.class);
        Drawable lockedDrawable = skin.getDrawable("check-locked");

        if (locked) {
            // Swap to the locked drawables + grey text + disable input.
            // overFontColor == fontColor so a stray hover can't recolour it.
            style.checkboxOn           = lockedDrawable;
            style.checkboxOff          = lockedDrawable;
            style.checkboxOnOver       = lockedDrawable;
            style.checkboxOver         = lockedDrawable;
            style.checkboxOnDisabled   = lockedDrawable;
            style.checkboxOffDisabled  = lockedDrawable;
            style.fontColor        = lockedColor;
            style.overFontColor    = lockedColor;   // no hover highlight when locked
            style.disabledFontColor= lockedColor;
            ce.setDisabled(true);
            ce.setChecked(true);
        } else {
            // Restore normal behaviour: navy text, yellow hover.
            style.checkboxOn           = ceNormalOn;
            style.checkboxOff          = ceNormalOff;
            style.checkboxOnOver       = ceNormalOn;
            style.checkboxOver         = ceNormalOff;
            style.checkboxOnDisabled   = ceNormalOn;
            style.checkboxOffDisabled  = ceNormalOff;
            style.fontColor        = normalColor;
            style.overFontColor    = hoverColor;
            style.disabledFontColor= normalColor;
            ce.setDisabled(false);
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
