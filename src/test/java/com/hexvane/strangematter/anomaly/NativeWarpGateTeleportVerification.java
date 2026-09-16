package com.hexvane.strangematter.anomaly;

import com.hexvane.strangematter.util.WorldAccess;

import com.hexvane.strangematter.equipment.MobilityTools;
import com.hexvane.strangematter.equipment.NativePlayerFixture;
import com.hexvane.strangematter.ui.gadget.GadgetHudService;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.dependency.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefChangeSystem;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.Direction;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.protocol.Position;
import com.hypixel.hytale.protocol.TeleportAck;
import com.hypixel.hytale.protocol.packets.entities.MountMovement;
import com.hypixel.hytale.protocol.packets.interaction.DismountNPC;
import com.hypixel.hytale.protocol.packets.player.ClientMovement;
import com.hypixel.hytale.protocol.packets.player.ClientTeleport;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.player.ChunkTracker;
import com.hypixel.hytale.server.core.modules.entity.teleport.PendingTeleport;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.modules.entity.teleport.TeleportSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.util.*;

/** Real native teleport addition, ACK lifecycle and receipt-backed NPC hoverboard dismount. */
public final class NativeWarpGateTeleportVerification {
    private static final Map<Ref<EntityStore>,Probe> PROBES=new HashMap<>();
    private static final class Probe { int additions; boolean protectedExisting=true,local=true; }

    /** Test-only observer sees an actual Teleport before the native player system consumes it. */
    public static final class RequestObserver extends RefChangeSystem<EntityStore,Teleport> {
        @Override public Query<EntityStore> getQuery(){return Query.and(PlayerRef.getComponentType(),TransformComponent.getComponentType());}
        @Override public ComponentType<EntityStore,Teleport> componentType(){return Teleport.getComponentType();}
        @Override public Set<Dependency<EntityStore>> getDependencies(){return Set.of(new SystemDependency<>(Order.BEFORE,TeleportSystems.PlayerMoveSystem.class));}
        @Override public void onComponentAdded(Ref<EntityStore> ref,Teleport teleport,Store<EntityStore> store,CommandBuffer<EntityStore> commands){
            var probe=PROBES.get(ref);if(probe==null)return;
            probe.additions++;probe.local&=teleport.getWorld()==null;
            probe.protectedExisting&=!WarpGateTeleport.eligible(store,ref)
                    &&!WarpGateTeleport.teleport(store,ref,new Vector3d(29.5,220,29.5))
                    &&store.getComponent(ref,Teleport.getComponentType())==teleport;
        }
        @Override public void onComponentSet(Ref<EntityStore> ref,Teleport old,Teleport value,Store<EntityStore> store,CommandBuffer<EntityStore> commands){}
        @Override public void onComponentRemoved(Ref<EntityStore> ref,Teleport value,Store<EntityStore> store,CommandBuffer<EntityStore> commands){}
    }

