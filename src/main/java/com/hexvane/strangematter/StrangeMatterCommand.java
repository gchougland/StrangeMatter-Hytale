package com.hexvane.strangematter;

import com.hexvane.strangematter.anomaly.*;
import com.hexvane.strangematter.machine.MachineService;
import com.hexvane.strangematter.progression.ProgressionService;
import com.hexvane.strangematter.research.*;
import com.hexvane.strangematter.util.InventoryOps;
import com.hexvane.strangematter.worldgen.ScientistService;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.*;
import com.hypixel.hytale.server.core.command.system.arguments.system.*;
import com.hypixel.hytale.server.core.command.system.arguments.types.*;
import com.hypixel.hytale.server.core.command.system.basecommands.*;
import com.hypixel.hytale.server.core.command.system.suggestion.SuggestionResult;
import com.hypixel.hytale.server.core.inventory.*;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.permissions.provider.HytalePermissionsProvider;
import com.hypixel.hytale.server.core.universe.*;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;
import java.util.*;
import java.util.function.Supplier;
import java.util.function.BiConsumer;

/** Real native subcommands and typed arguments provide client help, completion and permissions. */
public final class StrangeMatterCommand extends AbstractCommandCollection {
    private final ResearchService research; private final AnomalyService anomalies; private final MachineService machines;
    private final ScientistService scientists; private final ProgressionService progression;
    private static final String ADMIN = "strangematter.admin";
    public StrangeMatterCommand(ResearchService research,AnomalyService anomalies,MachineService machines,ScientistService scientists,ProgressionService progression) {
        super("strangematter","Strange Matter field guide and laboratory administration");addAliases("sm");
        setPermissionGroups(HytalePermissionsProvider.GROUP_ADVENTURER);
        this.research=research;this.anomalies=anomalies;this.machines=machines;this.scientists=scientists;this.progression=progression;
        addSubCommand(new Help());
        var journal=new PlayerAction("journal","Open the research archive",false);journal.addAliases("tablet");addSubCommand(journal);
        addSubCommand(new PlayerAction("status","Show observations and unlocked research",false));
        var achievements=new PlayerAction("achievements","Open your achievements",false);achievements.addAliases("milestones");addSubCommand(achievements);
        addSubCommand(new PlayerAction("kit","Give laboratory starter instruments",true));
        addSubCommand(new PlayerAction("scientist","Spawn a laboratory scientist nearby",true));
        addSubCommand(new Save());addSubCommand(new Spawn());addSubCommand(new Locate());addSubCommand(new Points());addSubCommand(new AllPoints());
        addSubCommand(new ResearchCommands());addSubCommand(new Unlock("unlock"));
    }
    private final class Help extends CommandBase {
        Help(){super("help","Explain progression and list native commands");setPermissionGroups(HytalePermissionsProvider.GROUP_ADVENTURER);}
        @Override protected void executeSync(CommandContext context){
            context.sendMessage(Message.raw("Scan natural anomalies, buy notes in the tablet, then stabilize them at a Research Machine. Select an unlocked topic to read its field guide. Use --help after any subcommand for typed arguments."));
            context.sendMessage(StrangeMatterCommand.this.getFullUsage(context.sender()));
            if(context.sender().hasPermission(ADMIN))context.sendMessage(Message.raw("Admin examples: /sm research unlock hoverboard | /sm research note hoverboard --player PlayerName | /sm research reset --player PlayerName | /sm points all 25. Unlock includes prerequisites; --strict requires them to be completed. Reset keeps observation points, scans and notes."));
        }
    }
    private final class PlayerAction extends AbstractPlayerCommand {
        private final String action;
        PlayerAction(String action,String description,boolean admin){super(action,description);this.action=action;if(admin){setPermissionGroups();requirePermission(ADMIN);}else setPermissionGroups(HytalePermissionsProvider.GROUP_ADVENTURER);}
        @Override protected void execute(CommandContext context,Store<EntityStore> store,Ref<EntityStore> ref,PlayerRef player,World world){
            switch(action){
                case "journal"->research.openTablet(player,store);
                case "achievements"->progression.open(player,store);
                case "status"->{var p=research.profile(player.getUuid());context.sendMessage(Message.raw("Research: "+p.points()+" | "+p.unlocked().size()+" topics | "+p.scannedCount()+" observations"));}
                case "scientist"->{var transform=store.getComponent(ref,TransformComponent.getComponentType());if(transform==null)return;var id=scientists.spawnScientist(world,new Vector3d(transform.getPosition()).add(0,0,3));context.sendMessage(Message.raw("Spawned laboratory scientist ("+id+")."));}
                case "kit"->{var inv=InventoryComponent.getCombined(store,ref,InventoryComponent.HOTBAR_FIRST);int delivered=0;for(String id:List.of("SM_Research_Tablet","SM_Field_Scanner","SM_Research_Machine","SM_Anomaly_Resonator","SM_Resonant_Burner","SM_Resonance_Condenser","SM_Reality_Forge","SM_Paradoxical_Energy_Cell","SM_Echo_Vacuum","SM_Containment_Capsule","SM_Resonant_Conduit","SM_Rift_Stabilizer","SM_Stasis_Projector","SM_Levitation_Pad","SM_Anomaly_Nullifier"))if(InventoryOps.give(inv,new ItemStack(id,1)))delivered++;context.sendMessage(Message.raw("Delivered "+delivered+" laboratory instruments; remaining items need inventory space."));}
            }
        }
    }
    private final class Save extends CommandBase {
        Save(){super("save","Save laboratory world state");setPermissionGroups();requirePermission(ADMIN);}
        @Override protected void executeSync(CommandContext context){machines.save();anomalies.save();scientists.save();progression.save();context.sendMessage(Message.raw("Saved Strange Matter world state."));}
    }
    private final class Spawn extends AbstractPlayerCommand {
        private final RequiredArg<AnomalyType> type=withRequiredArg("type","Anomaly type to spawn",ArgTypes.forEnum("Anomaly type",AnomalyType.class));
        Spawn(){super("spawn","Spawn a natural anomaly nearby");setPermissionGroups();requirePermission(ADMIN);}
        @Override protected void execute(CommandContext context,Store<EntityStore> store,Ref<EntityStore> ref,PlayerRef player,World world){var transform=store.getComponent(ref,TransformComponent.getComponentType());if(transform==null)return;var a=anomalies.spawnRaised(type.get(context),world,new Vector3d(transform.getPosition()).add(0,1,4),true);anomalies.save();context.sendMessage(Message.raw("Spawned "+a.type.displayName+" ("+a.id+")."));}
    }
    private final class Locate extends AbstractPlayerCommand {
        private final OptionalArg<AnomalyType> type=withOptionalArg("type","Restrict to an anomaly type",ArgTypes.forEnum("Anomaly type",AnomalyType.class));
        Locate(){super("locate","Locate a discovered anomaly; optional --type");setPermissionGroups();requirePermission(ADMIN);}
        @Override protected void execute(CommandContext context,Store<EntityStore> store,Ref<EntityStore> ref,PlayerRef player,World world){var t=store.getComponent(ref,TransformComponent.getComponentType());if(t==null)return;var a=anomalies.nearest(world,t.getPosition(),100000,type.get(context));context.sendMessage(Message.raw(a.map(v->v.type.displayName+" at "+(int)v.x+", "+(int)v.y+", "+(int)v.z).orElse("No discovered anomaly matches this frequency.")));}
    }
    private final class Points extends CommandBase {
        private final RequiredArg<String> type=withRequiredArg("discipline","Research discipline or all",disciplineArgument());
        private final RequiredArg<Integer> amount=withRequiredArg("amount","Observations to add, 1 to 100000",ArgTypes.INTEGER);
        private final OptionalArg<UUID> target=withOptionalArg("player","Online name or offline UUID; defaults to you",playerArgument());
        Points(){super("points","Award research observations");setPermissionGroups();requirePermission(ADMIN);}
        @Override protected void executeSync(CommandContext context){awardPoints(context,target,amount.get(context),ResearchType.fromName(type.get(context)));}
    }
    private final class AllPoints extends CommandBase {
        private final RequiredArg<Integer> amount=withRequiredArg("amount","Points to add to every discipline, 1 to 100000",ArgTypes.INTEGER);
        private final OptionalArg<UUID> target=withOptionalArg("player","Online name or offline UUID; defaults to you",playerArgument());
        AllPoints(){super("pointsall","Add the same number of points to every research discipline");setPermissionGroups();requirePermission(ADMIN);}
        @Override protected void executeSync(CommandContext context){awardPoints(context,target,amount.get(context),null);}
    }
    private void awardPoints(CommandContext context,OptionalArg<UUID> target,int count,ResearchType type){
        UUID id=target(context,target);if(id==null)return;
        if(count<1||count>100000){context.sendMessage(Message.raw("Amount must be from 1 to 100000."));return;}
        try{
            if(type==null)research.addPointsAll(id,count);else research.addPoints(id,type,count);
            context.sendMessage(Message.raw("Added "+count+" "+(type==null?"points to every discipline":type.displayName()+" points")+" for "+id+"."));
        }catch(ArithmeticException overflow){context.sendMessage(Message.raw("The point balance is at its supported maximum. No points were added."));}
    }
    private final class ResearchCommands extends AbstractCommandCollection {
        ResearchCommands(){super("research","Administer research progression");setPermissionGroups();requirePermission(ADMIN);addSubCommand(new Unlock("unlock"));addSubCommand(new GiveNote());addSubCommand(new ResetResearch());}
    }
    private final class GiveNote extends CommandBase {
        private final RequiredArg<String> node=withRequiredArg("node","Research experiment to put in the note",noteArgument());
        private final OptionalArg<UUID> target=withOptionalArg("player","Online recipient name or UUID; defaults to you",playerArgument());
        GiveNote(){super("note","Give one named research note without spending observations");setPermissionGroups();requirePermission(ADMIN);}
        @Override protected void executeSync(CommandContext context){
            UUID id=target(context,target);if(id==null)return;
            String requested=node.get(context);
            withOnlinePlayer(context,id,(player,store)->{
                var inventory=InventoryComponent.getCombined(store,player.getReference(),InventoryComponent.HOTBAR_FIRST);
                context.sendMessage(Message.raw(player.getUsername()+": "+research.giveNote(requested,inventory)));
            });
        }
    }
    private final class ResetResearch extends CommandBase {
        private final OptionalArg<UUID> target=withOptionalArg("player","Online name or offline UUID; defaults to you",playerArgument());
        ResetResearch(){super("reset","Clear earned research; keep points, scans, notes and starter topics");setPermissionGroups();requirePermission(ADMIN);}
        @Override protected void executeSync(CommandContext context){
            UUID id=target(context,target);if(id==null)return;
            var online=Universe.get().getPlayer(id);
            if(online!=null&&online.getReference()!=null&&online.getReference().isValid())
                withOnlinePlayer(context,id,(player,store)->resetResearch(context,id,player,store));
            else resetResearch(context,id,null,null);
        }
    }
    private void resetResearch(CommandContext context,UUID id,PlayerRef online,Store<EntityStore> store){
        int removed=research.reset(id);
        try{progression.reconcileResearch(id,research.earnedNodes(id));}
        catch(RuntimeException failed){System.getLogger(StrangeMatterCommand.class.getName()).log(System.Logger.Level.WARNING,"Research reset; achievement criteria will reconcile on next join",failed);}
        if(online!=null)try{research.refreshAfterReset(online,store);}
        catch(RuntimeException failed){System.getLogger(StrangeMatterCommand.class.getName()).log(System.Logger.Level.WARNING,"Research reset; native recipe knowledge will reconcile on next join",failed);}
        context.sendMessage(Message.raw("Reset "+removed+" earned research topics for "+(online==null?id:online.getUsername())+". Observation points, scans, notes and starter topics were kept."));
        if(online!=null)online.sendMessage(Message.raw("Your earned research was reset by an administrator. Your observation points and notes were kept."));
    }
    private void withOnlinePlayer(CommandContext context,UUID id,BiConsumer<PlayerRef,Store<EntityStore>> action){
        var online=Universe.get().getPlayer(id);var ref=online==null?null:online.getReference();
        if(ref==null||!ref.isValid()){context.sendMessage(Message.raw("The recipient must be online."));return;}
        var store=ref.getStore();var world=store.getExternalData().getWorld();
        try{world.execute(()->{
            if(!ref.isValid()||online.getReference()!=ref||ref.getStore()!=store){context.sendMessage(Message.raw("The player changed worlds or disconnected. Run the command again."));return;}
            action.accept(online,store);
        });}catch(RuntimeException stopped){context.sendMessage(Message.raw("The player's world is stopping. Run the command again after they reconnect."));}
    }
    private final class Unlock extends CommandBase {
        private final RequiredArg<String> node=withRequiredArg("node","Research node ID or all",researchArgument());
        private final OptionalArg<UUID> target=withOptionalArg("player","Online name or offline UUID; defaults to you",playerArgument());
        private final FlagArg strict=withFlagArg("strict","Require prerequisites instead of unlocking them too");
        Unlock(String name){super(name,"Unlock one topic and its prerequisites, or all topics");setPermissionGroups();requirePermission(ADMIN);}
        @Override protected void executeSync(CommandContext context){
            UUID id=target(context,target);if(id==null)return;
            try{
                var changed=research.unlock(id,node.get(context),!strict.get(context));var online=Universe.get().getPlayer(id);
                if(online!=null&&online.getReference()!=null&&online.getReference().isValid()){
                    var ref=online.getReference();var store=ref.getStore();
                    try{store.getExternalData().getWorld().execute(()->research.syncRecipes(online,store));}
                    catch(RuntimeException stopped){System.getLogger(StrangeMatterCommand.class.getName()).log(System.Logger.Level.WARNING,"Research unlocked; native knowledge will refresh on next join",stopped);}
                }
                context.sendMessage(Message.raw("Unlocked "+changed.size()+" research topics for "+(online==null?id:online.getUsername())+(changed.isEmpty()?". Already complete.":": "+changed.stream().map(ResearchNode::name).collect(java.util.stream.Collectors.joining(", "))+".")));
            }catch(IllegalArgumentException invalid){context.sendMessage(Message.raw(invalid.getMessage()));}
        }
    }
    private static UUID target(CommandContext context,OptionalArg<UUID> argument){UUID id=argument.get(context);if(id!=null)return id;if(context.isPlayer())return context.senderAs(PlayerRef.class).getUuid();context.sendMessage(Message.raw("The console must specify --player <online name or UUID>."));return null;}
    public SingleArgumentType<String> researchArgument(){return choices("Research node",()->{var ids=new ArrayList<>(research.nodes().stream().map(ResearchNode::id).toList());ids.add("all");return ids;});}
    public SingleArgumentType<String> noteArgument(){return choices("Research experiment",()->research.nodes().stream().filter(node->!node.defaultUnlocked()&&!node.id().equals("reality_forge_category")).map(ResearchNode::id).toList());}
    public static SingleArgumentType<String> disciplineArgument(){return choices("Research discipline",()->{var names=new ArrayList<>(Arrays.stream(ResearchType.values()).map(ResearchType::getName).toList());names.add("all");return names;});}
    static SingleArgumentType<String> choices(String name,Supplier<Collection<String>> values){
        return new SingleArgumentType<>(name,Message.raw(name)){
            @Override public String parse(String input,ParseResult result){String id=input.toLowerCase(Locale.ROOT);if(values.get().contains(id))return id;result.fail(Message.raw("Unknown "+name.toLowerCase(Locale.ROOT)+": "+input+". Use autocomplete to choose a value."));return null;}
            @Override public void suggest(CommandSender sender,String entered,int count,SuggestionResult result){String prefix=entered.toLowerCase(Locale.ROOT);values.get().stream().filter(v->v.startsWith(prefix)).sorted().forEach(result::suggest);}
        };
    }
    private static SingleArgumentType<UUID> playerArgument(){
        return new SingleArgumentType<>("Player",Message.raw("Online name or UUID")){
            @Override public UUID parse(String input,ParseResult result){return ArgTypes.PLAYER_UUID.parse(input,result);}
            @Override public void suggest(CommandSender sender,String entered,int count,SuggestionResult result){ArgTypes.PLAYER_REF.suggest(sender,entered,count,result);}
        };
    }
}

