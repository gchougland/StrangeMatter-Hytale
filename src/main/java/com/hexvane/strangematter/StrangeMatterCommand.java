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
        addSubCommand(new PlayerAction("milestones","Open the milestone journal",false));
        addSubCommand(new PlayerAction("kit","Give laboratory starter instruments",true));
        addSubCommand(new PlayerAction("scientist","Spawn a laboratory scientist nearby",true));
        addSubCommand(new Save());addSubCommand(new Spawn());addSubCommand(new Locate());addSubCommand(new Points());
        addSubCommand(new ResearchCommands());addSubCommand(new Unlock("unlock"));
    }
    private final class Help extends CommandBase {
        Help(){super("help","Explain progression and list native commands");setPermissionGroups(HytalePermissionsProvider.GROUP_ADVENTURER);}
        @Override protected void executeSync(CommandContext context){
            context.sendMessage(Message.raw("Scan natural anomalies, buy notes in the tablet, then stabilize them at a Research Machine. Select an unlocked topic to read its field guide. Use --help after any subcommand for typed arguments."));
            context.sendMessage(StrangeMatterCommand.this.getFullUsage(context.sender()));
            if(context.sender().hasPermission(ADMIN))context.sendMessage(Message.raw("Admin examples: /sm research unlock hoverboard | /sm research unlock all --player PlayerName | /sm points energy 25 --player <UUID>. Unlock includes prerequisites; --strict requires them to be completed."));
        }
    }
    private final class PlayerAction extends AbstractPlayerCommand {
        private final String action;
        PlayerAction(String action,String description,boolean admin){super(action,description);this.action=action;if(admin)requirePermission(ADMIN);else setPermissionGroups(HytalePermissionsProvider.GROUP_ADVENTURER);}
        @Override protected void execute(CommandContext context,Store<EntityStore> store,Ref<EntityStore> ref,PlayerRef player,World world){
            switch(action){
                case "journal"->research.openTablet(player,store);
                case "milestones"->progression.open(player,store);
                case "status"->{var p=research.profile(player.getUuid());context.sendMessage(Message.raw("Research: "+p.points()+" | "+p.unlocked().size()+" topics | "+p.scannedCount()+" observations"));}
                case "scientist"->{var transform=store.getComponent(ref,TransformComponent.getComponentType());if(transform==null)return;var id=scientists.spawnScientist(world,new Vector3d(transform.getPosition()).add(0,0,3));context.sendMessage(Message.raw("Spawned laboratory scientist ("+id+")."));}
                case "kit"->{var inv=InventoryComponent.getCombined(store,ref,InventoryComponent.HOTBAR_FIRST);int delivered=0;for(String id:List.of("SM_Research_Tablet","SM_Field_Scanner","SM_Research_Machine","SM_Anomaly_Resonator","SM_Resonant_Burner","SM_Resonance_Condenser","SM_Reality_Forge","SM_Paradoxical_Energy_Cell","SM_Echo_Vacuum","SM_Containment_Capsule","SM_Resonant_Conduit","SM_Rift_Stabilizer","SM_Stasis_Projector","SM_Levitation_Pad"))if(InventoryOps.give(inv,new ItemStack(id,1)))delivered++;context.sendMessage(Message.raw("Delivered "+delivered+" laboratory instruments; remaining items need inventory space."));}
            }
        }
    }
    private final class Save extends CommandBase {
        Save(){super("save","Save laboratory world state");requirePermission(ADMIN);}
        @Override protected void executeSync(CommandContext context){machines.save();anomalies.save();scientists.save();progression.save();context.sendMessage(Message.raw("Saved Strange Matter world state."));}
    }
    private final class Spawn extends AbstractPlayerCommand {
        private final RequiredArg<AnomalyType> type=withRequiredArg("type","Anomaly type to spawn",ArgTypes.forEnum("Anomaly type",AnomalyType.class));
        Spawn(){super("spawn","Spawn a natural anomaly nearby");requirePermission(ADMIN);}
        @Override protected void execute(CommandContext context,Store<EntityStore> store,Ref<EntityStore> ref,PlayerRef player,World world){var transform=store.getComponent(ref,TransformComponent.getComponentType());if(transform==null)return;var a=anomalies.spawn(type.get(context),world,new Vector3d(transform.getPosition()).add(0,1,4),true);anomalies.save();context.sendMessage(Message.raw("Spawned "+a.type.displayName+" ("+a.id+")."));}
    }
    private final class Locate extends AbstractPlayerCommand {
        private final OptionalArg<AnomalyType> type=withOptionalArg("type","Restrict to an anomaly type",ArgTypes.forEnum("Anomaly type",AnomalyType.class));
        Locate(){super("locate","Locate a discovered anomaly; optional --type");requirePermission(ADMIN);}
        @Override protected void execute(CommandContext context,Store<EntityStore> store,Ref<EntityStore> ref,PlayerRef player,World world){var t=store.getComponent(ref,TransformComponent.getComponentType());if(t==null)return;var a=anomalies.nearest(world,t.getPosition(),100000,type.get(context));context.sendMessage(Message.raw(a.map(v->v.type.displayName+" at "+(int)v.x+", "+(int)v.y+", "+(int)v.z).orElse("No discovered anomaly matches this frequency.")));}
    }
    private final class Points extends CommandBase {
        private final RequiredArg<ResearchType> type=withRequiredArg("discipline","Research discipline",ArgTypes.forEnum("Research discipline",ResearchType.class));
        private final RequiredArg<Integer> amount=withRequiredArg("amount","Observations to add, 1 to 100000",ArgTypes.INTEGER);
        private final OptionalArg<UUID> target=withOptionalArg("player","Online name or offline UUID; defaults to you",playerArgument());
        Points(){super("points","Award research observations");requirePermission(ADMIN);}
        @Override protected void executeSync(CommandContext context){UUID id=target(context,target);if(id==null)return;int count=amount.get(context);if(count<1||count>100000){context.sendMessage(Message.raw("Amount must be from 1 to 100000."));return;}try{research.addPoints(id,type.get(context),count);context.sendMessage(Message.raw("Added "+count+" "+type.get(context).displayName()+" observations for "+id+"."));}catch(ArithmeticException overflow){context.sendMessage(Message.raw("The observation balance is at its supported maximum."));}}
    }
    private final class ResearchCommands extends AbstractCommandCollection {
        ResearchCommands(){super("research","Administer research progression");requirePermission(ADMIN);addSubCommand(new Unlock("unlock"));}
    }
    private final class Unlock extends CommandBase {
        private final RequiredArg<String> node=withRequiredArg("node","Research node ID or all",researchArgument());
        private final OptionalArg<UUID> target=withOptionalArg("player","Online name or offline UUID; defaults to you",playerArgument());
        private final FlagArg strict=withFlagArg("strict","Require prerequisites instead of unlocking them too");
        Unlock(String name){super(name,"Unlock one topic and its prerequisites, or all topics");requirePermission(ADMIN);}
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

