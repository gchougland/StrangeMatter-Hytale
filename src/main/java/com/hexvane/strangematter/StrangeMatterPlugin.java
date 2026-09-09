package com.hexvane.strangematter;

import com.hexvane.strangematter.anomaly.AnomalyService;
import com.hexvane.strangematter.machine.*;
import com.hexvane.strangematter.equipment.EquipmentService;
import com.hexvane.strangematter.research.ResearchService;
import com.hexvane.strangematter.block.AnomalousGrassService;
import com.hexvane.strangematter.block.FixtureLightingRefresh;
import com.hexvane.strangematter.worldgen.ScientistService;
import com.hexvane.strangematter.progression.ProgressionService;
import com.hexvane.strangematter.research.ResearchType;
import com.hypixel.hytale.server.core.asset.type.blocktick.config.RandomTickProcedure;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.event.EventPriority;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.plugin.*;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.events.ChunkPreLoadProcessEvent;
import com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class StrangeMatterPlugin extends JavaPlugin {
    private static volatile StrangeMatterPlugin instance;
    private ResearchService research;
    private AnomalyService anomalies;
    private MachineService machines;
    private EquipmentService equipment;
    private ScientistService scientists;
    private ProgressionService progression;
    public StrangeMatterPlugin(JavaPluginInit init){super(init);}
    public static StrangeMatterPlugin instance(){return instance;}
    public EquipmentService equipment(){return equipment;}
    public ProgressionService progression(){return progression;}
    @Override protected void setup(){
        try {
            var config=StrangeMatterConfig.load(getDataDirectory());
            research=new ResearchService(getDataDirectory());anomalies=new AnomalyService(getDataDirectory());
            machines=new MachineService(getDataDirectory(),config,research,anomalies);equipment=new EquipmentService(research,anomalies,machines);
            anomalies.setGroundingHook(machines::grounded);
            machines.setCapsuleReservationCheck(equipment::capsuleInFlight);
            scientists=new ScientistService(getDataDirectory());progression=new ProgressionService(getDataDirectory());
            scientists.setMachineRegistrar((world,position,id)->machines.register(world,position,id));
            research.setScanHook(progression::scanned);research.setCompletionHook(progression::completedResearch);
            anomalies.setFirstContactHook((player,anomaly,award)->{progression.firstContact(player);if(award>0)research.addPoints(player,ResearchType.fromName(anomaly.type.researchType),award);});
            instance=this;
            getCodecRegistry(Interaction.CODEC).register("SM_Use",StrangeMatterInteraction.class,StrangeMatterInteraction.CODEC);
            getCodecRegistry(RandomTickProcedure.CODEC).register("SM_Anomalous_Grass",AnomalousGrassService.class,AnomalousGrassService.CODEC);
            getEntityStoreRegistry().registerSystem(new MachineEvents.Place(machines));
            getEntityStoreRegistry().registerSystem(new MachineEvents.Break(machines));
            getEntityStoreRegistry().registerSystem(new MachineEvents.EnvironmentBreak(machines));
            getEntityStoreRegistry().registerSystem(new com.hexvane.strangematter.research.ResearchCraftGate(research));
            getEntityStoreRegistry().registerSystem(new LaboratoryTick());
            getEntityStoreRegistry().registerSystem(anomalies.gravitySystem());
            getEntityStoreRegistry().registerSystem(anomalies.gravityCleanupSystem());
            getEntityStoreRegistry().registerSystem(new com.hexvane.strangematter.anomaly.GravityTerrainEvents.Place(anomalies));
            getEntityStoreRegistry().registerSystem(new com.hexvane.strangematter.anomaly.GravityTerrainEvents.Break(anomalies));
            getEntityStoreRegistry().registerSystem(new com.hexvane.strangematter.anomaly.GravityTerrainEvents.Damage(anomalies));
            getEntityStoreRegistry().registerSystem(new com.hexvane.strangematter.anomaly.GravityTerrainEvents.EnvironmentBreak(anomalies));
            com.hexvane.strangematter.anomaly.ThoughtwellHallucinations.register(getEntityStoreRegistry());
            getEntityStoreRegistry().registerSystem(new com.hexvane.strangematter.equipment.HoverboardRiderPose.RestoreOnRemove());
            getChunkStoreRegistry().registerSystem(new FixtureLightingRefresh());
            getCommandRegistry().registerCommand(new StrangeMatterCommand(research,anomalies,machines,scientists,progression));
            getEventRegistry().registerGlobal(PlayerReadyEvent.class,event->{
                var ref=event.getPlayerRef();if(ref==null||!ref.isValid())return;
                var store=ref.getStore();var world=store.getExternalData().getWorld();
                world.execute(()->{if(!ref.isValid())return;var p=store.getComponent(ref,PlayerRef.getComponentType());if(p!=null){research.syncRecipes(p,store);machines.welcome(p,event.getPlayer());}});
            });
            getEventRegistry().registerGlobal(ChunkPreLoadProcessEvent.class,anomalies::onChunkPreLoad);
            getEventRegistry().registerGlobal(EventPriority.FIRST,ChunkPreLoadProcessEvent.class,scientists::onChunkPreLoad);
            getEventRegistry().registerGlobal(EventPriority.LAST,RemoveWorldEvent.class,event->{if(!event.isCancelled())cleanupWorld(event.getWorld());});
            getLogger().atInfo().log("Strange Matter initialized: six anomaly disciplines and native laboratory research.");
        }catch(Exception e){throw new IllegalStateException("Strange Matter initialization failed",e);}
    }
    @Override protected void start(){
        // Loaded chunk lighting can outlive changes to block asset colors.
        for(var world:Universe.get().getWorlds().values())if(world.isAlive())
            world.execute(()->{if(instance==this)FixtureLightingRefresh.refreshLoaded(world);});
    }
    private final class LaboratoryTick extends TickingSystem<EntityStore> {
        private final Set<String> pending=ConcurrentHashMap.newKeySet();
        private final java.util.Map<String,Long> lastErrors=new ConcurrentHashMap<>();
        private void tickSubsystem(World world,String name,Runnable tick) {
            try { tick.run(); }
            catch(Exception e) {
                long now=System.currentTimeMillis();String key=world.getName()+":"+name;
                if(now-lastErrors.getOrDefault(key,0L)>=5000) {
                    lastErrors.put(key,now);
                    getLogger().atSevere().withCause(e).log("Strange Matter %s tick failed in %s",name,world.getName());
                }
            }
        }
        @Override public void tick(float dt,int index,Store<EntityStore> store){
            var world=store.getExternalData().getWorld();
            if(!pending.add(world.getName()))return;
            world.execute(()->{try{if(instance!=null){
                // A broken field must not starve capsule inventory receipts or mounted movement.
                tickSubsystem(world,"anomalies",()->anomalies.tick(world,dt));
                tickSubsystem(world,"machines",()->machines.tick(world,dt));
                tickSubsystem(world,"equipment",()->equipment.tick(world,dt));
                tickSubsystem(world,"scientists",()->scientists.tick(world,dt));
                tickSubsystem(world,"progression",()->progression.tick(world,dt));
            }}finally{pending.remove(world.getName());}});
        }
    }
    @Override protected void shutdown(){
        instance=null;
        if(research!=null)research.close();
        if(anomalies!=null){
            for(var world:Universe.get().getWorlds().values())cleanupWorld(world);
            anomalies.save();
        }
        if(machines!=null)machines.close();
        if(scientists!=null)scientists.save();
        if(progression!=null)progression.save();
    }
    private void cleanupWorld(World world){
        if(equipment==null||anomalies==null)return;
        Runnable cleanup=()->{equipment.cleanup(world);anomalies.stopWorld(world);if(machines!=null)machines.cleanupPresentation(world);};
        if(world.isInThread()){cleanup.run();return;}
        if(!world.isAlive())return;
        try{CompletableFuture.runAsync(cleanup,world).get(5,TimeUnit.SECONDS);}
        catch(Exception e){getLogger().atWarning().withCause(e).log("World cleanup did not complete before shutdown: %s",world.getName());}
    }
}
