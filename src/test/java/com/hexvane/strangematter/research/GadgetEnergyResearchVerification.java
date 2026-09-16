package com.hexvane.strangematter.research;

import com.hexvane.strangematter.machine.ForgeRecipe;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.entity.entities.player.data.PlayerConfigData;
import java.nio.file.Files;
import java.util.*;

/** Progression is executable: the first charging chain is affordable and old knowledge survives. */
public final class GadgetEnergyResearchVerification {
    public static void verifyLedger() throws Exception {
        require(ResearchCatalog.get("resonant_energy").costs().equals(Map.of(ResearchType.ENERGY,5)),"First power notes cost only 5 Energy");
        require(ResearchCatalog.get("resonant_energy").prerequisites().equals(List.of("research")),"Charging needs no advanced machine prerequisite");
        for(String item:List.of("SM_Resonant_Burner","SM_Resonant_Charging_Station","SM_Resonant_Energy_Storage","SM_Resonant_Conduit"))
            require("resonant_energy".equals(ResearchRecipeBridge.requirement(item)),"One discovery unlocks the entire charging chain: "+item);
        require("resonant_battery_pack".equals(ResearchRecipeBridge.requirement("SM_Resonant_Battery_Pack")),"Specific pack mapping overrides basic resonant prefix");
        require("gravitic_manipulation".equals(ResearchRecipeBridge.requirement("SM_Gravitic_Manipulator")),"Gravity tool has explicit research");
        require("arc_projection".equals(ResearchRecipeBridge.requirement("SM_Arc_Projector")),"Arc weapon has explicit research");
        var recipes=ForgeRecipe.load();
        for(String id:List.of("resonant_battery_pack","gravitic_manipulator","arc_projector")) {
            var recipe=recipes.stream().filter(r->id.equals(r.id)).findFirst().orElseThrow();
            require(ResearchCatalog.get(recipe.research)!=null,"Forge gadget has research");
            for(var shard:recipe.shards.entrySet()) {
                String item="SM_"+Character.toUpperCase(shard.getKey().charAt(0))+shard.getKey().substring(1)+"_Shard";
                require(!recipe.ingredients.containsKey(item)&&recipe.totalCost().get(item).equals(shard.getValue()),"Shard cost is represented exactly once");
            }
        }
        var directory=Files.createTempDirectory("sm-energy-research-migration-");var old=UUID.randomUUID();var fresh=UUID.randomUUID();
        Files.writeString(directory.resolve("research.properties"),"player."+old+".points.energy=7\nplayer."+old+".scan.saved=true\nother.plugin.marker=keep\n");
        Files.writeString(directory.resolve("research-catalog.json"),"{\"nodes\":[{\"id\":\"resonant_energy\",\"costs\":{\"ENERGY\":8},\"name\":\"Server Power\"}]}");
        try(var research=new ResearchService(directory)) {
            require(research.hasUnlocked(old,"resonant_energy"),"Implicit old access becomes explicit before native recipe reconciliation");
            require(!research.hasUnlocked(fresh,"resonant_energy"),"New players do not inherit old power access");
            require(research.points(old,ResearchType.ENERGY)==7,"Migration preserves observations");
            require(research.node("resonant_energy").name().equals("Server Power")&&research.node("resonant_energy").costs().equals(Map.of(ResearchType.ENERGY,8)),"Server catalog customization remains authoritative");
            research.addPoints(fresh,ResearchType.GRAVITY,2);
            research.reset(old);
        }
        try(var restored=new ResearchService(directory)) {
            require(!restored.hasUnlocked(old,"resonant_energy")&&!restored.hasUnlocked(fresh,"resonant_energy"),"Migration runs once; restart does not refill reset or fresh profiles");
        }
        require(Files.readString(directory.resolve("research.properties")).contains("other.plugin.marker=keep"),"Unrelated ledger keys survive");
        verifyLegacyServerFlag();
    }
    private static void verifyLegacyServerFlag() throws Exception {
        var legacyDirectory=Files.createTempDirectory("sm-energy-old-server-no-ledger-");
        // Older ResearchSettings always wrote this file, even without any ledger UUIDs.
        Files.writeString(legacyDirectory.resolve("research-config.json"),"{}");
        var legacy=UUID.randomUUID();var fresh=UUID.randomUUID();var resetBeforeJoin=UUID.randomUUID();
        try(var research=new ResearchService(legacyDirectory)) {
            require(!research.hasUnlocked(legacy,"resonant_energy"),"Native-only old profile waits for actual saved recipe evidence");
            research.migrateNativeEnergyKnowledge(legacy,Set.of("SM_Resonant_Coil_Recipe_Generated_0"));
            require(research.hasUnlocked(legacy,"resonant_energy"),"Existing config without a research ledger identifies an upgraded server");
            research.migrateNativeEnergyKnowledge(fresh,Set.of());
            research.migrateNativeEnergyKnowledge(fresh,Set.of("SM_Resonant_Burner"));
            require(!research.hasUnlocked(fresh,"resonant_energy"),"New player first-join marker prevents later native knowledge from bypassing research");
            research.reset(legacy);research.reset(resetBeforeJoin);
        }
        try(var research=new ResearchService(legacyDirectory)) {
            for(var player:List.of(legacy,resetBeforeJoin)) {
                research.migrateNativeEnergyKnowledge(player,Set.of("SM_Resonant_Burner"));
                require(!research.hasUnlocked(player,"resonant_energy"),"Explicit reset survives restart and blocks stale native knowledge, including before first join");
            }
        }
        var freshDirectory=Files.createTempDirectory("sm-energy-new-server-");var newServerPlayer=UUID.randomUUID();
        try(var research=new ResearchService(freshDirectory)) {
            research.migrateNativeEnergyKnowledge(newServerPlayer,Set.of("SM_Resonant_Burner"));
            require(!research.hasUnlocked(newServerPlayer,"resonant_energy"),"A genuinely new server cannot treat manually learned native recipes as old progress");
        }
        try(var research=new ResearchService(freshDirectory)) {
            var later=UUID.randomUUID();research.migrateNativeEnergyKnowledge(later,Set.of("SM_Resonant_Conduit"));
            require(!research.hasUnlocked(later,"resonant_energy"),"Persisted new-server flag remains false despite config and ledger files now existing");
        }
    }
    public static void verifyNativePurchase() throws Exception {
        verifyNativeOnlyMigration();
        var directory=Files.createTempDirectory("sm-energy-research-purchase-");var player=UUID.randomUUID();
        try(var research=new ResearchService(directory)) {
            var inventory=new SimpleItemContainer((short)1);
            require(!research.hasUnlocked(player,"resonant_energy"),"Fresh power research is gated");
            require(research.scan(player,"first-natural-energy",ResearchType.ENERGY,10),"One fresh energetic anomaly funds the first power research");
            inventory.addItemStack(new ItemStack("SM_Field_Scanner",1));
            require(!research.purchase(player,"resonant_energy",inventory).startsWith("Created ")&&research.points(player,ResearchType.ENERGY)==10,"Full inventory cannot spend the first power budget");
            inventory.clear();
            require(research.purchase(player,"resonant_energy",inventory).startsWith("Created "),"First charging research can be purchased through the native inventory");
            require(research.points(player,ResearchType.ENERGY)==5,"Exactly five Energy points are spent");
            ItemStack note=inventory.getItemStack((short)0);String token=ResearchService.noteToken(note);
            var failed=new ResearchSession(research.node("resonant_energy"),9);failed.begin();
            for(int i=0;i<2000&&failed.state()==ResearchSession.State.RUNNING;i++)failed.tick();
            require(failed.state()==ResearchSession.State.FAILURE&&!research.finish(player,failed,token,inventory,research.generation(player)),"Failed stabilization does not unlock charging");
            require(!ItemStack.isEmpty(inventory.getItemStack((short)0)),"Failed attempt preserves notes for retry");
            var settings=new ResearchSettings();settings.enableMinigames=false;var solved=new ResearchSession(research.node("resonant_energy"),9,settings);solved.begin();
            require(research.finish(player,solved,token,inventory,research.generation(player)),"Successful retry consumes the note and unlocks research");
            var known=ResearchRecipeBridge.knowledgeFor(research,player);
            for(String item:List.of("SM_Resonant_Burner","SM_Resonant_Charging_Station","SM_Resonant_Energy_Storage","SM_Resonant_Conduit","SM_Resonant_Coil"))
                require(known.contains(item),"Native crafting knowledge exposes "+item);
            require(!known.contains("SM_Resonant_Battery_Pack"),"Advanced pack cannot unlock from the resonant prefix");
        }
    }
    private static void verifyNativeOnlyMigration() throws Exception {
        var directory=Files.createTempDirectory("sm-energy-native-only-legacy-");
        Files.writeString(directory.resolve("research-config.json"),"{}");
        Files.writeString(directory.resolve("research-catalog.json"),"{\"nodes\":[{\"id\":\"resonant_energy\",\"name\":\"Custom Fundamentals\",\"costs\":{\"ENERGY\":9}}]}");
        var player=UUID.randomUUID();var fresh=UUID.randomUUID();var config=new PlayerConfigData();
        config.setKnownRecipes(new HashSet<>(Set.of("SM_Resonant_Burner_Recipe_Generated_0","OtherMod_Recipe")));
        try(var research=new ResearchService(directory)) {
            require(!Files.readString(directory.resolve("research.properties")).contains(player.toString()),"Legacy player genuinely has no research ledger UUID before native join");
            ResearchRecipeBridge.reconcile(research,player,config);
            require(research.hasUnlocked(player,"resonant_energy")&&config.getKnownRecipes().contains("SM_Resonant_Charging_Station"),"Native saved burner recipe migrates before revocation and learns the charging station");
            require(config.getKnownRecipes().contains("OtherMod_Recipe"),"Native-only migration preserves unrelated recipe knowledge");
            require(research.node("resonant_energy").name().equals("Custom Fundamentals")&&research.node("resonant_energy").costs().equals(Map.of(ResearchType.ENERGY,9)),"Native-only migration preserves server catalog overrides");
            var newConfig=new PlayerConfigData();ResearchRecipeBridge.reconcile(research,fresh,newConfig);
            require(!research.hasUnlocked(fresh,"resonant_energy")&&!newConfig.getKnownRecipes().contains("SM_Resonant_Charging_Station"),"New players on upgraded servers still pay the configured research cost");
            research.reset(player);
            // The native page still carries old known recipes when reset reconciliation begins.
            require(config.getKnownRecipes().contains("SM_Resonant_Burner"),"Reset fixture retains stale native knowledge");
            ResearchRecipeBridge.reconcile(research,player,config);
            require(!research.hasUnlocked(player,"resonant_energy")&&!config.getKnownRecipes().contains("SM_Resonant_Burner"),"Reset marker prevents native stale knowledge from regranting old power research");
        }
        try(var research=new ResearchService(directory)) {
            config.setKnownRecipes(new HashSet<>(Set.of("SM_Resonant_Burner")));ResearchRecipeBridge.reconcile(research,player,config);
            require(!research.hasUnlocked(player,"resonant_energy")&&!config.getKnownRecipes().contains("SM_Resonant_Burner"),"Reset and one-time native migration persist across restart");
        }
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
