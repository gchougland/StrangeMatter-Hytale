package com.hexvane.strangematter.machine;

import com.hexvane.strangematter.util.WorldAccess;

import com.hexvane.strangematter.anomaly.AnomalyType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.World;
import org.joml.Vector3d;
import org.joml.Vector3i;
import java.lang.foreign.MemorySegment;
import java.util.*;

/** Native loaded assets, packet serialization and real work-to-block-state transitions. */
public final class NativeMachineWorkVerification {
    public static void verify(World world,MachineService machines){
        verifyPackets();
        var created=new ArrayList<MachineState>();var anomalyIds=new ArrayList<UUID>();
        try{
            int x=3;
            for(String id:List.of("SM_Resonance_Condenser","SM_Resonant_Burner","SM_Reality_Forge","SM_Rift_Stabilizer")){
                var type=BlockType.getAssetMap().getAsset(id);var chunk=WorldAccess.inMemory(world,com.hypixel.hytale.math.util.ChunkUtil.indexChunkFromBlock(x,3));
                WorldAccess.set(chunk,x,70,3,BlockType.getAssetMap().getIndex(id),type,1,0,0);
                var machine=machines.register(world,new Vector3i(x,70,3),id);created.add(machine);
                require(machine!=null&&machines.valid(world,machine),"Default machine registered with native identity: "+id);
                int filler=WorldAccess.filler(chunk,x,71,3);
                machine.active=true;if(id.equals("SM_Reality_Forge"))machine.recipe=machines.recipes.getFirst().id;
                require(MachineWorkEffects.sync(world,machine),"Native working state starts: "+id);
                require(machines.valid(world,machine)&&MachineService.baseId(world.getBlockType(x,71,3)).equals(id),"Tall working filler retains the same machine identity: "+id);
                require(WorldAccess.rotation(chunk,x,70,3)==1&&WorldAccess.filler(chunk,x,71,3)==filler,"State transition preserves orientation and upper filler offset: "+id);
                require(!MachineWorkEffects.sync(world,machine),"Unchanged working state does not restart its animation/audio: "+id);
                machine.enabled=false;MachineWorkEffects.sync(world,machine);
                require(world.getBlockType(x,70,3).getId().equals(id),"Disabled machine returns to silent unanimated base: "+id);
                machine.enabled=true;machine.active=false;machine.recipe="";x+=4;
            }
            var condenser=created.get(0);var burner=created.get(1);var forge=created.get(2);var stabilizer=created.get(3);
            var gravity=machines.anomalies.spawn(AnomalyType.GRAVITY,world,new Vector3d(3.5,71,3.5),true);anomalyIds.add(gravity.id);
            condenser.energy=machines.config.condenserConsumption*8;machines.tick(world,.05);
            require(condenser.progress>0&&isWorking(world,condenser),"Real powered condenser work starts native animation and hum");
            condenser.energy=0;machines.tick(world,.05);require(!isWorking(world,condenser),"No energy stops condenser animation and hum");
            condenser.energy=machines.config.condenserConsumption*8;condenser.output="SM_Gravitic_Shard";condenser.outputQuantity=new ItemStack(condenser.output,1).getItem().getMaxStack();
            int progress=condenser.progress;machines.tick(world,.05);
            require(!isWorking(world,condenser)&&condenser.progress==progress,"Full output stops presentation without advancing work");
            condenser.outputQuantity=0;condenser.output="";machines.tick(world,.05);require(isWorking(world,condenser),"Clearing output restarts real work");
            condenser.enabled=false;machines.tick(world,.05);require(!isWorking(world,condenser),"Pause is reflected on the next native machine tick");
            condenser.enabled=true;machines.anomalies.remove(gravity.id);machines.tick(world,.05);require(!isWorking(world,condenser),"Missing anomaly leaves condenser silent");
            burner.fuelTicks=2;machines.tick(world,.05);require(isWorking(world,burner),"Actual fuel consumption animates burner");
            machines.tick(world,.05);machines.tick(world,.05);require(!isWorking(world,burner),"Exhausted burner stops its model animation");
            forge.recipe=machines.recipes.getFirst().id;forge.progress=0;machines.tick(world,.05);require(isWorking(world,forge),"In-progress craft animates the forge press");
            forge.progress=machines.config.forgeCraftTicks-1;machines.tick(world,.05);
            require(forge.recipe.isEmpty()&&forge.outputQuantity>0&&!isWorking(world,forge),"Finished craft returns press to rest without looping over output");
            var rift=machines.anomalies.spawn(AnomalyType.ENERGETIC_RIFT,world,new Vector3d(15.5,71,3.5),true);anomalyIds.add(rift.id);
            machines.tick(world,.05);require(isWorking(world,stabilizer),"Actual rift harvesting animates the stabilizer core");
            machines.cleanupPresentation(world);require(created.stream().noneMatch(m->isWorking(world,m)),"Orderly cleanup stops all native working states");
            // Reconcile a persisted Working variant against current requirements on the next tick.
            condenser.active=true;MachineWorkEffects.sync(world,condenser);condenser.energy=0;
            machines.tick(world,.05);require(!isWorking(world,condenser),"Reloaded stale working state is stopped when no work can occur");
            var removed=condenser.block();world.setBlock(removed.x,removed.y,removed.z,"Empty");
            require(!MachineWorkEffects.sync(world,condenser),"Presentation never recreates a removed machine");
            require(!MachineWorkEffects.sync(world,new MachineState(world.getName(),new Vector3i(4096,70,4096),"SM_Resonance_Condenser")),"Unloaded chunks are never loaded merely for animation/audio");
        }finally{
            for(UUID id:anomalyIds)machines.anomalies.remove(id);
            for(var machine:created){MachineWorkEffects.stop(world,machine);world.setBlock(machine.x,machine.y,machine.z,"Empty");machines.removed(world,machine.block());}
        }
        System.out.println("NATIVE_MACHINE_WORK_VERIFICATION_PASSED: four native working block packets, quiet bounded ambient Vorbis loop, actual fuel/craft/condensation/rift work, no-power/full-output/pause/completion/removal/unload/reload stops, idempotent transitions, rotation and tall filler preservation.");
    }
    private static boolean isWorking(World world,MachineState machine){return MachineWorkEffects.WORKING.equals(world.getBlockType(machine.x,machine.y,machine.z).getCurrentInteractionState());}
    private static void verifyPackets(){
        int hum=SoundEvent.getAssetMap().getIndex(MachineWorkEffects.CONDENSER_HUM);require(hum!=SoundEvent.EMPTY_ID,"Condenser hum resolves as a native sound event");
        for(String id:MachineWorkEffects.MACHINES){
            var idle=BlockType.getAssetMap().getAsset(id);var working=idle.getBlockForState(MachineWorkEffects.WORKING);
            require(working!=null&&MachineService.baseId(working).equals(id),"Native working variant keeps base identity: "+id);
            require(idle.getCustomModelAnimation()==null&&idle.getAmbientSoundEventIndex()==SoundEvent.EMPTY_ID,"Idle item/block has no animation or ambient sound: "+id);
            var packet=working.toPacket();require(packet.looping&&packet.modelAnimation!=null&&packet.modelAnimation.endsWith("_working.blockyanim"),"Native asset packet carries a looping working animation: "+id);
            int expectedHum=id.equals("SM_Resonance_Condenser")?hum:id.equals("SM_Resonant_Charging_Station")||EnergyStoragePorts.storage(id)?SoundEvent.getAssetMap().getIndex("SM_Charging_Hum_SFX"):id.equals("SM_Anomaly_Nullifier")?SoundEvent.getAssetMap().getIndex("SM_Nullifier_Hum_SFX"):java.util.Set.of("SM_Resonant_Separator","SM_Flux_Furnace","SM_Pattern_Assembler").contains(id)?SoundEvent.getAssetMap().getIndex(id+"_Hum_SFX"):SoundEvent.EMPTY_ID;
            require(packet.ambientSoundEventIndex==expectedHum,"Working machine resolves its expected ambient loop: "+id);
            var bytes=MemorySegment.ofArray(new byte[packet.computeSize()]);require(packet.serialize(bytes,0)==bytes.byteSize(),"Native block packet size: "+id);
            var decoded=com.hypixel.hytale.protocol.BlockType.toObject(bytes);
            require(decoded.looping&&decoded.modelAnimation.equals(packet.modelAnimation)&&decoded.ambientSoundEventIndex==packet.ambientSoundEventIndex,"Animation and ambient routing survive actual native wire encoding: "+id);
        }
        var packet=SoundEvent.getAssetMap().getAsset(MachineWorkEffects.CONDENSER_HUM).toPacket();
        require(packet.spatialBlend==1&&packet.maxDistance==7&&packet.startAttenuationDistance==1&&packet.maxInstance==3&&packet.volume<.13f,"Hum is quiet, fully positional, bounded to seven blocks and three instances");
        require(packet.layers.length==1&&packet.layers[0].looping,"Hum has exactly one native looping layer");
        var bytes=MemorySegment.ofArray(new byte[packet.computeSize()]);packet.serialize(bytes,0);
        var decoded=com.hypixel.hytale.protocol.SoundEvent.toObject(bytes);
        require(decoded.layers[0].looping&&decoded.maxDistance==7&&decoded.maxInstance==3&&decoded.volume==packet.volume,"Quiet ambient settings survive native sound packet serialization");
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