    public static void verify(World world)throws Exception {
        var store=world.getEntityStore().getStore();
        try(var subject=NativePlayerFixture.create(world,"NativeGateTeleport",new Vector3d(24.5,220,24.5))){
            subject.player().startClientReadyTimeout();
            require(!WarpGateTeleport.eligible(store,subject.ref()),"Gate waits until native client join readiness is complete");
            subject.player().handleClientReady(false);subject.packets().packets.clear();
            var probe=new Probe();PROBES.put(subject.ref(),probe);
            var body=store.getComponent(subject.ref(),TransformComponent.getComponentType());
            var head=store.getComponent(subject.ref(),HeadRotation.getComponentType());
            body.setRotation(new Rotation3f(0,.7f,0));head.setRotation(new Rotation3f(.45f,.9f,0));
            var model=store.getComponent(subject.ref(),ModelComponent.getComponentType());
            var destination=new Vector3d(26.5,220,26.5);
            require(!WarpGateTeleport.teleport(store,subject.ref(),new Vector3d(Double.NaN,220,24.5)),"Invalid destinations cannot queue native teleport state");
            require(WarpGateTeleport.teleport(store,subject.ref(),destination),"Valid same-world gate request reaches the native teleport system");
            require(probe.additions==1&&probe.local&&probe.protectedExisting,"Actual queued Teleport cannot be replaced while native onAdded is still processing it");
            var packet=wire(subject.packets().ofType(ClientTeleport.class).getLast());
            require(packet.resetVelocity&&packet.relativeTransformFields==0&&packet.ignoredTransformFields==0,
                    "Native teleport has an absolute destination and normal velocity reset");
            require(position(packet).equals(destination)&&packet.modelTransform.bodyOrientation.yaw==.7f
                            &&packet.modelTransform.lookOrientation.pitch==.45f&&packet.modelTransform.lookOrientation.yaw==.9f,
                    "Native wire preserves exact destination, body yaw and the player's independent look direction");
            require(store.getComponent(subject.ref(),ModelComponent.getComponentType())==model,"Gate never replaces the avatar model");
            require(store.getComponent(subject.ref(),PendingTeleport.getComponentType())!=null&&!subject.owner().getTeleportAckTracker().isEmpty(),
                    "Native engine creates both the pending marker and expected ACK");
            for(int attempt=0;attempt<40;attempt++){
                require(!WarpGateTeleport.teleport(store,subject.ref(),new Vector3d(28.5,220,28.5)),"Repeated gate contact leaves an outstanding native teleport untouched");
                var movement=new ClientMovement();movement.absolutePosition=new Position(24.5,220,24.5);
                receive(subject,movement);store.tick(.05f);
                require(body.getPosition().equals(destination),"Unacknowledged old movement cannot overwrite the native teleport destination");
            }
            require(subject.packets().ofType(ClientTeleport.class).size()==1&&probe.additions==1,"Delayed ACK produces exactly one request and one wire packet");
            acknowledge(subject,packet);
            require(subject.owner().getTeleportAckTracker().isEmpty()&&store.getComponent(subject.ref(),PendingTeleport.getComponentType())==null,
                    "Actual wire ACK drains the native tracker and pending component without manipulation");
            require(subject.packets().getChannel().isActive()&&WarpGateTeleport.eligible(store,subject.ref()),"Acknowledged player stays connected and becomes eligible again");
            require(WarpGateTeleport.teleport(store,subject.ref(),new Vector3d(24.5,220,24.5)),"A later independent gate request works after the real ACK");
            var second=wire(subject.packets().ofType(ClientTeleport.class).getLast());
            require(second.teleportId!=packet.teleportId,"Native engine allocates the later request's teleport ID");
            acknowledge(subject,second);
            verifyStreaming(subject);
            verifyMounted(subject,probe);
            PROBES.remove(subject.ref());
        }finally{PROBES.keySet().removeIf(ref->!ref.isValid());WarpGateTeleport.clear(world);}
        verifyDisconnectedStreaming(world);
        System.out.println("NATIVE_WARP_GATE_TELEPORT_VERIFICATION_PASSED: native packet/head rotation and ACK ownership, bounded 4/8/16/32 section arrival ramp, exact custom-budget restoration, repeated travel, third-party overrides, disconnect/cleanup, waiting feedback throttle, native NPC dismount and exact hoverboard return.");
    }

