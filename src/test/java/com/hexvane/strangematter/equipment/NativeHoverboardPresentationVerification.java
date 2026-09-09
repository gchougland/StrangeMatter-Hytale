package com.hexvane.strangematter.equipment;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.protocol.AudioUpdate;
import com.hypixel.hytale.protocol.packets.world.SpawnParticleSystem;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.asset.type.particle.config.ParticleSpawner;
import com.hypixel.hytale.server.core.asset.type.particle.config.ParticleSystem;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.modules.entity.component.AudioComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.system.AudioSystems;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.List;
import org.joml.Vector3d;

/** Native entity audio tracking and real nearby particle packets, without client renderer claims. */
public final class NativeHoverboardPresentationVerification {
    public static void verify(World world) {
        var store=world.getEntityStore().getStore();store.assertThread();
        int sound=SoundEvent.getAssetMap().getIndex(HoverboardRideEffects.HUM);
        require(sound!=SoundEvent.EMPTY_ID,"Quiet riding event resolves in native assets");
        var event=SoundEvent.getAssetMap().getAsset(sound).toPacket();
        var bytes=MemorySegment.ofArray(new byte[event.computeSize()]);event.serialize(bytes,0);
        var decoded=com.hypixel.hytale.protocol.SoundEvent.toObject(bytes);
        require(decoded.spatialBlend==1&&decoded.maxDistance==8&&decoded.volume<=.16f&&decoded.layers.length==1&&decoded.layers[0].looping,
                "Native riding sound packet is a quiet positional eight-block loop");
        for(String id:List.of(HoverboardRideEffects.GLIDE,HoverboardRideEffects.IDLE)) {
            var system=ParticleSystem.getAssetMap().getAsset(id);require(system!=null,"Native riding effect exists: "+id);
            int total=0;
            for(var emitter:system.toPacket().spawners) {
                var particle=ParticleSpawner.getAssetMap().getAsset(emitter.spawnerId).toPacket();total+=particle.totalParticles.max;
                require(particle.particleLifeSpan.max<=.5f&&particle.lifeSpan<=.1f,"Riding trails expire promptly after dismount");
            }
            require(total<=4,"Two coils remain bounded to eight particles per movement pulse");
        }
        try(var near=NativePlayerFixture.create(world,"NativeBoardAudio",new Vector3d(20.5,18,20.5));
            var far=NativePlayerFixture.create(world,"NativeBoardDistant",new Vector3d(50.5,18,20.5))) {
            var holder=EntityStore.REGISTRY.newHolder();
            var origin=new Vector3d(20.5,17,20.5);
            var transform=new TransformComponent(new Vector3d(origin),new Rotation3f());
            holder.addComponent(TransformComponent.getComponentType(),transform);
            holder.addComponent(NetworkId.getComponentType(),new NetworkId(world.getEntityStore().takeNextNetworkId()));
            var movement=new MovementStatesComponent();holder.addComponent(MovementStatesComponent.getComponentType(),movement);
            var visible=new EntityTrackerSystems.Visible();holder.addComponent(EntityTrackerSystems.Visible.getComponentType(),visible);
            HoverboardRideEffects.attach(holder);
            require(holder.getComponent(AudioComponent.getComponentType()).getSoundEventIds().length==1,"Holder owns one native sound rather than periodic play messages");
            var board=store.addEntity(holder,AddReason.SPAWN);
            try {
                var viewer=store.getComponent(near.ref(),EntityTrackerSystems.EntityViewer.getComponentType());
                // Match the native tracker relationship on both sides before
                // AudioSystems queues a component update for this observer.
                viewer.visible.add(board);
                viewer.sent.put(board,store.getComponent(board,NetworkId.getComponentType()).getId());
                visible.visibleTo.put(near.ref(),viewer);visible.newlyVisibleTo.put(near.ref(),viewer);
                var tracker=new AudioSystems.EntityTrackerUpdate();
                Runnable tick=()->store.forEachChunk(tracker.getQuery(),(chunk,commands)->{
                    for(int i=0;i<chunk.size();i++)if(chunk.getReferenceTo(i).equals(board))tracker.tick(.05f,i,chunk,store,commands);
                });
                tick.run();
                var update=viewer.updates.remove(board);
                require(update!=null,"Native audio tracker publishes the board loop");
                var audio=Arrays.stream(update.toUpdatesArray()).filter(AudioUpdate.class::isInstance).map(AudioUpdate.class::cast).findFirst().orElseThrow();
                var wire=MemorySegment.ofArray(new byte[audio.computeSize()]);audio.serialize(wire,0);
                require(Arrays.equals(AudioUpdate.toObject(wire).soundEventIds,new int[]{sound}),"Actual AudioUpdate wire carries the single board-owned loop");
                visible.newlyVisibleTo.clear();tick.run();require(viewer.updates.remove(board)==null,"Stable audio does not restart on each server tick");
                visible.newlyVisibleTo.put(near.ref(),viewer);tick.run();require(viewer.updates.remove(board)!=null,"Native late-viewer tracking supplies the active loop");
                visible.newlyVisibleTo.clear();
                near.packets().packets.clear();far.packets().packets.clear();
                HoverboardRideEffects.pulse(store,board);
                var idle=near.packets().ofType(SpawnParticleSystem.class);
                require(idle.size()==2&&idle.stream().allMatch(p->HoverboardRideEffects.IDLE.equals(p.particleSystemId)),"Idle board emits two restrained coil rings");
                require(far.packets().ofType(SpawnParticleSystem.class).isEmpty(),"Riding particles are not sent beyond24 blocks");
                near.packets().packets.clear();movement.getMovementStates().running=true;movement.getMovementStates().sprinting=true;
                transform.getRotation().setYaw((float)(Math.PI/2));
                HoverboardRideEffects.pulse(store,board);
                var glide=near.packets().ofType(SpawnParticleSystem.class);
                require(glide.size()==2,"Moving board emits one bounded pair of rear trails");
                for(int i=0;i<glide.size();i++) {
                    var packet=glide.get(i);var encoded=MemorySegment.ofArray(new byte[packet.computeSize()]);packet.serialize(encoded,0);
                    var actual=SpawnParticleSystem.toObject(encoded);
                    var expected=HoverboardRideEffects.coilPosition(origin,(float)(Math.PI/2),i==0?-1:1);
                    require(actual.particleSystemId.equals(HoverboardRideEffects.GLIDE)&&actual.scale==1.15f
                            &&new Vector3d(actual.position.x,actual.position.y,actual.position.z).distance(expected)<1e-8,
                            "Native boosted trail packet originates at the rotated rear coil");
                    require(Math.abs(actual.position.y-origin.y-(MobilityTools.BOARD_VISUAL_ORIGIN_Y+2/32d))<1e-9,"Trail height follows the translated art rather than the collider floor");
                }
                require(transform.getPosition().equals(origin),"Rendering and audio never rewrite the native board position");
                require(store.getComponent(near.ref(),AudioComponent.getComponentType())==null,"Rider avatar receives no replacement audio/model component");
            } finally {if(board.isValid())store.removeEntity(board,RemoveReason.REMOVE);}
            require(!board.isValid(),"Removing the board ends its native entity-owned sound lifecycle");
        }
        System.out.println("NATIVE_HOVERBOARD_PRESENTATION_VERIFICATION_PASSED: native quiet looping sound and AudioUpdate wire, stable and late-viewer tracking, bounded paired idle/glide particles, boost and rotated coil positions, finite range/lifetimes, unchanged movement origin and board removal.");
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
