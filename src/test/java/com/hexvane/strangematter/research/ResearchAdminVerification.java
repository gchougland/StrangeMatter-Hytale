package com.hexvane.strangematter.research;

import com.hexvane.strangematter.StrangeMatterCommand;
import com.hexvane.strangematter.equipment.NativePlayerFixture;
import com.hexvane.strangematter.progression.ProgressionService;
import com.hypixel.hytale.protocol.packets.interface_.UpdateKnownRecipes;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.command.system.ParseResult;
import com.hypixel.hytale.server.core.command.system.suggestion.SuggestionResult;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.CraftRecipeEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.permissions.provider.HytalePermissionsProvider;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import org.bson.BsonDocument;
import org.joml.Vector3d;
import org.joml.Vector3i;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Focused command/ledger checks, plus real loaded items, player storage and recipe packets in verify(world). */
public final class ResearchAdminVerification {
    public static void main(String[] args) throws Exception {
        var directory=Files.createTempDirectory("sm-research-admin-");
        UUID player=UUID.randomUUID(),other=UUID.randomUUID();
        ResearchService.ProfileView before,otherBefore;
        long afterGeneration;
        try(var research=new ResearchService(directory)){
            var progression=new ProgressionService(directory);
            research.setCompletionHook(progression::completedResearch);research.setScanHook(progression::scanned);
            progression.joined(player);progression.inventoryChanged(player,List.of("SM_Research_Machine"));
            for(var type:ResearchType.values())research.scan(player,"scan-"+type,type,17);
            research.unlock(player,"all",true);research.unlock(other,"hoverboard",true);
            before=research.profile(player);otherBefore=research.profile(other);
            require(progression.complete(player,"knowledge_seeker"),"Unlocks fulfill the actual research-completion achievement");
            require(progression.complete(player,"anomaly_collector")&&progression.complete(player,"research_master"),"Unrelated scan and inventory achievements were earned");
            var owner=new PlayerRef(null,player,"AdminResearchTest","en-US",null,null);
            var oldPage=new ResearchMachinePage(owner,research,new Vector3i());
            require(oldPage.reserve("admin-reset:0,0,0"),"Old experiment holds the actual researcher reservation");
            long beforeGeneration=research.generation(player);
            int removed=research.reset(player);afterGeneration=research.generation(player);
            require(removed>0&&afterGeneration==beforeGeneration+1,"Reset advances the durable generation and clears earned topics");
            assertReset(research,player,before);
            require(research.profile(other).equals(otherBefore),"Other players retain knowledge and observations");
            progression.reconcileResearch(player,research.earnedNodes(player));
            require(!progression.complete(player,"knowledge_seeker"),"Research-derived completion is removed");
            require(progression.complete(player,"anomaly_collector")&&progression.complete(player,"research_master")&&progression.complete(player,"root"),"Scan, inventory and join achievements survive");
            var currentPage=new ResearchMachinePage(owner,research,new Vector3i());
            require(currentPage.reserve("admin-reset:0,0,0"),"Reset immediately releases the old reservation");
            oldPage.onDismiss(null,null);
            require(!research.acquire("admin-reset:other",player),"Late dismissal of the old page cannot release the new experiment");
            currentPage.onDismiss(null,null);
            require(!oldPage.reserve("admin-reset:stale"),"A pre-reset page cannot acquire another experiment");
            var command=new StrangeMatterCommand(research,null,null,null,progression);
            var branch=command.getSubCommands().get("research");
            for(String name:List.of("unlock","note","reset")){
                var child=branch.getSubCommands().get(name);
                require(child!=null&&"strangematter.admin".equals(child.getPermission())&&child.getPermissionGroups().isEmpty(),"Native admin-only subcommand: "+name);
                require(child.getOptionalArguments().containsKey("player"),"Optional native player target: "+name);
            }
            require(branch.getSubCommands().get("note").getRequiredArguments().size()==1&&branch.getSubCommands().get("reset").getRequiredArguments().isEmpty(),"Native help exposes note ID and self-default reset");
            require(!command.getPermissionGroupsRecursive().getOrDefault(HytalePermissionsProvider.GROUP_ADVENTURER,Set.of()).contains("strangematter.admin"),"New commands never publish admin permission to Adventurers");
            var argument=command.noteArgument();var parsed=new ParseResult();
            require("hoverboard".equals(argument.parse("HOVERBOARD",parsed))&&!parsed.failed(),"Named note IDs are case insensitive");
            for(String invalid:List.of("all","research","reality_forge_category","invented")){
                var result=new ParseResult();require(argument.parse(invalid,result)==null&&result.failed(),"Only real note experiments are accepted: "+invalid);
            }
            var suggestions=new SuggestionResult();argument.suggest(null,"hov",0,suggestions);
            require(suggestions.getSuggestions().equals(List.of("hoverboard")),"Native autocomplete suggests the specific experiment");
        }
        try(var restored=new ResearchService(directory)){
            assertReset(restored,player,before);
            require(restored.generation(player)==afterGeneration&&restored.profile(other).equals(otherBefore),"Reset generation and player isolation survive reload");
            var progression=new ProgressionService(directory);
            require(!progression.complete(player,"knowledge_seeker")&&progression.complete(player,"anomaly_collector"),"Scoped achievement reset persists");
            // Simulate the achievement file surviving a crash just before reset reconciliation.
            for(var node:restored.nodes())if(!node.defaultUnlocked())progression.completedResearch(player,node);
            require(progression.complete(player,"knowledge_seeker"),"Fixture restored stale achievement completion");
            progression.reconcileResearch(player,restored.earnedNodes(player));
            require(!progression.complete(player,"knowledge_seeker"),"Join reconciliation repairs a stale achievement file from authoritative research");
        }
        System.out.println("RESEARCH_ADMIN_VERIFICATION_PASSED: scoped persistent reset, points/scans/other-player preservation, actual reservation generations, achievement repair, native permissions/help/autocomplete.");
    }
    private static void assertReset(ResearchService research,UUID player,ResearchService.ProfileView before){
        var after=research.profile(player);
        require(after.points().equals(before.points())&&after.scannedCount()==before.scannedCount(),"Points and observation history are unchanged");
        for(var node:research.nodes())require(research.hasUnlocked(player,node.id())==node.defaultUnlocked(),"Only starter topics remain: "+node.id());
        for(var type:ResearchType.values())require(research.hasScanned(player,"scan-"+type),"Scan identity remains spent: "+type);
    }
    public static void verify(World world) throws Exception {
        var directory=Files.createTempDirectory("sm-native-research-admin-");
        try(var research=new ResearchService(directory);var player=NativePlayerFixture.create(world,"ResearchAdmin",new Vector3d(16,223,8))){
            UUID id=player.owner().getUuid();
            var inventory=player.hotbar();
            String result=research.giveNote("hoverboard",inventory);
            require(result.startsWith("Created ")&&!research.hasUnlocked(id,"containment_basics"),"Admin gives a note without points or prerequisites: "+result);
            var note=inventory.getItemStack((short)0);String token=ResearchService.noteToken(note);
            require(token!=null&&research.noteNode(note).id().equals("hoverboard"),"Named note has its real persistent token and topic");
            require(note.getDisplayName().getRawText().equals("Research Notes: Hoverboard")&&note.getDisplayDescription().getRawText().contains("Research Machine"),"Actual native title and tooltip identify the experiment");
            var packet=note.toPacket();var restored=new ItemStack(packet.itemId,packet.quantity,BsonDocument.parse(packet.metadata));
            require(token.equals(ResearchService.noteToken(restored))&&restored.getDisplayName().getRawText().equals(note.getDisplayName().getRawText()),"Native item packet preserves title and token");
            var full=new SimpleItemContainer((short)1);full.setItemStackForSlot((short)0,new ItemStack("SM_Resonite_Ingot",100));
            String saved=Files.readString(directory.resolve("research.properties"));
            require(research.giveNote("warp_gun",full).contains("Make room"),"Full inventory refuses delivery");
            require(full.getItemStack((short)0).getQuantity()==100&&Files.readString(directory.resolve("research.properties")).equals(saved),"Rejected delivery changes neither existing items nor note ledger");
            var settings=new ResearchSettings();settings.enableMinigames=false;
            var oldSession=new ResearchSession(research.node("hoverboard"),1,settings);oldSession.begin();long generation=research.generation(id);
            research.unlock(id,"reality_forge",true);
            player.player().getPlayerConfigData().setKnownRecipes(new HashSet<>(Set.of("Bench_WorkBench")));
            research.syncRecipes(player.owner(),player.store());
            require(player.player().getPlayerConfigData().getKnownRecipes().contains("SM_Reality_Forge"),"Native knowledge contains the earned recipe before reset");
            research.openTablet(player.owner(),player.store());
            require(player.player().getPageManager().getCustomPage() instanceof ResearchTabletPage,"Actual native tablet page is open before reset");
            research.reset(id);research.refreshAfterReset(player.owner(),player.store());
            require(player.player().getPageManager().getCustomPage()==null,"Reset dismisses the actual page while its initial ACK may still be pending");
            require(!research.finish(id,oldSession,token,inventory,generation)&&inventory.getItemStack((short)0).equals(note),"Delayed successful experiment cannot consume its note or restore reset research");
            require(!research.hasUnlocked(id,"hoverboard"),"Stale success does not restore the topic");
            var known=player.player().getPlayerConfigData().getKnownRecipes();
            require(!known.contains("SM_Reality_Forge")&&known.contains("Bench_WorkBench")&&known.contains("SM_Field_Scanner"),"Native reset preserves vanilla and starter recipe knowledge");
            var update=player.packets().ofType(UpdateKnownRecipes.class).getLast();
            require(!update.known.containsKey("SM_Reality_Forge")&&update.known.containsKey("SM_Field_Scanner"),"Actual native recipe packet revokes the earned output");
            var bytes=MemorySegment.ofArray(new byte[update.computeSize()]);update.serialize(bytes,0);
            require(UpdateKnownRecipes.toObject(bytes).equals(update),"Reset recipe packet round trips through native wire schema");
            var recipe=CraftingRecipe.getAssetMap().getAsset("SM_Reality_Forge_Recipe_Generated_0");var event=new CraftRecipeEvent.Pre(recipe,1);
            new ResearchCraftGate(research).validate(id,event);require(event.isCancelled(),"Authoritative native pre-craft gate rejects the cleared research");
            player.save();
            var disk=Universe.get().getPlayerStorage().load(id).get(10,TimeUnit.SECONDS);
            require(!disk.getComponent(Player.getComponentType()).getPlayerConfigData().getKnownRecipes().contains("SM_Reality_Forge"),"Actual DiskPlayerStorage retains revoked native knowledge");
            require(research.giveNote("reality_forge",inventory).startsWith("Created "),"A new note remains obtainable after reset");
            var freshNote=inventory.getItemStack((short)1);var fresh=new ResearchSession(research.node("reality_forge"),2,settings);fresh.begin();
            require(research.finish(id,fresh,ResearchService.noteToken(freshNote),inventory,research.generation(id)),"A new experiment after reset can complete normally");
        }
        System.out.println("NATIVE_RESEARCH_ADMIN_VERIFICATION_PASSED: actual titled note packet, full inventory rollback, native page close, stale-success rejection, recipe revocation/wire/craft gate, DiskPlayerStorage and fresh completion.");
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