    private static void verifyStreaming(NativePlayerFixture subject){
        var world=subject.world();var tracker=subject.store().getComponent(subject.ref(),ChunkTracker.getComponentType());require(tracker!=null,"Actual native player has its native chunk tracker");
        var originalPosition=new Vector3d(subject.store().getComponent(subject.ref(),TransformComponent.getComponentType()).getPosition());
        WarpGateTeleport.clear(world);int original=tracker.getMaxSectionsPerTick(),rate=tracker.getMaxSectionsPerSecond();
        try{
            tracker.setMaxSectionsPerTick(40);long started=System.nanoTime();
            require(WarpGateTeleport.teleport(subject.store(),subject.ref(),new Vector3d(25.5,220,25.5))&&tracker.getMaxSectionsPerTick()==4,"Native gate request immediately reduces the arrival burst from 40 to four sections per tick");
            acknowledge(subject,wire(subject.packets().ofType(ClientTeleport.class).getLast()));
            require(WarpGateTeleport.teleport(subject.store(),subject.ref(),new Vector3d(26.5,220,25.5))&&tracker.getMaxSectionsPerTick()==4,"A repeated acknowledged crossing during stage one restarts the ramp without capturing four as its original budget");
            acknowledge(subject,wire(subject.packets().ofType(ClientTeleport.class).getLast()));
            WarpGateTeleport.tick(world,started+1_100_000_000L);require(tracker.getMaxSectionsPerTick()==8,"Second ramp stage allows eight sections per tick");
            WarpGateTeleport.tick(world,started+2_100_000_000L);require(tracker.getMaxSectionsPerTick()==16,"Third ramp stage allows sixteen sections per tick");
            WarpGateTeleport.tick(world,started+3_100_000_000L);require(tracker.getMaxSectionsPerTick()==32,"Fourth ramp stage allows thirty-two sections per tick");
            WarpGateTeleport.tick(world,started+4_100_000_000L);require(tracker.getMaxSectionsPerTick()==40&&tracker.getMaxSectionsPerSecond()==rate,"Lease expiry restores exact original burst budget and never changes the bandwidth setting");
            tracker.setMaxSectionsPerTick(6);started=System.nanoTime();
            require(WarpGateTeleport.teleport(subject.store(),subject.ref(),new Vector3d(26.5,220,25.5)),"Custom-budget traveller can use the gate");
            acknowledge(subject,wire(subject.packets().ofType(ClientTeleport.class).getLast()));
            WarpGateTeleport.tick(world,started+2_100_000_000L);require(tracker.getMaxSectionsPerTick()==6,"Ramp never raises an existing lower user budget");
            require(WarpGateTeleport.teleport(subject.store(),subject.ref(),new Vector3d(25.5,220,25.5))&&tracker.getMaxSectionsPerTick()==4,"A subsequent acknowledged trip restarts the ramp");
            acknowledge(subject,wire(subject.packets().ofType(ClientTeleport.class).getLast()));
            WarpGateTeleport.clear(world);require(tracker.getMaxSectionsPerTick()==6,"Repeated travel still restores the original custom budget, never a temporary ramp value");
            tracker.setMaxSectionsPerTick(2);
            require(WarpGateTeleport.teleport(subject.store(),subject.ref(),new Vector3d(26.5,220,25.5))&&tracker.getMaxSectionsPerTick()==2,"A user budget below four remains untouched");
            acknowledge(subject,wire(subject.packets().ofType(ClientTeleport.class).getLast()));
            tracker.setMaxSectionsPerTick(40);
            require(WarpGateTeleport.teleport(subject.store(),subject.ref(),new Vector3d(25.5,220,25.5)),"Third-party override fixture starts a normal arrival lease");
            acknowledge(subject,wire(subject.packets().ofType(ClientTeleport.class).getLast()));
            tracker.setMaxSectionsPerTick(3);WarpGateTeleport.tick(world);WarpGateTeleport.clear(world);
            require(tracker.getMaxSectionsPerTick()==3,"Tick and cleanup preserve an external budget change made during our lease");
            int packets=subject.packets().packets.size();WarpGateTeleport.preparing(world,List.of(subject.ref()));int once=subject.packets().packets.size();WarpGateTeleport.preparing(world,List.of(subject.ref()));
            require(once>packets&&subject.packets().packets.size()==once,"Pending travel reports preparation once, without repeating chat on every gate tick");
            WarpGateTeleport.preparationFailed(world,List.of(subject.ref()));int failed=subject.packets().packets.size();WarpGateTeleport.preparationFailed(world,List.of(subject.ref()));
            require(failed>once&&subject.packets().packets.size()==failed,"A preparation failure replaces waiting feedback once instead of leaving a silent timeout");
            require(WarpGateTeleport.teleport(subject.store(),subject.ref(),originalPosition),"Streaming fixture restores its original position through a real native teleport");
            acknowledge(subject,wire(subject.packets().ofType(ClientTeleport.class).getLast()));
            require(subject.store().getComponent(subject.ref(),TransformComponent.getComponentType()).getPosition().equals(originalPosition)&&subject.owner().getTeleportAckTracker().isEmpty(),"Streaming fixture leaves the following grounded mount case at its original location with the restoration ACK complete");
        }finally{WarpGateTeleport.clear(world);tracker.setMaxSectionsPerTick(original);}
    }
    private static void verifyDisconnectedStreaming(World world)throws Exception{
        ChunkTracker tracker;
        try(var subject=NativePlayerFixture.create(world,"NativeGateDisconnect",new Vector3d(24.5,220,24.5))){
            subject.player().handleClientReady(false);tracker=subject.store().getComponent(subject.ref(),ChunkTracker.getComponentType());tracker.setMaxSectionsPerTick(19);
            require(WarpGateTeleport.teleport(subject.store(),subject.ref(),new Vector3d(25.5,220,25.5))&&tracker.getMaxSectionsPerTick()==4,"Disconnect fixture has an active arrival lease");
            acknowledge(subject,wire(subject.packets().ofType(ClientTeleport.class).getLast()));
        }
        WarpGateTeleport.tick(world);require(tracker.getMaxSectionsPerTick()==19,"Native entity removal restores its tracker object and retires the lease without waiting for expiry");
    }

