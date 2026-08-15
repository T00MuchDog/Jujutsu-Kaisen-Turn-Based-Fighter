package com.jjktbf.graphics.launch;

import com.jjktbf.graphics.ui.profile.UiProfile;

import java.util.Properties;

/** Centralized parsing for host defaults and development overrides. */
public record DesktopLaunchOptions(
    DesktopPlatform hostPlatform,
    UiProfile uiProfile,
    boolean windowed,
    int windowWidth,
    int windowHeight
) {
    public static final String UI_PROFILE_PROPERTY = "jjktbf.ui.profile";
    public static final String WINDOWED_PROPERTY = "jjktbf.windowed";
    public static final String WINDOW_WIDTH_PROPERTY = "jjktbf.window.width";
    public static final String WINDOW_HEIGHT_PROPERTY = "jjktbf.window.height";

    public static DesktopLaunchOptions parse(String[] arguments) {
        return parse(arguments, System.getProperties(),
            DesktopPlatform.fromOsName(System.getProperty("os.name")));
    }

    static DesktopLaunchOptions parse(
        String[] arguments,
        Properties properties,
        DesktopPlatform platform
    ) {
        String configuredProfile = properties.getProperty(UI_PROFILE_PROPERTY);
        UiProfile profile = configuredProfile == null || configuredProfile.isBlank()
            ? platform.defaultUiProfile()
            : UiProfile.parse(configuredProfile);
        boolean windowed = Boolean.parseBoolean(properties.getProperty(WINDOWED_PROPERTY));
        Integer width = integerProperty(properties, WINDOW_WIDTH_PROPERTY);
        Integer height = integerProperty(properties, WINDOW_HEIGHT_PROPERTY);

        String[] args = arguments == null ? new String[0] : arguments;
        for (int index = 0; index < args.length; index++) {
            String argument = args[index];
            if ("--windowed".equals(argument)) {
                windowed = true;
            } else if ("--fullscreen".equals(argument)) {
                windowed = false;
            } else if (argument.startsWith("--ui-profile=")) {
                profile = UiProfile.parse(argument.substring("--ui-profile=".length()));
            } else if ("--ui-profile".equals(argument)) {
                profile = UiProfile.parse(requireValue(args, ++index, argument));
            } else if (argument.startsWith("--width=")) {
                width = parseInteger("width", argument.substring("--width=".length()));
                windowed = true;
            } else if ("--width".equals(argument)) {
                width = parseInteger("width", requireValue(args, ++index, argument));
                windowed = true;
            } else if (argument.startsWith("--height=")) {
                height = parseInteger("height", argument.substring("--height=".length()));
                windowed = true;
            } else if ("--height".equals(argument)) {
                height = parseInteger("height", requireValue(args, ++index, argument));
                windowed = true;
            } else {
                throw new IllegalArgumentException("Unknown launch option: " + argument);
            }
        }

        int defaultWidth = 1280;
        int defaultHeight = 720;
        int resolvedWidth = width == null ? defaultWidth : width;
        int resolvedHeight = height == null ? defaultHeight : height;
        validateDimension("width", resolvedWidth, 640, 7680);
        validateDimension("height", resolvedHeight, 480, 4320);
        return new DesktopLaunchOptions(
            platform, profile, windowed, resolvedWidth, resolvedHeight);
    }

    private static Integer integerProperty(Properties properties, String name) {
        String value = properties.getProperty(name);
        return value == null || value.isBlank() ? null : parseInteger(name, value);
    }

    private static int parseInteger(String name, String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(name + " must be an integer", invalid);
        }
    }

    private static String requireValue(String[] arguments, int index, String option) {
        if (index >= arguments.length) {
            throw new IllegalArgumentException(option + " requires a value");
        }
        return arguments[index];
    }

    private static void validateDimension(String name, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                name + " must be between " + minimum + " and " + maximum);
        }
    }
}
