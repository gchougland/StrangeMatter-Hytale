package com.hexvane.strangematter.anomaly.memory;

import com.hexvane.strangematter.anomaly.AnomalyType;
import com.hypixel.hytale.builtin.adventure.memories.memories.Memory;
import com.hypixel.hytale.builtin.adventure.memories.memories.MemoryProvider;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Supplies the native bench catalog and its native configurable encounter radius. */
public final class AnomalyMemoryProvider extends MemoryProvider<AnomalyMemory> {
    public static final String CATEGORY = "SM_Anomalies";
    public static final double DEFAULT_COLLECTION_RADIUS = 10.0;
    private final Map<String, Set<Memory>> catalog;

    public AnomalyMemoryProvider() {
        super(AnomalyMemory.ID, AnomalyMemory.CODEC, DEFAULT_COLLECTION_RADIUS);
        Set<Memory> entries = new LinkedHashSet<>();
        for (AnomalyType type : AnomalyType.values()) entries.add(new AnomalyMemory(type));
        catalog = Map.of(CATEGORY, Collections.unmodifiableSet(entries));
    }

    @Override public Map<String, Set<Memory>> getAllMemories() { return catalog; }
}
