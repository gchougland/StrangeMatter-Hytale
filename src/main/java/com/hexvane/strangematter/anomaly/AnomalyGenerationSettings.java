package com.hexvane.strangematter.anomaly;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Optional exact-name world/environment overrides. Defaults retain the source mod's uniform 1/500 rarity. */
public final class AnomalyGenerationSettings {
    public boolean terrainPatches=true;
    public double resoniteColumnChance=.25, shardColumnChance=.10;
    public Set<String> excludedWorlds=Set.of();
    public Map<String,Integer> typeRarity=new HashMap<>();
    public Map<String,Map<String,Integer>> worldRarity=new HashMap<>();
    public Map<String,Map<String,Integer>> environmentRarity=new HashMap<>();
    public int rarity(String world,String environment,AnomalyType type,int fallback) {
        if(excludedWorlds.contains(world))return 0;
        int value=typeRarity.getOrDefault(type.name(),fallback);
        value=worldRarity.getOrDefault(world,Map.of()).getOrDefault(type.name(),value);
        return environmentRarity.getOrDefault(environment,Map.of()).getOrDefault(type.name(),value);
    }
}
