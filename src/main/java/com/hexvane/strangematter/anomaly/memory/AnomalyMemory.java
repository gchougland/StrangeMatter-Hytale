package com.hexvane.strangematter.anomaly.memory;

import com.hexvane.strangematter.anomaly.AnomalyType;
import com.hypixel.hytale.builtin.adventure.memories.memories.Memory;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.EnumCodec;
import com.hypixel.hytale.codec.validation.Validators;
import com.hypixel.hytale.server.core.Message;

import java.util.Objects;

/** One native memory per phenomenon, independent of the field's identity or location. */
public final class AnomalyMemory extends Memory {
    public static final String ID = "SM_Anomaly";
    public static final BuilderCodec<AnomalyMemory> CODEC = BuilderCodec.builder(AnomalyMemory.class, AnomalyMemory::new)
            .append(new KeyedCodec<>("AnomalyType", new EnumCodec<>(AnomalyType.class)),
                    (memory, type) -> memory.type = type, memory -> memory.type)
            .addValidator(Validators.nonNull())
            .add()
            .build();

    // Only the native codec writes this field; collected and catalog entries are immutable.
    private AnomalyType type;

    private AnomalyMemory() {}

    public AnomalyMemory(AnomalyType type) {
        this.type = Objects.requireNonNull(type, "type");
    }

    public AnomalyType type() { return type; }

    @Override public String getId() { return ID + "_" + type.name(); }

    @Override public String getTitle() { return "server.memories.entries." + getId() + ".title"; }

    @Override public Message getDescription() {
        return Message.translation("server.memories.entries." + getId() + ".description");
    }

    @Override public Message getTooltipText() {
        return Message.translation("server.memories.general.discovered.tooltipText");
    }

    @Override public Message getUndiscoveredTooltipText() {
        return Message.translation("server.memories.general.undiscovered.tooltipText");
    }

    @Override public String getIconPath() {
        String suffix = switch (type) {
            case GRAVITY -> "Gravity";
            case TEMPORAL_BLOOM -> "Temporal_Bloom";
            case ENERGETIC_RIFT -> "Energetic_Rift";
            case WARP_GATE -> "Warp_Gate";
            case ECHOING_SHADOW -> "Echoing_Shadow";
            case THOUGHTWELL -> "Thoughtwell";
        };
        return "Icons/ItemsGenerated/SM_Anomaly_" + suffix + ".png";
    }

    @Override public boolean equals(Object other) {
        return other instanceof AnomalyMemory memory && type == memory.type;
    }

    @Override public int hashCode() { return 31 * AnomalyMemory.class.hashCode() + Objects.hashCode(type); }

    @Override public String toString() { return "AnomalyMemory{" + type + "}"; }
}
