package com.jjktbf.graphics.launch;

import com.jjktbf.graphics.ui.profile.UiProfile;

import java.util.Locale;

/** Host-window policy. UI profile selection is resolved once and may be overridden. */
public enum DesktopPlatform {
    MAC,
    WINDOWS,
    OTHER;

    public static DesktopPlatform fromOsName(String osName) {
        String normalized = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (normalized.contains("mac") || normalized.contains("darwin")) return MAC;
        if (normalized.contains("win")) return WINDOWS;
        return OTHER;
    }

    public UiProfile defaultUiProfile() {
        return this == MAC ? UiProfile.MAC : UiProfile.WINDOWS;
    }
}
