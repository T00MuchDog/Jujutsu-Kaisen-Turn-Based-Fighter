package com.jjktbf.graphics.ui.editor;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;

/** A text field that shows the editor's yellow action border while hovered. */
public class HoverTextField extends TextField {

    private final TextFieldStyle normalStyle;
    private final TextFieldStyle hoverStyle;

    public HoverTextField(String text, Skin skin) {
        super(text, skin);
        normalStyle = new TextFieldStyle(getStyle());
        hoverStyle = new TextFieldStyle(normalStyle);
        hoverStyle.background = skin.getDrawable("textfield-over");
        hoverStyle.focusedBackground = skin.getDrawable("textfield-over");

        addListener(new InputListener() {
            @Override
            public void enter(InputEvent event, float x, float y, int pointer, Actor fromActor) {
                if (!isDisabled()) setStyle(hoverStyle);
            }

            @Override
            public void exit(InputEvent event, float x, float y, int pointer, Actor toActor) {
                setStyle(normalStyle);
            }
        });
    }
}
