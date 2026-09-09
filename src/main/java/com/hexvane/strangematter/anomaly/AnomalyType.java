package com.hexvane.strangematter.anomaly;

import java.util.Locale;

/** Original six research disciplines and their field defaults, in blocks and seconds. */
public enum AnomalyType {
    GRAVITY("Gravity Anomaly", "GRAVITY", "SM_Gravity_Anomaly", "Gravity", 8),
    TEMPORAL_BLOOM("Temporal Bloom", "TIME", "SM_Temporal_Bloom", "Temporal_Bloom", 8),
    ENERGETIC_RIFT("Energetic Rift", "ENERGY", "SM_Energetic_Rift", "Energetic", 6),
    WARP_GATE("Warp Gate", "SPACE", "SM_Warp_Gate", "Warp_Gate", 2),
    ECHOING_SHADOW("Echoing Shadow", "SHADOW", "SM_Echoing_Shadow", "Echoing_Shadow", 8),
    THOUGHTWELL("Thoughtwell", "COGNITION", "SM_Thoughtwell", "Thoughtwell", 6);

    public final String displayName, researchType, particleId, capsuleSuffix;
    public final double radius;
    AnomalyType(String name, String research, String particle, String capsule, double radius) {
        displayName = name; researchType = research; particleId = particle; capsuleSuffix = capsule; this.radius = radius;
    }
    public String capsuleItemId() { return "SM_Containment_Capsule_" + capsuleSuffix; }
    public static AnomalyType fromName(String name) {
        String normalized = name.toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        return switch (normalized) {
            case "GRAVITY_ANOMALY" -> GRAVITY;
            case "TIME", "TEMPORAL" -> TEMPORAL_BLOOM;
            case "ENERGY", "RIFT" -> ENERGETIC_RIFT;
            case "SPACE", "WARP" -> WARP_GATE;
            case "SHADOW" -> ECHOING_SHADOW;
            case "COGNITION" -> THOUGHTWELL;
            default -> valueOf(normalized);
        };
    }
}