    private static void verifyMounted(NativePlayerFixture rider,Probe probe)throws Exception {
        var world=rider.world();var store=rider.store();
        var chunk=WorldAccess.inMemory(world,ChunkUtil.indexChunkFromBlock(24,24));
        require(chunk!=null,"Mounted gate fixture uses an already loaded native chunk");
        var section=WorldAccess.section(chunk,219);
        int original=section.get(24,219,24),rotation=section.getRotationIndex(24,219,24),filler=section.getFiller(24,219,24);
        try(var hud=new GadgetHudService()){
            var mobility=new MobilityTools(hud,Files.createTempDirectory("sm-native-gate-mount-"));
            try{
                world.setBlock(24,219,24,"Rock_Stone");
                var originalBoard=new ItemStack("SM_Hoverboard",1).withMetadata("GateProof",Codec.STRING,"exact owned board");
                rider.hotbar().setItemStackForSlot((short)0,originalBoard,false);
                store.getComponent(rider.ref(),InventoryComponent.Hotbar.getComponentType()).setActiveSlot((byte)0,rider.ref(),store);
                mobility.useHoverboard(rider.owner(),store);
                for(int attempt=0;attempt<10&&rider.player().getMountEntityId()==0;attempt++){rider.save();mobility.tick(world,.05);}
                int mountId=rider.player().getMountEntityId();require(mountId!=0,"Actual receipt-backed hoverboard mounts before the gate crossing");
                var mount=world.getEntityStore().getRefFromNetworkId(mountId);require(mount!=null&&mount.isValid(),"Native occupied mount exists");
                var mountUuid=store.getComponent(mount,UUIDComponent.getComponentType()).getUuid();
                var mountPosition=new Vector3d(store.getComponent(mount,TransformComponent.getComponentType()).getPosition());
                require(!WarpGateTeleport.eligible(store,mount),"Occupied mount is excluded as an independent gate traveller");
                rider.packets().packets.clear();
                require(WarpGateTeleport.teleport(store,rider.ref(),new Vector3d(26.5,220,26.5)),"Mounted player is safely accepted after native NPC dismount");
                require(rider.player().getMountEntityId()==0,"Native NPC dismount clears the controller's mount ID before teleport");
                var packets=rider.packets().packets;
                int dismount=-1,teleport=-1;
                for(int i=0;i<packets.size();i++){if(packets.get(i) instanceof DismountNPC&&dismount<0)dismount=i;if(packets.get(i) instanceof ClientTeleport&&teleport<0)teleport=i;}
                require(dismount>=0&&teleport>dismount,"Native DismountNPC is sent before ClientTeleport on the same stream");
                var stale=new MountMovement();stale.absolutePosition=new Position(1024.5,240,1024.5);stale.bodyOrientation=new Direction(0,0,0);stale.movementStates=new MovementStates();
                rider.packets().handleMountMovement(stale,rider.owner(),rider.ref(),world,store);
                if(mount.isValid())require(store.getComponent(mount,TransformComponent.getComponentType()).getPosition().equals(mountPosition),
                        "Late mount movement cannot move the old vehicle while the player awaits teleport ACK");
                acknowledge(rider,wire(rider.packets().ofType(ClientTeleport.class).getLast()));
                for(int attempt=0;attempt<12;attempt++){mobility.tick(world,.05);rider.save();}
                int count=0;ItemStack returned=null;
                for(short slot=0;slot<rider.inventory().getCapacity();slot++){
                    var item=rider.inventory().getItemStack(slot);
                    if(!ItemStack.isEmpty(item)&&item.getItemId().equals("SM_Hoverboard")){count+=item.getQuantity();returned=item;}
                }
                require(count==1&&returned!=null&&"exact owned board".equals(returned.getFromMetadataOrNull("GateProof",Codec.STRING)),
                        "Normal dismount recovery returns exactly one original board with its metadata");
                require(world.getEntityStore().getRefFromUUID(mountUuid)==null,"Normal mobility cleanup removes the old mount instead of leaving a ghost");
                require(probe.protectedExisting&&rider.owner().getTeleportAckTracker().isEmpty(),"Mounted crossing retains native request and ACK ownership");
            }finally{
                mobility.cleanup(world);
                section.set(24,219,24,original,rotation,filler);WorldAccess.column(chunk).updateHeight(24,24);
            }
        }
    }
    private static ClientTeleport wire(ClientTeleport packet){
        var bytes=MemorySegment.ofArray(new byte[packet.computeSize()]);require(packet.serialize(bytes,0)==bytes.byteSize(),"Native ClientTeleport wire size is exact");
        var decoded=ClientTeleport.toObject(bytes);require(decoded.equals(packet),"Native ClientTeleport survives serialization");return decoded;
    }
    private static Vector3d position(ClientTeleport packet){var p=packet.modelTransform.position;return new Vector3d(p.x,p.y,p.z);}
    private static void acknowledge(NativePlayerFixture subject,ClientTeleport teleport){
        var packet=new ClientMovement();packet.teleportAck=new TeleportAck(teleport.teleportId);
        // The native client echoes position through float precision; exercise that actual contract.
        var p=teleport.modelTransform.position;packet.absolutePosition=new Position((float)p.x,(float)p.y,(float)p.z);
        receive(subject,packet);
    }
    private static void receive(NativePlayerFixture subject,ClientMovement packet){
        var bytes=MemorySegment.ofArray(new byte[packet.computeSize()]);packet.serialize(bytes,0);
        subject.packets().handle(ClientMovement.toObject(bytes));subject.world().consumeTaskQueue();
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
