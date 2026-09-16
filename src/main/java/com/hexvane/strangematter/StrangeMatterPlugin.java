package com.hexvane.strangematter;

import com.hexvane.strangematter.anomaly.AnomalyService;
import com.hexvane.strangematter.machine.*;
import com.hexvane.strangematter.automation.FactoryService;
import com.hexvane.strangematter.automation.TubeService;
import com.hexvane.strangematter.worldgen.GenerationCoordinator;
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
    private FactoryService factory;
    private TubeService tubes;
    private GenerationCoordinator generation;
    private EquipmentService equipment;
    private com.hexvane.strangematter.equipment.GadgetEnergyPresentation gadgetPresentation;
    private ScientistService scientists;
    private ProgressionService progression;
    private volatile com.hexvane.strangematter.telemetry.BeaconTelemetry beacon;
    private volatile com.hexvane.strangematter.diagnostics.WorldStallDiagnostics diagnostics;
    public StrangeMatterPlugin(JavaPluginInit init){super(PluginDataPaths.preserveLegacyDirectory(init));}
    public static StrangeMatterPlugin instance(){return instance;}
    public EquipmentService equipment(){return equipment;}
    public TubeService tubes(){return tubes;}
    public ProgressionService progression(){return progression;}
    @Override protected void setup(){
        try {
            var config=StrangeMatterConfig.load(getDataDirectory());
            research=new ResearchService(getDataDirectory());anomalies=new AnomalyService(getDataDirectory());
            com.hexvane.strangematter.anomaly.memory.AnomalyMemories.register(this);
            anomalies.setMemoryEncounterHook(com.hexvane.strangematter.anomaly.memory.AnomalyMemories::collectNearby);
            machines=new MachineService(getDataDirectory(),config,research,anomalies);equipment=new EquipmentService(research,anomalies,machines);
            gadgetPresentation=new com.hexvane.strangematter.equipment.GadgetEnergyPresentation();
            FactoryService.register(this);
            TubeService.register(getChunkStoreRegistry(),getEntityStoreRegistry());
            com.hexvane.strangematter.equipment.GraviticChestMarker.register(getChunkStoreRegistry());
            factory=new FactoryService(machines,research);machines.setFactory(factory);
            tubes=new TubeService(getDataDirectory(),factory);
            equipment.setTubeService(tubes);
            equipment.advancedGadgets().setChestMoveBlocker(tubes::isRecoveryBlocked);
            factory.setRecoveryBlocker(tubes::isRecoveryBlocked);
            tubes.registerSystems(getChunkStoreRegistry(),getEntityStoreRegistry());
            anomalies.setGroundingHook(machines::grounded);
            anomalies.setSuppressionHook(machines::suppressed);
            machines.setCapsuleReservationCheck(equipment::capsuleInFlight);
            scientists=new ScientistService(getDataDirectory());progression=new ProgressionService(getDataDirectory());
            generation=new GenerationCoordinator(anomalies,scientists);
            scientists.setMachineRegistrar((world,position,id)->machines.register(world,position,id));
            research.setScanHook((player,type)->{
                progression.scanned(player,type);
                var telemetry=beacon;if(telemetry!=null)telemetry.usage(com.hexvane.strangematter.telemetry.BeaconTelemetry.Feature.SCAN);
            });
            research.setCompletionHook((player,node)->{
                progression.completedResearch(player,node);
                var telemetry=beacon;if(telemetry!=null)telemetry.usage(com.hexvane.strangematter.telemetry.BeaconTelemetry.Feature.RESEARCH_UNLOCKED);
            });
            anomalies.setFirstContactHook((player,anomaly,award)->{progression.firstContact(player);if(award>0)research.addPoints(player,ResearchType.fromName(anomaly.type.researchType),award);});
            instance=this;
            getCodecRegistry(Interaction.CODEC).register("SM_Use",StrangeMatterInteraction.class,StrangeMatterInteraction.CODEC);
            getCodecRegistry(RandomTickProcedure.CODEC).register("SM_Anomalous_Grass",AnomalousGrassService.class,AnomalousGrassService.CODEC);
            getEntityStoreRegistry().registerSystem(new MachineEvents.Place(machines));
            getEntityStoreRegistry().registerSystem(new MachineEvents.Break(machines));
            getEntityStoreRegistry().registerSystem(new MachineEvents.EnvironmentBreak(machines));
            getEntityStoreRegistry().registerSystem(new com.hexvane.strangematter.research.ResearchCraftGate(research));
            getEntityStoreRegistry().registerSystem(new LaboratoryTick());
            getEntityStoreRegistry().registerSystem(new com.hexvane.strangematter.equipment.GadgetEnergyArmorUpdates());
            getEntityStoreRegistry().registerSystem(new com.hexvane.strangematter.equipment.GadgetEnergyArmorUpdates.Remember());
            getEntityStoreRegistry().registerSystem(new com.hexvane.strangematter.equipment.LevitationInputSystem());
            getEntityStoreRegistry().registerSystem(new com.hexvane.strangematter.equipment.LevitationInputSystem.RemoveSystem());
            getEntityStoreRegistry().registerSystem(anomalies.gravityInputSystem());
            getEntityStoreRegistry().registerSystem(anomalies.gravitySystem());
            getEntityStoreRegistry().registerSystem(anomalies.gravityCleanupSystem());
            getEntityStoreRegistry().registerSystem(new com.hexvane.strangematter.anomaly.GravityTerrainEvents.Place(anomalies));
            getEntityStoreRegistry().registerSystem(new com.hexvane.strangematter.anomaly.GravityTerrainEvents.Break(anomalies));
            getEntityStoreRegistry().registerSystem(new com.hexvane.strangematter.anomaly.GravityTerrainEvents.Damage(anomalies));
            getEntityStoreRegistry().registerSystem(new com.hexvane.strangematter.anomaly.GravityTerrainEvents.EnvironmentBreak(anomalies));
            getEntityStoreRegistry().registerSystem(new com.hexvane.strangematter.equipment.AdvancedGadgetEvents.Place(equipment.advancedGadgets()));
            getEntityStoreRegistry().registerSystem(new com.hexvane.strangematter.equipment.AdvancedGadgetEvents.Use(equipment.advancedGadgets()));
            getEntityStoreRegistry().registerSystem(new com.hexvane.strangematter.equipment.AdvancedGadgetEvents.Break(equipment.advancedGadgets()));
            getEntityStoreRegistry().registerSystem(new com.hexvane.strangematter.equipment.AdvancedGadgetEvents.Damage(equipment.advancedGadgets()));
            getEntityStoreRegistry().registerSystem(new com.hexvane.strangematter.equipment.AdvancedGadgetEvents.EnvironmentBreak(equipment.advancedGadgets()));
            getEntityStoreRegistry().registerSystem(new com.hexvane.strangematter.equipment.AdvancedGadgetEvents.HeldAttack());
            com.hexvane.strangematter.equipment.GraviticFlightSuspension.register(getEntityStoreRegistry());
            getEntityStoreRegistry().registerSystem(new com.hexvane.strangematter.equipment.BatteryCapePresentation());
            com.hexvane.strangematter.anomaly.ThoughtwellHallucinations.register(getEntityStoreRegistry());
            getEntityStoreRegistry().registerSystem(new com.hexvane.strangematter.equipment.HoverboardRiderPose.RestoreOnRemove());
            getChunkStoreRegistry().registerSystem(new FixtureLightingRefresh());
            getChunkStoreRegistry().registerSystem(machines.discovery());
            getChunkStoreRegistry().registerSystem(new MachineDiscovery.Sections(machines.discovery()));
            getChunkStoreRegistry().registerSystem(new MachineDiscovery.Activation(machines.discovery()));
            getCommandRegistry().registerCommand(new StrangeMatterCommand(research,anomalies,machines,scientists,progression));
            getEventRegistry().registerGlobal(PlayerReadyEvent.class,event->{
                var ref=event.getPlayerRef();if(ref==null||!ref.isValid())return;
                var store=ref.getStore();var world=store.getExternalData().getWorld();
                world.execute(()->{if(!ref.isValid())return;var p=store.getComponent(ref,PlayerRef.getComponentType());if(p!=null){research.syncRecipes(p,store);progression.reconcileResearch(p.getUuid(),research.earnedNodes(p.getUuid()));machines.welcome(p,event.getPlayer());}});
            });
            getEventRegistry().registerGlobal(ChunkPreLoadProcessEvent.class,generation::column);
            getEventRegistry().registerGlobal(EventPriority.LAST,com.hypixel.hytale.server.core.universe.world.events.ChunkSectionPreLoadProcessEvent.class,generation::section);
            getEventRegistry().registerGlobal(EventPriority.LAST,RemoveWorldEvent.class,event->{if(!event.isCancelled())cleanupWorld(event.getWorld());});
            getLogger().atInfo().log("Strange Matter initialized: six anomaly disciplines and native laboratory research.");
        }catch(Exception e){throw new IllegalStateException("Strange Matter initialization failed",e);}
    }
    @Override protected void start(){
        beacon=new com.hexvane.strangematter.telemetry.BeaconTelemetry(getClass().getClassLoader());
        diagnostics=new com.hexvane.strangematter.diagnostics.WorldStallDiagnostics(message->getLogger().atWarning().log("%s",message));
        // Loaded chunk lighting can outlive changes to block asset colors.
        for(var world:Universe.get().getWorlds().values())if(world.isAlive())
            world.execute(()->{if(instance==this){FixtureLightingRefresh.refreshLoaded(world);machines.discovery().enqueueLoaded(world);}});
    }
    private final class LaboratoryTick extends TickingSystem<EntityStore> {
        private final Set<String> pending=ConcurrentHashMap.newKeySet();
        private final java.util.Map<String,Long> lastErrors=new ConcurrentHashMap<>();
        private void tickSubsystem(World world,String name,com.hexvane.strangematter.telemetry.BeaconTelemetry.Subsystem subsystem,Runnable tick) {
            if(diagnostics!=null)diagnostics.stage(world.getName(),"Strange Matter "+name);
            var telemetry=beacon;boolean measuring=telemetry!=null&&telemetry.isActive();
            long started=measuring?System.nanoTime():0;
            try { tick.run(); }
            catch(Exception e) {
                long now=System.currentTimeMillis();String key=world.getName()+":"+name;
                if(now-lastErrors.getOrDefault(key,0L)>=5000) {
                    lastErrors.put(key,now);
                    getLogger().atSevere().withCause(e).log("Strange Matter %s tick failed in %s",name,world.getName());
                }
            } finally {
                if(measuring)telemetry.performance(subsystem,System.nanoTime()-started);
            }
        }
        @Override public void tick(float dt,int index,Store<EntityStore> store){
            var world=store.getExternalData().getWorld();
            if(diagnostics!=null)diagnostics.observe(world.getName(),world,world::isAlive);
            if(!pending.add(world.getName()))return;
            world.execute(()->{try{if(instance!=null){
                // A broken field must not starve capsule inventory receipts or mounted movement.
                tickSubsystem(world,"anomalies",com.hexvane.strangematter.telemetry.BeaconTelemetry.Subsystem.ANOMALIES,()->anomalies.tick(world,dt));
                tickSubsystem(world,"item tubes",com.hexvane.strangematter.telemetry.BeaconTelemetry.Subsystem.ITEM_TUBES,()->tubes.tick(world,dt));
                tickSubsystem(world,"machines",com.hexvane.strangematter.telemetry.BeaconTelemetry.Subsystem.MACHINES,()->machines.tick(world,dt));
                tickSubsystem(world,"equipment",com.hexvane.strangematter.telemetry.BeaconTelemetry.Subsystem.EQUIPMENT,()->equipment.tick(world,dt));
                tickSubsystem(world,"scientists",com.hexvane.strangematter.telemetry.BeaconTelemetry.Subsystem.SCIENTISTS,()->scientists.tick(world,dt));
                tickSubsystem(world,"progression",com.hexvane.strangematter.telemetry.BeaconTelemetry.Subsystem.PROGRESSION,()->progression.tick(world,dt));
                var telemetry=beacon;if(telemetry!=null)telemetry.tick();
            }}finally{pending.remove(world.getName());if(diagnostics!=null)diagnostics.stage(world.getName(),"native world or another plugin");}});
        }
    }
    @Override protected void shutdown(){
        if(gadgetPresentation!=null)gadgetPresentation.close();
        if(beacon!=null){beacon.close();beacon=null;}
        if(diagnostics!=null)diagnostics.close();
        instance=null;
        if(research!=null)research.close();
        if(anomalies!=null){
            for(var world:Universe.get().getWorlds().values())cleanupWorld(world);
            anomalies.save();
        }
        if(machines!=null)machines.close();
        if(factory!=null)factory.close();
        if(tubes!=null)tubes.close();
        if(scientists!=null)scientists.save();
        if(progression!=null)progression.save();
    }
    private void cleanupWorld(World world){
        if(diagnostics!=null)diagnostics.forget(world.getName());
        if(equipment==null||anomalies==null)return;
        Runnable cleanup=()->{equipment.cleanup(world);anomalies.stopWorld(world);if(tubes!=null)tubes.stopWorld(world);if(machines!=null)machines.cleanupPresentation(world);if(generation!=null)generation.cleanup(world);};
        if(world.isInThread()){cleanup.run();return;}
        if(!world.isAlive())return;
        try{CompletableFuture.runAsync(cleanup,world).get(5,TimeUnit.SECONDS);}
        catch(Exception e){getLogger().atWarning().withCause(e).log("World cleanup did not complete before shutdown: %s",world.getName());}
    }
}
