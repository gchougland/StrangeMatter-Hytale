package com.hexvane.strangematter.equipment;

import com.hexvane.strangematter.StrangeMatterConfig;
import com.hexvane.strangematter.StrangeMatterInteraction;
import com.hexvane.strangematter.anomaly.AnomalyService;
import com.hexvane.strangematter.block.AnomalousGrassService;
import com.hexvane.strangematter.machine.MachineService;
import com.hexvane.strangematter.machine.MachineState;
import com.google.gson.Gson;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import org.bson.BsonDocument;
import com.hexvane.strangematter.research.ResearchService;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.asset.type.blocktick.config.RandomTickProcedure;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.interaction.BlockHarvestUtils;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.WorldConfig;
import com.hypixel.hytale.server.core.universe.world.worldgen.provider.FlatWorldGenProvider;
import org.joml.Vector3i;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Test-only plugin entry point. Package it in an isolated build directory, never in the release jar. */
public final class NativeWorldVerification extends JavaPlugin {
    private ResearchService research;private AnomalyService anomalies;private MachineService machines;
    private final AtomicBoolean finished=new AtomicBoolean();
    public NativeWorldVerification(JavaPluginInit init){super(init);}
    @Override protected void setup(){
        try {
            research=new ResearchService(getDataDirectory());anomalies=new AnomalyService(getDataDirectory());
            var config=new StrangeMatterConfig();config.giveStarterTablet=false;
            machines=new MachineService(getDataDirectory(),config,research,anomalies);
            getCodecRegistry(Interaction.CODEC).register("SM_Use",StrangeMatterInteraction.class,StrangeMatterInteraction.CODEC);
            getCodecRegistry(RandomTickProcedure.CODEC).register("SM_Anomalous_Grass",AnomalousGrassService.class,AnomalousGrassService.CODEC);
            getChunkStoreRegistry().registerSystem(new com.hexvane.strangematter.block.FixtureLightingRefresh());
            getEntityStoreRegistry().registerSystem(new com.hexvane.strangematter.equipment.HoverboardRiderPose.RestoreOnRemove());
            getEntityStoreRegistry().registerSystem(anomalies.gravityInputSystem());
            getEntityStoreRegistry().registerSystem(anomalies.gravitySystem());
            getEntityStoreRegistry().registerSystem(anomalies.gravityCleanupSystem());
            getEntityStoreRegistry().registerSystem(new com.hexvane.strangematter.anomaly.GravityTerrainEvents.Place(anomalies));
            getEntityStoreRegistry().registerSystem(new com.hexvane.strangematter.anomaly.GravityTerrainEvents.Break(anomalies));
            getEntityStoreRegistry().registerSystem(new com.hexvane.strangematter.anomaly.GravityTerrainEvents.Damage(anomalies));
            getEntityStoreRegistry().registerSystem(new com.hexvane.strangematter.anomaly.GravityTerrainEvents.EnvironmentBreak(anomalies));
            com.hexvane.strangematter.anomaly.ThoughtwellHallucinations.register(getEntityStoreRegistry());
        }catch(Exception ex){throw new IllegalStateException(ex);}
    }
    @Override protected void start(){
        CompletableFuture.delayedExecutor(90,TimeUnit.SECONDS).execute(()->finish(new TimeoutException("Native world smoke exceeded90 seconds")));
        Universe.get().getUniverseReady().thenCompose(ignored->{
            var config=new WorldConfig();config.setWorldGenProvider(new FlatWorldGenProvider());config.setSpawningNPC(false);config.setIsSpawnMarkersEnabled(false);config.setBlockTicking(false);config.setCanUnloadChunks(false);
            String name="sm_verification_"+UUID.randomUUID().toString().replace("-","");
            return Universe.get().makeWorld(name,Universe.get().validateWorldPath(name),config);
        }).thenCompose(world->CompletableFuture.allOf(world.getChunkAsync(ChunkUtil.indexChunk(0,0)),world.getChunkAsync(ChunkUtil.indexChunk(1,0)))
                .thenRunAsync(()->verify(world),world))
          .whenComplete((ignored,error)->finish(error));
    }
    private void verify(World world){
        var store=world.getEntityStore().getStore();
        verifyRecipePackets();
        try {com.hexvane.strangematter.ui.NativeCognitionSymbolsVerification.verify();}
        catch(Exception ex){throw new IllegalStateException(ex);}
        try {com.hexvane.strangematter.research.ResearchNoteVerification.verify();com.hexvane.strangematter.research.ResearchUnlockVerification.verify(world);}
        catch(Exception ex){throw new IllegalStateException(ex);}
        verifyReservedMetadata();
        verifyForgeIngredients();
        com.hexvane.strangematter.machine.NativeMachineVerification.verify(machines,research);
        com.hexvane.strangematter.machine.NativeMachineVerification.verifyGrounding(world,machines);
        try {NativeLaboratorySelectionVerification.verify(world,research,machines);}
        catch(Exception ex){throw new IllegalStateException(ex);}
        try {NativeResearchTabletVerification.verify(world,research);}
        catch(Exception ex){throw new IllegalStateException(ex);}
        try {com.hexvane.strangematter.machine.MachineControlsVerification.verify(world,machines);}
        catch(Exception ex){throw new IllegalStateException(ex);}
        verifyConduits(world);
        verifyPresentationPackets();
        com.hexvane.strangematter.effects.NativeEnergeticPresentationVerification.verify();
        NativeHeldLightVerification.verify();
        com.hexvane.strangematter.block.NativeGrassStatusIconVerification.verify();
        try {com.hexvane.strangematter.block.NativeFixtureLightingVerification.verify(world);}
        catch(Exception ex){throw new IllegalStateException(ex);}
        com.hexvane.strangematter.machine.NativeMachineWorkVerification.verify(world,machines);
        world.setBlock(4,8,4,"SM_Resonant_Burner");
        require(world.getBlockType(4,8,4).getId().equals("SM_Resonant_Burner"),"Native custom block placement");
        var burner=machines.register(world,new Vector3i(4,8,4),"SM_Resonant_Burner");burner.fuelTicks=20;
        for(int i=0;i<20;i++)machines.tick(world,.05);
        require(burner.energy==400&&burner.fuelTicks==0,"Native world burner runs original20 RF/tick for20 ticks");

        world.setBlock(7,8,7,"Soil_Dirt");var dirt=world.getBlockType(7,8,7);var breaking=dirt.getGathering().getBreaking();
        int before=store.getEntityCountFor(ItemComponent.getComponentType());var section=world.getChunkStore().getChunkSectionReferenceAtBlock(7,8,7);
        BlockHarvestUtils.performBlockBreak(new Vector3i(7,8,7),dirt,null,breaking.getQuantity(),breaking.getItemId(),breaking.getDropListId(),0,null,section,store,world.getChunkStore().getStore());
        require(world.getBlockType(7,8,7).getId().equals("Empty"),"Native block gathering removes block");
        require(store.getEntityCountFor(ItemComponent.getComponentType())>before,"Native gathering emits real dropped items");

        var fields=new LaboratoryFields(machines);fields.temporal(world,new Vector3i(16,16,16));
        long count=machines.inWorld(world).stream().filter(m->m.id.equals("SM_Time_Dilation_Block")).count();
        require(count>=19&&count<=81,"Chrono impact places native ragged block volume");
        for(int i=0;i<602;i++)fields.tick(world,.05);
        require(machines.inWorld(world).stream().noneMatch(m->m.id.equals("SM_Time_Dilation_Block")),"Every temporal voxel expires and unregisters after30 seconds");
        require(world.getBlockType(16,16,16).getId().equals("Empty"),"Temporal core returns to native air");
        fields.cleanup(world);machines.save();
        verifyCapsuleCraft(world);
        try {NativePlayerFixture.verifyPersistence(world);NativeEquipmentVerification.verify(world,research,anomalies,machines,getDataDirectory());}
        catch(Exception ex){throw new IllegalStateException(ex);}
        verifyPortalChannels(world);
        com.hexvane.strangematter.anomaly.NativeAnomalyRevisionVerification.verify(world);
        try {com.hexvane.strangematter.anomaly.NativeRiftHatVerification.verify(world);NativeMobilityRevisionVerification.verify(world);com.hexvane.strangematter.anomaly.NativeTerrainHostVerification.verify(world);}
        catch(Exception ex){throw new IllegalStateException(ex);}
        try {
            NativeHoverboardRiderVerification.verify(world);
            NativeHoverboardPresentationVerification.verify(world);
            com.hexvane.strangematter.anomaly.NativeThoughtwellVerification.verify(world);
            com.hexvane.strangematter.anomaly.NativeGravityVerification.verify(world);
            com.hexvane.strangematter.anomaly.NativeGravityTerrainVerification.verify(world);
            com.hexvane.strangematter.anomaly.NativeRiftExposureVerification.verify(world);
        }
        catch(Exception ex){throw new IllegalStateException(ex);}
    }
    private void verifyConduits(World world) {
        int[][] faces={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        var center=new Vector3i(12,10,12);
        world.setBlock(center.x,center.y,center.z,"SM_Resonant_Conduit");
        var conduit=machines.register(world,center,"SM_Resonant_Conduit");
        for(int mask=0;mask<64;mask++) {
            for(int i=0;i<6;i++) {
                var d=faces[i];int x=center.x+d[0],y=center.y+d[1],z=center.z+d[2];
                // A tall burner below the hub occupies its block with a native filler.
                // Compact conduits exercise all six faces without overlapping either model.
                world.setBlock(x,y,z,(mask&(1<<i))!=0?"SM_Resonant_Conduit":"Empty");
            }
            for(int i=0;i<11;i++)machines.tick(world,.05);
            var type=world.getBlockType(center.x,center.y,center.z);
            require(("Connection"+String.format(java.util.Locale.ROOT,"%02d",mask)).equals(type.getCurrentInteractionState()),"Native conduit mask "+mask+" after neighbor replacement: "+type.getId());
            require(machines.valid(world,conduit),"Visual-state change retains registered conduit identity");
            require(machines.register(world,center,type.getId())==conduit,"Interaction with generated conduit state preserves machine data");
        }
        for(var d:faces)world.setBlock(center.x+d[0],center.y+d[1],center.z+d[2],"Empty");
        world.setBlock(center.x+1,center.y,center.z,"SM_Resonant_Burner");
        for(int i=0;i<11;i++)machines.tick(world,.05);
        require("Connection01".equals(world.getBlockType(center.x,center.y,center.z).getCurrentInteractionState()),"Conduit connects to a different native machine family");
        world.setBlock(center.x+1,center.y,center.z,"Empty");
        world.setBlock(center.x,center.y,center.z,"Empty");machines.removed(world,center);
        System.out.println("CONDUIT_VERIFICATION: all64 native connection states survived registration and real neighbor changes.");
    }
    private void verifyPresentationPackets() {
        for(var type:com.hexvane.strangematter.anomaly.AnomalyType.values()) {
            var sound=com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent.getAssetMap().getAsset(type.particleId+"_Loop");
            require(sound!=null,"Anomaly point-source sound exists: "+type);
            var packet=sound.toPacket();
            require(packet.spatialBlend==1&&packet.startAttenuationDistance==1.5f&&packet.maxDistance==18,"Native anomaly packet carries full spatial blend and finite distance falloff");
        }
        var bench=com.hypixel.hytale.server.core.asset.type.item.config.Item.getAssetMap().getAsset("SM_Laboratory_Bench");
        require(bench!=null&&bench.toPacket()!=null,"Laboratory bench has a native client item packet");
    }
    private void verifyPortalChannels(World world) {
        var gate=anomalies.spawn(com.hexvane.strangematter.anomaly.AnomalyType.WARP_GATE,world,new org.joml.Vector3d(8,12,8),false);
        anomalies.setPortalChannel(gate.id,1);
        require(gate.particleId().equals("SM_Warp_Gate_Cyan"),"First warp endpoint chooses cyan particles and core");
        anomalies.setPortalChannel(gate.id,2);
        require(gate.particleId().equals("SM_Warp_Gate_Purple"),"Second warp endpoint chooses violet particles and core");
        anomalies.save();
        require(new AnomalyService(getDataDirectory()).get(gate.id).orElseThrow().portalChannel==2,"Portal channel survives save/reload");
        anomalies.stopWorld(world);
    }
    private void verifyRecipePackets(){
        var assets=com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe.getAssetMap();
        var init=(com.hypixel.hytale.protocol.packets.assets.UpdateRecipes)new com.hypixel.hytale.server.core.modules.item.CraftingRecipePacketGenerator()
                .generateInitPacket(assets,assets.getAssetMap());
        require(init.getId()==60&&init.type==com.hypixel.hytale.protocol.UpdateType.Init,"Native recipe generator produces client packet 60 UpdateRecipes Init");
        int bytes=init.computeSize();
        var wire=java.lang.foreign.MemorySegment.ofArray(new byte[bytes]);
        require(init.serialize(wire,0)==bytes,"Native UpdateRecipes wire serialization writes its exact calculated size");
        var decoded=com.hypixel.hytale.protocol.packets.assets.UpdateRecipes.toObject(wire);
        require(decoded.recipes.keySet().equals(init.recipes.keySet()),"Native UpdateRecipes wire decode retains every loaded recipe");
        var problems=new java.util.ArrayList<String>();int tested=0;
        for(var entry:decoded.recipes.entrySet()){
            String id=entry.getKey();if(!id.startsWith("SM_"))continue;
            tested++;var recipe=entry.getValue();
            if(recipe==null){problems.add(id+": null recipe");continue;}
            if(!id.equals(recipe.id))problems.add(id+": packet id differs from map key");
            verifyPacketMaterial(problems,id+" primaryOutput",recipe.primaryOutput,true);
            if(recipe.inputs==null||recipe.inputs.length==0)problems.add(id+": missing inputs");
            else for(int i=0;i<recipe.inputs.length;i++)verifyPacketMaterial(problems,id+" input["+i+"]",recipe.inputs[i],false);
            if(recipe.outputs==null||recipe.outputs.length==0)problems.add(id+": missing outputs");
            else {
                for(int i=0;i<recipe.outputs.length;i++)verifyPacketMaterial(problems,id+" output["+i+"]",recipe.outputs[i],true);
                if(recipe.primaryOutput!=null&&java.util.Arrays.stream(recipe.outputs).noneMatch(recipe.primaryOutput::equals))
                    problems.add(id+": primaryOutput is not one of the actual outputs");
            }
        }
        require(tested>=30,"Expected the mod's loaded generated and standalone recipes in UpdateRecipes");
        for(String id:java.util.List.of("SM_Raw_Resonite","SM_Resonite_Ingot_From_Block","SM_Resonite_Ingot_From_Nuggets"))
            if(!decoded.recipes.containsKey(id))problems.add(id+": standalone recipe failed to load");
        System.out.println("RECIPE_PACKET_VERIFICATION: inspected "+tested+" Strange Matter recipes in actual "+bytes+"-byte UpdateRecipes wire roundtrip.");
        require(problems.isEmpty(),"Client recipe packet invariants failed:\n"+String.join("\n",problems));
    }
    private static void verifyPacketMaterial(java.util.List<String> problems,String label,com.hypixel.hytale.protocol.MaterialQuantity material,boolean output){
        if(material==null){problems.add(label+": null material");return;}
        if(material.quantity<=0)problems.add(label+": nonpositive quantity");
        if(material.itemId!=null){
            if(com.hypixel.hytale.server.core.asset.type.item.config.Item.getAssetMap().getAsset(material.itemId)==null)
                problems.add(label+": unresolved ItemId "+material.itemId);
        }else if(!output&&material.resourceTypeId!=null){
            if(com.hypixel.hytale.server.core.asset.type.item.config.ResourceType.getAssetMap().getAsset(material.resourceTypeId)==null)
                problems.add(label+": unresolved ResourceTypeId "+material.resourceTypeId);
        }else problems.add(label+": no resolvable "+(output?"output item":"item or resource type"));
    }
    private void verifyReservedMetadata(){
        String token=UUID.randomUUID()+":"+UUID.randomUUID();
        var metadata=BsonDocument.parse("{\"nested\":{\"rank\":3,\"labels\":[\"cyan\",\"purple\"]}}");
        var capsule=new ItemStack("SM_Containment_Capsule_Gravity",1,metadata).withMetadata("SMAnomaly",Codec.STRING,token);
        var gun=new ItemStack("SM_Warp_Gun",1,metadata).withDurability(37);
        var state=new MachineState("verification",new Vector3i(1,2,3),"SM_Reality_Forge");
        state.reservedInputs.add(MachineState.ReservedInput.from(capsule));state.reservedInputs.add(MachineState.ReservedInput.from(gun));
        state.fuelQueue.add(new MachineState.FuelCharge("SM_Resonite_Ingot",200,metadata.toJson()));
        var gson=new Gson();var loaded=gson.fromJson(gson.toJson(state),MachineState.class);
        var returned=loaded.reservedInputs.getFirst().toItemStack();
        require(token.equals(returned.getFromMetadataOrNull("SMAnomaly",Codec.STRING)),"Contained anomaly token survives reserved-input JSON save/reload/refund");
        require(returned.getQuantity()==1&&returned.getMetadata().equals(capsule.getMetadata()),"Nested BSON and capsule quantity survive reservation");
        require(loaded.reservedInputs.get(1).toItemStack().getDurability()==37,"Reserved durable tool retains durability");
        require(loaded.fuelQueue.getFirst().toItemStack().getMetadata().equals(metadata),"Fuel queue refunds preserve nested metadata");
    }
    private void verifyForgeIngredients(){
        var inventory=new com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer((short)8);
        inventory.addItemStack(new ItemStack("Wood_Hardwood_Planks",1),true,false,false);
        inventory.addItemStack(new ItemStack("Wood_Goldenwood_Planks",2),true,false,false);
        var taken=com.hexvane.strangematter.util.InventoryOps.take(inventory,java.util.Map.of("resource:Wood_Planks",3));
        require(taken!=null&&taken.stream().mapToInt(ItemStack::getQuantity).sum()==3,"Forge accepts mixed native plank families");
        require(taken.stream().anyMatch(s->s.getItemId().equals("Wood_Hardwood_Planks"))&&taken.stream().anyMatch(s->s.getItemId().equals("Wood_Goldenwood_Planks")),"Forge reserves actual plank variants for refunds");
        var capsule=new ItemStack("SM_Containment_Capsule_Gravity",1).withMetadata("SMAnomaly",Codec.STRING,"pending-flight");
        inventory.addItemStack(capsule,true,false,false);
        var excluded=com.hexvane.strangematter.util.InventoryOps.take(inventory,java.util.Map.of(capsule.getItemId(),1),s->s.getFromMetadataOrNull("SMAnomaly",Codec.STRING)==null);
        require(excluded==null&&com.hexvane.strangematter.util.InventoryOps.count(inventory,capsule.getItemId())==1,"Reserved capsule cannot pay forge cost or be removed on failure");
    }
    private void verifyCapsuleCraft(World world){
        var anomaly=anomalies.spawn(com.hexvane.strangematter.anomaly.AnomalyType.GRAVITY,world,new org.joml.Vector3d(10,12,10),true);
        String token=anomalies.capture(anomaly.id).orElseThrow().token();
        var capsule=new ItemStack("SM_Containment_Capsule_Gravity",1).withMetadata("SMAnomaly",Codec.STRING,token);
        require(machines.availableIngredient(capsule,false),"A real captured anomaly is a valid forge ingredient");
        machines.setCapsuleReservationCheck(token::equals);
        require(!machines.availableIngredient(capsule,false),"A pending flight cannot also fund a forge recipe");
        machines.setCapsuleReservationCheck(value->false);
        world.setBlock(10,8,10,"SM_Reality_Forge");
        var forge=machines.register(world,new Vector3i(10,8,10),"SM_Reality_Forge");
        forge.reservedInputs.add(MachineState.ReservedInput.from(capsule));forge.recipe="hoverboard";forge.progress=99;
        require(!machines.availableIngredient(capsule,false),"One captured identity cannot fund two simultaneous crafts");
        machines.tick(world,.05);
        require(forge.output.equals("SM_Hoverboard")&&forge.outputQuantity==1,"Forge commits its intended output");
        require(!machines.availableIngredient(capsule,false)&&anomalies.get(anomaly.id).isEmpty(),"Completed crafting retires the contained identity and stale copies");
        try{
            var reloaded=new MachineService(getDataDirectory(),machines.config,research,anomalies);
            require(reloaded.get(world,forge.block()).outputQuantity==1&&!reloaded.availableIngredient(capsule,false),"Output and spent-capsule receipt survive restart together");
        }catch(java.io.IOException ex){throw new IllegalStateException(ex);}
    }
    private void finish(Throwable error){
        if(!finished.compareAndSet(false,true))return;
        String message=error==null?"NATIVE_WORLD_VERIFICATION_PASSED: colored grass border and Thoughtwell icon, surfing Action packets with preserved avatar and skin, energetic presentation and directed stabilizer arcs, scheduled rift exposure and grounding, fixtures, machines, mounted player lifecycle, inventory recovery, research/recipes, fuel/Forge controls, capsules, hammer, terrain, conduits and anomaly lifecycles.":"NATIVE_WORLD_VERIFICATION_FAILED: "+error;
        System.out.println(message);if(error!=null)error.printStackTrace();
        try{Files.writeString(Path.of("native-world-result.txt"),message+"\n");}catch(Exception ex){ex.printStackTrace();}
        CompletableFuture.runAsync(()->HytaleServer.get().shutdownServer());
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    @Override protected void shutdown(){if(research!=null)research.close();if(machines!=null)machines.close();if(anomalies!=null)anomalies.save();}
}
