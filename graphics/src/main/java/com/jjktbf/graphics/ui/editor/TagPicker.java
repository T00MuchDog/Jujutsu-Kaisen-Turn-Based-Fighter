package com.jjktbf.graphics.ui.editor;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.jjktbf.graphics.audio.SoundCue;
import com.jjktbf.model.move.MoveTag;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A grid of toggle checkboxes for {@link MoveTag}s, grouped into ordered sections.
 *
 * Tags are the canonical representation of a move's nature. Every toggle fires
 * {@code onChange} with the current tag set so the caller can refresh dependent
 * UI (e.g. show/hide the base-power field).
 *
 * <p>Tags are rendered in four ordered sections, matching how the editor reasons
 * about a move:
 * <ol>
 *   <li><b>CATEGORY</b> — Attack, Defensive, Utility</li>
 *   <li><b>TYPE</b> — Physical, Cursed Energy, Innate Technique,
 *       Non-Innate Technique</li>
 *   <li><b>RANGE</b> — Melee, Ranged, AOE, Friendly Fire</li>
 *   <li><b>TAGS</b> — Sword, Stun, Guard Break, Heavy, Intangible</li>
 * </ol>
 *
 * <p>Two coupling rules are enforced in the UI:
 * <ul>
 *   <li>Whenever INNATE_TECHNIQUE or NON_INNATE_TECHNIQUE is selected,
 *       CURSED_ENERGY is force-selected and LOCKED (grey fill, unclickable,
 *       no hover highlight).</li>
 *   <li>MELEE, RANGED, and AOE are LOCKED OFF unless ATTACK is selected.</li>
 *   <li>FRIENDLY_FIRE is LOCKED OFF unless AOE is selected.</li>
 * </ul>
 * The rules revert the instant their gating condition no longer holds.
 */
public class TagPicker extends Table {

    /** Tags that, if any is selected, force CURSED_ENERGY on and lock it. */
    private static final Set<MoveTag> TECHNIQUE_TAGS = Set.of(
        MoveTag.INNATE_TECHNIQUE, MoveTag.NON_INNATE_TECHNIQUE);

    /** Ordered tag sections, rendered top-to-bottom with a sub-label each. */
    private static final Map<String, List<MoveTag>> SECTIONS = sectionOrder();

    private final Set<MoveTag> selected = new LinkedHashSet<>();
    private final Consumer<Set<MoveTag>> onChange;
    private final Consumer<SoundCue> soundPlayer;
    private final Skin skin;

    /** Per-tag checkbox, so the lock logic can toggle individual ones. */
    private final Map<MoveTag, CheckBox> checkboxes = new java.util.EnumMap<>(MoveTag.class);
    /**
     * Snapshot of each lockable tag's normal checkbox drawables, to restore
     * after unlocking. Populated lazily for tags that get a cloned style.
     */
    private final Map<MoveTag, Drawable[]> normalDrawables = new LinkedHashMap<>();

    public TagPicker(
        Set<MoveTag> initial,
        Consumer<Set<MoveTag>> onChange,
        Consumer<SoundCue> soundPlayer,
        Skin skin
    ) {
        super(skin);
        this.skin = skin;
        this.onChange = onChange;
        this.soundPlayer = soundPlayer == null ? cue -> { } : soundPlayer;
        if (initial != null) this.selected.addAll(initial);

        // No internal "Tags" heading — the form section strip already names it.
        defaults().pad(4);

        int perRow = 3;
        for (Map.Entry<String, List<MoveTag>> section : SECTIONS.entrySet()) {
            // Section sub-label, matching the small-caps style used elsewhere.
            Label heading = new Label(section.getKey(), skin, "small");
            heading.setColor(skin.get("text-dim", Color.class));
            add(heading).left().padTop(6f).row();

            int col = 0;
            for (MoveTag tag : section.getValue()) {
                CheckBox cb = new CheckBox(pretty(tag.name()), skin);
                cb.setProgrammaticChangeEvents(false);
                cb.setChecked(selected.contains(tag));
                checkboxes.put(tag, cb);
                cb.addListener(new ChangeListener() {
                    @Override public void changed(ChangeEvent event, Actor actor) {
                        if (cb.isChecked()) selected.add(tag);
                        else                selected.remove(tag);
                        enforceCoupling();
                        enforceTargetingRules(selected);
                        applyLocks();
                        TagPicker.this.soundPlayer.accept(SoundCue.UI_TOGGLE);
                        if (onChange != null) onChange.accept(new LinkedHashSet<>(selected));
                    }
                });
                add(cb).left();
                if (++col >= perRow) { row(); col = 0; }
            }
            if (col != 0) row();
        }

        // Lockable checkboxes each need their OWN style
        // instance so swapping drawables to show a locked state doesn't affect
        // every other checkbox sharing the skin style. Clone the default style
        // and attach it to each lockable tag only.
        cloneStyleFor(MoveTag.CURSED_ENERGY);
        cloneStyleFor(MoveTag.MELEE);
        cloneStyleFor(MoveTag.RANGED);
        cloneStyleFor(MoveTag.AOE);
        cloneStyleFor(MoveTag.FRIENDLY_FIRE);

        // Apply the coupling rules to the initial selection, then the locks.
        enforceCoupling();
        enforceTargetingRules(selected);
        applyLocks();
    }

