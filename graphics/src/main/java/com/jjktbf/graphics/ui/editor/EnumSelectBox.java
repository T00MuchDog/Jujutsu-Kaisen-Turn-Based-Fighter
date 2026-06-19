package com.jjktbf.graphics.ui.editor;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;

import java.util.function.Consumer;

/**
 * Thin wrapper over {@link SelectBox} driven by an enum's values, with an
 * optional "include null" entry for nullable enum fields.
 *
 * Selected value is exposed as the enum constant name (the string form stored
 * in the DTOs). This keeps callers free of generics dance.
 */
public class EnumSelectBox<T extends Enum<T>> extends SelectBox<String> {

    private final Class<T> enumClass;
    private final boolean hasNull;
    private final Consumer<String> onChange;

    /**
     * @param enumClass  the enum to populate from
     * @param current    currently-selected value name, or null
     * @param includeNull  when true, a "[none]" entry is offered at the top
     * @param onChange   fires with the selected enum name (or null if "[none]" picked)
     */
    public EnumSelectBox(Class<T> enumClass, String current, boolean includeNull,
                         Consumer<String> onChange, Skin skin) {
        super(skin);
        this.enumClass = enumClass;
        this.hasNull   = includeNull;
        this.onChange  = onChange;

        T[] consts = enumClass.getEnumConstants();
        String[] items = new String[consts.length + (includeNull ? 1 : 0)];
        int i = 0;
        if (includeNull) items[i++] = "[none]";
        for (T c : consts) items[i++] = c.name();
        setItems(items);

        setSelected(prepareSelected(current));

        addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                String s = getSelected();
                String resolved = (hasNull && "[none]".equals(s)) ? null : s;
                if (onChange != null) onChange.accept(resolved);
            }
        });
    }

    /** Returns the selected value as a name, or null if "[none]". */
    public String selectedName() {
        String s = getSelected();
        return (hasNull && "[none]".equals(s)) ? null : s;
    }

    private String prepareSelected(String current) {
        if (current == null) {
            return hasNull ? "[none]" : enumClass.getEnumConstants()[0].name();
        }
        // Find a case-insensitive match against enum names.
        for (T c : enumClass.getEnumConstants()) {
            if (c.name().equalsIgnoreCase(current)) return c.name();
        }
        return hasNull ? "[none]" : enumClass.getEnumConstants()[0].name();
    }
}
