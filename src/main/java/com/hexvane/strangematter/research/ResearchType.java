package com.hexvane.strangematter.research;

import java.util.Locale;

/** The six original Strange Matter research disciplines. */
public enum ResearchType {
    COGNITION("Cognition", "#bc8fff"), ENERGY("Energy", "#39e9ff"),
    GRAVITY("Gravity", "#a67eff"), SHADOW("Shadow", "#bd73de"),
    SPACE("Space", "#55daec"), TIME("Time", "#efd283");

    private final String displayName;
    private final String color;
    ResearchType(String displayName, String color) { this.displayName = displayName; this.color = color; }
    public String getName() { return name().toLowerCase(Locale.ROOT); }
    public String displayName() { return displayName; }
    public String color() { return color; }
    public static ResearchType fromName(String value) {
        if (value == null) return null;
        try { return valueOf(value.toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException ignored) { return null; }
    }
}
