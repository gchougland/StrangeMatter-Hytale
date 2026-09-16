package com.hexvane.strangematter.anomaly;

import java.nio.file.Files;
import java.util.Map;
import com.google.gson.Gson;

final class AnomalyGenerationBalanceVerification {
    static void verify()throws Exception {
        var directory=Files.createTempDirectory("sm-anomaly-balance-");var file=directory.resolve("anomaly-generation.json");
        var settings=AnomalyGenerationSettings.load(directory);
        require(Files.exists(file)&&settings.defaultRarity==1000&&settings.minimumSpacing==48&&settings.resoniteColumnChance==.175&&settings.shardColumnChance==.10,"New install persists the balanced natural generation defaults");
        var legacy=new com.google.gson.JsonObject();legacy.addProperty("resoniteColumnChance",.25);legacy.addProperty("shardColumnChance",.1);
        var types=new com.google.gson.JsonObject();for(var type:AnomalyType.values())types.addProperty(type.name(),500);legacy.add("typeRarity",types);
        legacy.add("worldRarity",new Gson().toJsonTree(Map.of("custom-world",Map.of("GRAVITY",500))));
        legacy.add("environmentRarity",new Gson().toJsonTree(Map.of("Zone2",Map.of("THOUGHTWELL",125))));
        String original=legacy.toString();Files.writeString(file,original);settings=AnomalyGenerationSettings.load(directory);
        require(settings.typeRarity.isEmpty()&&settings.defaultRarity==1000&&settings.resoniteColumnChance==.175,"Complete shipped legacy defaults adopt new balance");
        require(settings.rarity("custom-world","Zone1",AnomalyType.GRAVITY,settings.defaultRarity)==500&&settings.rarity("default","Zone2",AnomalyType.THOUGHTWELL,settings.defaultRarity)==125,"Explicit world and environment rarity remain exact");
        require(Files.readString(directory.resolve("anomaly-generation.before-balance-v1.json")).equals(original),"Migration retains exact original configuration backup");
        Files.writeString(file,"{\"resoniteColumnChance\":0.4,\"defaultRarity\":700,\"minimumSpacing\":32,\"typeRarity\":{\"GRAVITY\":500,\"ENERGETIC_RIFT\":125}}");
        settings=AnomalyGenerationSettings.load(directory);
        require(settings.resoniteColumnChance==.4&&settings.defaultRarity==700&&settings.minimumSpacing==32&&settings.typeRarity.equals(Map.of("GRAVITY",500,"ENERGETIC_RIFT",125)),"Custom density and partial rarity maps survive migration, including intentional500");
        String tuned="{\"generationBalanceRevision\":1,\"defaultRarity\":500,\"resoniteColumnChance\":0.25}";Files.writeString(file,tuned);
        settings=AnomalyGenerationSettings.load(directory);require(settings.defaultRarity==500&&settings.resoniteColumnChance==.25&&Files.readString(file).equals(tuned),"Revision-marked custom tuning is never rewritten");
        String invalid="{\"resoniteColumnChance\":2}";Files.writeString(file,invalid);boolean rejected=false;
        try{AnomalyGenerationSettings.load(directory);}catch(java.io.IOException expected){rejected=true;}
        require(rejected&&Files.readString(file).equals(invalid),"Invalid probability cannot silently replace saved tuning");
        Files.delete(file);Files.delete(directory.resolve("anomaly-generation.before-balance-v1.json"));Files.delete(directory);
        System.out.println("ANOMALY_GENERATION_BALANCE_VERIFICATION_PASSED: new defaults, exact legacy template migration/backup, custom overrides, repeat load, invalid config preservation.");
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