    /** Clone the default CheckBox style onto one tag and snapshot its drawables. */
    private void cloneStyleFor(MoveTag tag) {
        CheckBox cb = checkboxes.get(tag);
        if (cb == null) return;
        CheckBox.CheckBoxStyle style = new CheckBox.CheckBoxStyle(cb.getStyle());
        cb.setStyle(style);
        normalDrawables.put(tag, new Drawable[] { style.checkboxOn, style.checkboxOff });
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

    /** Normalize attack targeting tags and their friendly-fire dependency. */
    static void enforceTargetingRules(Set<MoveTag> tags) {
        if (tags == null) return;
        if (!tags.contains(MoveTag.ATTACK)) {
            tags.remove(MoveTag.MELEE);
            tags.remove(MoveTag.RANGED);
            tags.remove(MoveTag.AOE);
        }
        if (!tags.contains(MoveTag.AOE)) tags.remove(MoveTag.FRIENDLY_FIRE);
    }

    /**
     * Toggle the locked state of the coupling-driven checkboxes.
     *
     * <p>Two lock flavours, both driven through the cloned CheckBoxStyle's
     * colour fields (fontColor / overFontColor / disabledFontColor). We
     * deliberately do NOT touch the label actor's own colour, because that hard
     * override would mask the hover highlight.
     * <ul>
     *   <li>CE: <b>locked ON</b> when a technique tag is selected — light-grey
     *       fill, disabled (unclickable), no hover highlight, force-checked so
     *       it can't drift from the enforced state.</li>
     *   <li>Attack targeting: <b>locked OFF</b> unless ATTACK is selected.</li>
     *   <li>Friendly Fire: <b>locked OFF</b> unless AOE is selected.</li>
     * </ul>
     * Unlocked tags behave like every other tag (normal drawables, enabled,
     * navy text + yellow hover).
     */
    private void applyLocks() {
        boolean attackSelected = selected.contains(MoveTag.ATTACK);
        applyLockOn(MoveTag.CURSED_ENERGY,
            selected.stream().anyMatch(TECHNIQUE_TAGS::contains));
        applyLockOff(MoveTag.MELEE, !attackSelected);
        applyLockOff(MoveTag.RANGED, !attackSelected);
        applyLockOff(MoveTag.AOE, !attackSelected);
        applyLockOff(MoveTag.FRIENDLY_FIRE, !selected.contains(MoveTag.AOE));
    }

    /** Lock a checkbox ON (forced checked, unclickable, grey fill + text). */
    private void applyLockOn(MoveTag tag, boolean locked) {
        CheckBox cb = checkboxes.get(tag);
        if (cb == null) return;
        CheckBox.CheckBoxStyle style = cb.getStyle();
        Drawable[] normal = normalDrawables.get(tag);
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
            style.overFontColor    = lockedColor;
            style.disabledFontColor= lockedColor;
            cb.setDisabled(true);
            cb.setChecked(true);
        } else if (normal != null) {
            // Restore normal behaviour: navy text, yellow hover.
            style.checkboxOn           = normal[0];
            style.checkboxOff          = normal[1];
            style.checkboxOnOver       = normal[0];
            style.checkboxOver         = normal[1];
            style.checkboxOnDisabled   = normal[0];
            style.checkboxOffDisabled  = normal[1];
            style.fontColor        = normalColor;
            style.overFontColor    = hoverColor;
            style.disabledFontColor= normalColor;
            cb.setDisabled(false);
        }
    }

    /** Lock a checkbox OFF (forced unchecked, unclickable, grey text). */
    private void applyLockOff(MoveTag tag, boolean locked) {
        CheckBox cb = checkboxes.get(tag);
        if (cb == null) return;
        CheckBox.CheckBoxStyle style = cb.getStyle();
        Drawable[] normal = normalDrawables.get(tag);
        Color normalColor = skin.get("text-dark", Color.class);
        Color hoverColor  = skin.get("text-hover", Color.class);
        Color lockedColor = skin.get("text-dim", Color.class);

        if (locked) {
            // Grey text + disable input; leave the (off) drawables alone so the
            // empty checkbox just reads as a disabled control.
            style.fontColor        = lockedColor;
            style.overFontColor    = lockedColor;   // no hover highlight when locked
            style.disabledFontColor= lockedColor;
            cb.setDisabled(true);
            cb.setChecked(false);
        } else if (normal != null) {
            // Restore normal behaviour: navy text, yellow hover.
            style.checkboxOn           = normal[0];
            style.checkboxOff          = normal[1];
            style.checkboxOnOver       = normal[0];
            style.checkboxOver         = normal[1];
            style.checkboxOnDisabled   = normal[0];
            style.checkboxOffDisabled  = normal[1];
            style.fontColor        = normalColor;
            style.overFontColor    = hoverColor;
            style.disabledFontColor= normalColor;
            cb.setDisabled(false);
        }
    }

    public Set<MoveTag> getSelected() {
        return new LinkedHashSet<>(selected);
    }

    /** The ordered tag sections rendered top-to-bottom. */
    private static Map<String, List<MoveTag>> sectionOrder() {
        Map<String, List<MoveTag>> sections = new LinkedHashMap<>();
        sections.put("CATEGORY", List.of(MoveTag.ATTACK, MoveTag.DEFENSIVE, MoveTag.UTILITY));
        sections.put("TYPE", List.of(
            MoveTag.PHYSICAL, MoveTag.CURSED_ENERGY,
            MoveTag.INNATE_TECHNIQUE, MoveTag.NON_INNATE_TECHNIQUE));
        sections.put("RANGE", List.of(
            MoveTag.MELEE, MoveTag.RANGED, MoveTag.AOE, MoveTag.FRIENDLY_FIRE));
        sections.put("TAGS", List.of(
            MoveTag.SWORD, MoveTag.GUARD_BREAK, MoveTag.HEAVY,
            MoveTag.INTANGIBLE));
        return sections;
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
