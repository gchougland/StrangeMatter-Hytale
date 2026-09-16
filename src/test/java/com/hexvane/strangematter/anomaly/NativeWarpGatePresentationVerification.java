package com.hexvane.strangematter.anomaly;

import com.hexvane.strangematter.equipment.NativePlayerFixture;
import com.hexvane.strangematter.util.WorldAccess;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.Position;
import com.hypixel.hytale.protocol.TeleportAck;
import com.hypixel.hytale.protocol.packets.player.ClientMovement;
import com.hypixel.hytale.protocol.packets.player.ClientTeleport;
import com.hypixel.hytale.protocol.packets.world.SpawnParticleSystem;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.particle.config.ParticleSpawner;
import com.hypixel.hytale.server.core.asset.type.particle.config.ParticleSystem;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.teleport.PendingTeleport;
import com.hypixel.hytale.server.core.universe.world.World;
import org.joml.Vector3d;
import org.joml.Vector3i;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Actual native gate crossings, ACKs, player-specific particles and bounded visual lifetimes. */
public final class NativeWarpGatePresentationVerification {
    private record Cell(int block,int rotation,int filler){}
    private static final double Y=281;
    public static void verify(World world)throws Exception{
        var clock=new AtomicLong();var effects=new HytaleAnomalyEffects(clock::get);var saved=new LinkedHashMap<Vector3i,Cell>();
        try{
            assets();
            for(var pad:List.of(new Vector3i(8,280,8),new Vector3i(20,280,20),new Vector3i(24,280,20),new Vector3i(12,280,8)))pad(world,pad,saved);
            try(var subject=NativePlayerFixture.create(world,"NativeGateCooldown",new Vector3d(8.5,Y,8.5));
                var observer=NativePlayerFixture.create(world,"NativeGateObserver",new Vector3d(20.5,Y,20.5))){
                subject.player().handleClientReady(false);observer.player().handleClientReady(false);subject.store().tick(.05f);
                var source=gate(world,new Vector3d(8.5,Y,8.5),0);var destination=gate(world,new Vector3d(20.5,Y,20.5),0);
                clear(subject,observer);effects.teleport(world,source,destination);
                require(subject.packets().ofType(ClientTeleport.class).size()==1&&observer.packets().ofType(ClientTeleport.class).isEmpty(),"A real gate crossing moves only its native contacting player");
                require(effects.warpCoolingDown(subject.ref())&&!effects.warpCoolingDown(observer.ref()),"Real crossing starts one player's cooldown without closing the other player's route");
                acknowledge(subject);subject.store().tick(.05f);
                pairPulse(effects,world,destination,false,subject,"Closed",observer,"Open");
                clock.set(4_999_999_999L);
                pairPulse(effects,world,destination,false,subject,"Closed",observer,"Open");
                var returnSource=gate(world,position(subject),0);
                clear(subject,observer);effects.teleport(world,returnSource,source);
                require(subject.packets().ofType(ClientTeleport.class).isEmpty(),"The authoritative gate still rejects crossing immediately before five seconds");
                clock.set(5_000_000_000L);
                require(!effects.warpCoolingDown(subject.ref()),"Cooldown expires exactly at five seconds without waiting for cleanup");
                pairPulse(effects,world,destination,false,subject,"Open",observer,"Open");
                clear(subject,observer);effects.teleport(world,returnSource,source);
                require(subject.packets().ofType(ClientTeleport.class).size()==1&&effects.warpCoolingDown(subject.ref()),"The same crossing is accepted at the exact visual reopen boundary before cleanup runs");
                var delayedTeleport=subject.packets().ofType(ClientTeleport.class).getLast();
                require(subject.store().getComponent(subject.ref(),PendingTeleport.getComponentType())!=null&&!subject.owner().getTeleportAckTracker().isEmpty(),"Return trip awaits the real native client acknowledgment");
                clock.set(10_000_000_000L);
                require(!effects.warpCoolingDown(subject.ref()),"The next personal cooldown also expires independently of cleanup");
                pairPulse(effects,world,destination,false,subject,"Closed",observer,"Open");
                // Particle recording must not erase the actual teleport packet before its ACK.
                acknowledge(subject,delayedTeleport.teleportId,new Vector3d(delayedTeleport.modelTransform.position.x,delayedTeleport.modelTransform.position.y,delayedTeleport.modelTransform.position.z));subject.store().tick(.05f);
                pairPulse(effects,world,destination,false,subject,"Open",observer,"Open");
                for(int channel=0;channel<=2;channel++){
                    destination.portalChannel=channel;
                    pairPulse(effects,world,destination,true,subject,"Closed",observer,"Closed");
                    pairPulse(effects,world,destination,false,subject,"Open",observer,"Open");
                    clear(subject,observer);for(int count=0;count<4;count++)effects.gateParticles(world,destination,false);
                    pulses(subject,destination,"Open",4);pulses(observer,destination,"Open",4);
                }
                range(effects,world,subject,observer);
                clear(subject,observer);effects.teleport(world,gate(world,position(subject),0),destination);
                require(subject.packets().ofType(ClientTeleport.class).size()==1&&effects.warpCoolingDown(subject.ref()),"Cleanup fixture begins with a real personal cooldown");
                acknowledge(subject);subject.store().tick(.05f);
                observer.packets().packets.clear();effects.teleport(world,gate(world,position(observer),0),source);
                require(observer.packets().ofType(ClientTeleport.class).size()==1&&effects.warpCoolingDown(subject.ref())&&effects.warpCoolingDown(observer.ref()),"A fresh second player can actually traverse while the first player is still cooling down");
                acknowledge(observer);effects.restore(world);
                require(!effects.warpCoolingDown(subject.ref())&&!effects.warpCoolingDown(observer.ref()),"World cleanup retires both personal cooldowns");
                serviceCadence(world,subject,observer);
            }
        }finally{effects.restore(world);WarpGateTeleport.clear(world);restore(world,saved);}
        System.out.println("NATIVE_WARP_GATE_PRESENTATION_VERIFICATION_PASSED: actual individual crossings, two-player open/closed isolation, exact five-second boundary before cleanup, delayed native ACK, all three channels, unavailable/range routing, frame-safe finite one-shot assets and native wire fields, 5Hz service presentation without legacy broadcast, second-player traversal and world cleanup.");
    }
    private static void range(HytaleAnomalyEffects effects,World world,NativePlayerFixture subject,NativePlayerFixture observer){
        var p=position(observer);var boundary=gate(world,new Vector3d(p).add(96,0,0),0);
        clear(subject,observer);effects.gateParticles(world,boundary,false);
        pulses(observer,boundary,"Open",1);require(subject.packets().ofType(SpawnParticleSystem.class).isEmpty(),"A farther player outside 96 blocks receives no gate pulse");
        boundary.x+=.001;long chunk=ChunkUtil.indexChunkFromBlock((int)Math.floor(boundary.x),(int)Math.floor(boundary.z));var resident=WorldAccess.inMemory(world,chunk);
        clear(subject,observer);effects.gateParticles(world,boundary,false);
        require(subject.packets().ofType(SpawnParticleSystem.class).isEmpty()&&observer.packets().ofType(SpawnParticleSystem.class).isEmpty(),"A gate just beyond the 96-block boundary emits no viewer packets");
        require(WorldAccess.inMemory(world,chunk)==resident,"Visual range checks never load remote terrain");
    }
    private static void serviceCadence(World world,NativePlayerFixture subject,NativePlayerFixture observer)throws Exception{
        var service=new AnomalyService(Files.createTempDirectory("sm-gate-presentation-"));service.naturalGeneration=false;
        try{
            var gate=service.spawn(AnomalyType.WARP_GATE,world,new Vector3d(16.5,Y,16.5),false);service.setPortalChannel(gate.id,1);
            clear(subject,observer);for(int tick=0;tick<5;tick++)service.tick(world,.05);
            pulses(subject,gate,"Closed",2);pulses(observer,gate,"Closed",2);
            require(subject.packets().ofType(ClientTeleport.class).isEmpty()&&observer.packets().ofType(ClientTeleport.class).isEmpty(),"Unpaired gun aperture remains closed and presentation does not teleport observers");
            service.setSuppressionHook((w,p,type)->true);clear(subject,observer);service.tick(world,.2);
            pulses(subject,gate,"Closed",1);pulses(observer,gate,"Closed",1);
            service.remove(gate.id);clear(subject,observer);service.tick(world,.25);
            require(subject.packets().ofType(SpawnParticleSystem.class).isEmpty()&&observer.packets().ofType(SpawnParticleSystem.class).isEmpty(),"Removed gate stops refreshing its finite aperture");
            service.setSuppressionHook((w,p,type)->false);
            var retry=service.spawn(AnomalyType.WARP_GATE,world,new Vector3d(16.5,Y,16.5),false);retry.primary=5;
            clear(subject,observer);service.tick(world,.05);pulses(subject,retry,"Closed",1);pulses(observer,retry,"Closed",1);
            for(int tick=0;tick<19;tick++){
                clear(subject,observer);service.tick(world,.25);pulses(subject,retry,"Closed",1);pulses(observer,retry,"Closed",1);
            }
            clear(subject,observer);service.tick(world,.25);pulses(subject,retry,"Open",1);pulses(observer,retry,"Open",1);
            require(!retry.creatingPair&&retry.pairedGate==null&&subject.packets().ofType(ClientTeleport.class).isEmpty()&&observer.packets().ofType(ClientTeleport.class).isEmpty(),"Natural gate reopens after its retry deadline without preparing terrain or teleporting distant observers");
        }finally{service.stopWorld(world);}
    }
    private static void assets(){
        for(var base:List.of("SM_Warp_Gate","SM_Warp_Gate_Cyan","SM_Warp_Gate_Purple"))for(var state:List.of("Open","Closed")){
            var id=base+"_"+state;var system=ParticleSystem.getAssetMap().getAsset(id);
            require(system!=null&&system.getLifeSpan()>=.35f&&system.getLifeSpan()<=.500001f,"Finite aperture system allows a delayed first client update and whole particle lifetime: "+id);
            require(system.getSpawners()!=null&&system.getSpawners().length>0,"Aperture has actual native spawners: "+id);
            for(var group:system.getSpawners()){
                var spawner=ParticleSpawner.getAssetMap().getAsset(group.getSpawnerId());
                require(spawner!=null&&spawner.getLifeSpan()==0&&spawner.getParticleLifeSpan()!=null&&spawner.getParticleLifeSpan().min>.2f
                        &&spawner.getParticleLifeSpan().max<=.250001f,"One-shot emitter survives first client update; its particles have a finite short fade: "+group.getSpawnerId());
                var wire=spawner.toPacket();var groupWire=group.toPacket();
                require(groupWire.startDelay==0&&groupWire.totalSpawners==1&&wire.lifeSpan==0&&wire.spawnBurst
                        &&wire.waveDelay!=null&&wire.waveDelay.min==0&&wire.waveDelay.max==0,"Native wire fields request one immediately available burst without subframe expiry: "+group.getSpawnerId());
                require(wire.totalParticles!=null&&wire.totalParticles.min>0&&wire.totalParticles.min==wire.totalParticles.max
                        &&wire.spawnRate!=null&&wire.spawnRate.min==wire.totalParticles.min&&wire.spawnRate.max==wire.totalParticles.max
                        &&wire.maxConcurrentParticles==wire.totalParticles.max,"Finite total budget prevents the lifetime-free emitter from repeating: "+group.getSpawnerId());
            }
        }
    }
    private static void pairPulse(HytaleAnomalyEffects effects,World world,AnomalyRecord gate,boolean unavailable,NativePlayerFixture first,String firstState,NativePlayerFixture second,String secondState){
        clear(first,second);effects.gateParticles(world,gate,unavailable);pulses(first,gate,firstState,1);pulses(second,gate,secondState,1);
    }
    private static void pulses(NativePlayerFixture player,AnomalyRecord gate,String state,int count){
        var packets=player.packets().ofType(SpawnParticleSystem.class);require(packets.size()==count,"Exactly "+count+" private "+state+" pulses reach "+player.owner().getUsername()+", got "+packets.size());
        for(var packet:packets){
            require((gate.particleId()+"_"+state).equals(packet.particleSystemId),"A viewer receives only its own channel/state, without the legacy broadcast aperture");
            require(packet.position!=null&&packet.position.x==gate.x&&packet.position.y==gate.y&&packet.position.z==gate.z&&packet.scale==1,"Aperture retains the exact gate location and scale");
            require(packet.maxDuration==0,"Packet uses finite asset lifetime without a competing client duration clamp");
            var bytes=MemorySegment.ofArray(new byte[packet.computeSize()]);require(packet.serialize(bytes,0)==bytes.byteSize()&&SpawnParticleSystem.toObject(bytes).equals(packet),"Actual private particle packet survives native wire serialization");
        }
    }
    private static AnomalyRecord gate(World world,Vector3d position,int channel){var gate=new AnomalyRecord(UUID.randomUUID(),AnomalyType.WARP_GATE,world.getName(),position,false);gate.portalChannel=channel;return gate;}
    private static Vector3d position(NativePlayerFixture player){return new Vector3d(player.store().getComponent(player.ref(),TransformComponent.getComponentType()).getPosition());}
    private static void clear(NativePlayerFixture... players){for(var player:players)player.packets().packets.clear();}
    private static void acknowledge(NativePlayerFixture player){var teleport=player.packets().ofType(ClientTeleport.class).getLast();acknowledge(player,teleport.teleportId,new Vector3d(teleport.modelTransform.position.x,teleport.modelTransform.position.y,teleport.modelTransform.position.z));}
    private static void acknowledge(NativePlayerFixture player,byte id,Vector3d position){
        var movement=new ClientMovement();movement.teleportAck=new TeleportAck(id);movement.absolutePosition=new Position((float)position.x,(float)position.y,(float)position.z);
        var bytes=MemorySegment.ofArray(new byte[movement.computeSize()]);movement.serialize(bytes,0);player.packets().handle(ClientMovement.toObject(bytes));player.world().consumeTaskQueue();
        require(player.owner().getTeleportAckTracker().isEmpty()&&player.store().getComponent(player.ref(),PendingTeleport.getComponentType())==null,"Real client ACK drains native teleport state");
    }
    private static void pad(World world,Vector3i center,Map<Vector3i,Cell> saved){
        int stone=BlockType.getAssetMap().getIndex("Rock_Stone");require(stone>=0,"Native footing asset loaded");
        for(int x=center.x-1;x<=center.x+1;x++)for(int z=center.z-1;z<=center.z+1;z++){
            var chunk=WorldAccess.loaded(world,ChunkUtil.indexChunkFromBlock(x,z));require(chunk!=null,"Warp presentation fixture stays within already-loaded columns");
            for(int y=center.y;y<ChunkUtil.HEIGHT;y++){
                var section=WorldAccess.section(chunk,y);require(section!=null,"Native fixture section loaded");var cell=new Vector3i(x,y,z);
                saved.putIfAbsent(cell,new Cell(section.get(x,y,z),section.getRotationIndex(x,y,z),section.getFiller(x,y,z)));section.set(x,y,z,y==center.y?stone:0,0,0);
            }
            WorldAccess.column(chunk).updateHeight(x,z);
        }
    }
    private static void restore(World world,Map<Vector3i,Cell> saved){
        var columns=new HashSet<Vector3i>();for(var entry:saved.entrySet()){
            var p=entry.getKey();var value=entry.getValue();var chunk=WorldAccess.loaded(world,ChunkUtil.indexChunkFromBlock(p.x,p.z));
            WorldAccess.section(chunk,p.y).set(p.x,p.y,p.z,value.block,value.rotation,value.filler);columns.add(new Vector3i(p.x,0,p.z));
        }
        for(var p:columns)WorldAccess.column(WorldAccess.loaded(world,ChunkUtil.indexChunkFromBlock(p.x,p.z))).updateHeight(p.x,p.z);
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
