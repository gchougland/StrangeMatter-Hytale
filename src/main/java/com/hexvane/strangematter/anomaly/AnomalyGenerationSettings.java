package com.hexvane.strangematter.anomaly;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.nio.file.*;
import java.io.IOException;
import com.google.gson.*;

/** Natural generation balance, with exact-name world/environment overrides. */
public final class AnomalyGenerationSettings {
    public static final int DEFAULT_RARITY=1000;
    public int generationBalanceRevision=1,defaultRarity=DEFAULT_RARITY;
    public double minimumSpacing=48;
    public boolean terrainPatches=true;
    public double resoniteColumnChance=.175, shardColumnChance=.10;
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
    public static AnomalyGenerationSettings load(Path directory)throws IOException {
        Files.createDirectories(directory);var file=directory.resolve("anomaly-generation.json");var gson=new GsonBuilder().setPrettyPrinting().create();
        JsonObject saved=Files.exists(file)?JsonParser.parseString(Files.readString(file)).getAsJsonObject():null;
        var settings=saved==null?new AnomalyGenerationSettings():gson.fromJson(saved,AnomalyGenerationSettings.class);
        if(settings==null)throw new IOException("Anomaly generation settings cannot be null");
        int revision=saved==null?1:saved.has("generationBalanceRevision")?saved.get("generationBalanceRevision").getAsInt():0;
        boolean migrate=saved!=null&&revision<1;
        if(migrate){
            if(settings.resoniteColumnChance==.25)settings.resoniteColumnChance=.175;
            // Only the complete six-entry shipped template is identifiable as old defaults.
            // A partial or edited map remains explicit server tuning, even at a value of500.
            boolean original=settings.typeRarity!=null&&settings.typeRarity.size()==AnomalyType.values().length;
            for(var type:AnomalyType.values())original&=settings.typeRarity!=null&&Integer.valueOf(500).equals(settings.typeRarity.get(type.name()));
            if(original)settings.typeRarity.clear();
            settings.generationBalanceRevision=1;
        }
        settings.validate();
        if(migrate){var backup=directory.resolve("anomaly-generation.before-balance-v1.json");if(!Files.exists(backup))Files.copy(file,backup);}
        if(saved==null||migrate){var next=file.resolveSibling(file.getFileName()+".tmp");Files.writeString(next,gson.toJson(settings)+"\n");try{Files.move(next,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException ignored){Files.move(next,file,StandardCopyOption.REPLACE_EXISTING);}}
        return settings;
    }
    private void validate()throws IOException {
        if(defaultRarity<0||!Double.isFinite(minimumSpacing)||minimumSpacing<0||minimumSpacing>256||!chance(resoniteColumnChance)||!chance(shardColumnChance))throw new IOException("Invalid anomaly spacing, rarity or ore probability");
        if(excludedWorlds==null||excludedWorlds.stream().anyMatch(java.util.Objects::isNull)||typeRarity==null||worldRarity==null||environmentRarity==null)throw new IOException("Anomaly override collections cannot be null");
        validateMap(typeRarity);for(var map:worldRarity.values())validateMap(map);for(var map:environmentRarity.values())validateMap(map);
    }
    private static boolean chance(double chance){return Double.isFinite(chance)&&chance>=0&&chance<=1;}
    private static void validateMap(Map<String,Integer> values)throws IOException {if(values==null)throw new IOException("Anomaly override map cannot be null");for(var e:values.entrySet())if(e.getKey()==null||e.getValue()==null||e.getValue()<0)throw new IOException("Anomaly rarities must be nonnegative integers");}
}
