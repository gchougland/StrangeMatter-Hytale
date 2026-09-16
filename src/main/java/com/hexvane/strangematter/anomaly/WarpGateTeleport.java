package com.hexvane.strangematter.anomaly;

import com.hypixel.hytale.builtin.mounts.MountPlugin;
import com.hypixel.hytale.builtin.mounts.MountedByComponent;
import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.modules.entity.player.ChunkTracker;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.teleport.PendingTeleport;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Same-world native teleport requests. The engine alone owns packets and their acknowledgements. */
final class WarpGateTeleport {
    private WarpGateTeleport(){}
    private static final class Arrival {
        final ChunkTracker tracker;
        final int original;
        final long started;
        int applied;
        Arrival(ChunkTracker tracker,long started){this.tracker=tracker;this.original=tracker.getMaxSectionsPerTick();this.started=started;this.applied=original;}
    }
    private static final Map<World,Map<Ref<EntityStore>,Arrival>> arrivals=new ConcurrentHashMap<>();
    private record Notice(long until,boolean failed){}
    private static final Map<World,Map<Ref<EntityStore>,Notice>> notices=new ConcurrentHashMap<>();

    /** Native streaming ramps after a gate jump; unrelated player settings remain authoritative. */
    static void tick(World world){tick(world,System.nanoTime());}
    static void tick(World world,long now){
        var inWorld=arrivals.get(world);
        if(inWorld!=null){
            var store=world.getEntityStore().getStore();
            for(var iterator=inWorld.entrySet().iterator();iterator.hasNext();){
                var entry=iterator.next();var arrival=entry.getValue();var ref=entry.getKey();
                if(!ref.isValid()||ref.getStore()!=store||store.getComponent(ref,ChunkTracker.getComponentType())!=arrival.tracker||now-arrival.started>=4_000_000_000L){
                    restore(arrival);iterator.remove();continue;
                }
                if(arrival.tracker.getMaxSectionsPerTick()!=arrival.applied){iterator.remove();continue;}
                long elapsed=now-arrival.started;
                int budget=elapsed<1_000_000_000L?4:elapsed<2_000_000_000L?8:elapsed<3_000_000_000L?16:32;
                arrival.applied=Math.min(arrival.original,budget);arrival.tracker.setMaxSectionsPerTick(arrival.applied);
            }
            if(inWorld.isEmpty())arrivals.remove(world,inWorld);
        }
        var feedback=notices.get(world);if(feedback!=null){feedback.entrySet().removeIf(entry->!entry.getKey().isValid()||now>=entry.getValue().until);if(feedback.isEmpty())notices.remove(world,feedback);}
    }
    static void preparing(World world,Collection<Ref<EntityStore>> nearby){notice(world,nearby,false);}
    static void preparationFailed(World world,Collection<Ref<EntityStore>> nearby){notice(world,nearby,true);}
    private static void notice(World world,Collection<Ref<EntityStore>> nearby,boolean failed){
        var store=world.getEntityStore().getStore();long now=System.nanoTime();
        for(var ref:nearby){
            if(!eligible(store,ref))continue;var player=store.getComponent(ref,PlayerRef.getComponentType());if(player==null)continue;
            var feedback=notices.computeIfAbsent(world,key->new HashMap<>());
            var previous=feedback.get(ref);if(previous!=null&&now<previous.until&&previous.failed==failed)continue;
            feedback.put(ref,new Notice(now+8_000_000_000L,failed));
            player.sendMessage(Message.raw(failed?"Warp destination could not be prepared. Retrying shortly.":"Preparing warp destination…"));
        }
    }
    static void clear(World world){var inWorld=arrivals.remove(world);if(inWorld!=null)inWorld.values().forEach(WarpGateTeleport::restore);notices.remove(world);}
    private static void restore(Arrival arrival){if(arrival.tracker.getMaxSectionsPerTick()==arrival.applied)arrival.tracker.setMaxSectionsPerTick(arrival.original);}
    private static Arrival prepareArrival(Store<EntityStore> store,Ref<EntityStore> ref){
        var tracker=store.getComponent(ref,ChunkTracker.getComponentType());if(tracker==null)return null;
        var world=store.getExternalData().getWorld();var inWorld=arrivals.computeIfAbsent(world,key->new HashMap<>());
        var old=inWorld.remove(ref);if(old!=null)restore(old);
        if(tracker.getMaxSectionsPerTick()<=4){if(inWorld.isEmpty())arrivals.remove(world,inWorld);return null;}
        var arrival=new Arrival(tracker,System.nanoTime());arrival.applied=Math.min(4,arrival.original);tracker.setMaxSectionsPerTick(arrival.applied);inWorld.put(ref,arrival);return arrival;
    }

    static boolean eligible(Store<EntityStore> store,Ref<EntityStore> ref){
        if(store==null||!store.getExternalData().getWorld().isInThread()
                ||ref==null||!ref.isValid()||ref.getStore()!=store)return false;
        if(store.getComponent(ref,TransformComponent.getComponentType())==null
                ||store.getComponent(ref,DeathComponent.getComponentType())!=null
                ||store.getComponent(ref,Teleport.getComponentType())!=null
                ||store.getComponent(ref,PendingTeleport.getComponentType())!=null)return false;
        // A rider is handled once as a player. Moving its occupied vehicle separately
        // leaves two independent destinations under a client-owned mount controller.
        var mount=store.getComponent(ref,NPCMountComponent.getComponentType());
        if(mount!=null&&mount.getOwnerPlayerRef()!=null)return false;
        var passengers=store.getComponent(ref,MountedByComponent.getComponentType());
        if(passengers!=null&&!passengers.getPassengers().isEmpty())return false;
        var owner=store.getComponent(ref,PlayerRef.getComponentType());
        if(owner!=null&&!owner.getTeleportAckTracker().isEmpty())return false;
        var player=store.getComponent(ref,Player.getComponentType());
        return player==null||!player.isWaitingForClientReady();
    }

    static boolean teleport(Store<EntityStore> store,Ref<EntityStore> ref,Vector3d destination){
        if(destination==null||!destination.isFinite()||destination.y<0||destination.y>=ChunkUtil.HEIGHT||!eligible(store,ref))return false;
        var transform=store.getComponent(ref,TransformComponent.getComponentType());
        var body=new Rotation3f(transform.getRotation());
        var head=store.getComponent(ref,HeadRotation.getComponentType());
        var look=new Rotation3f(head==null?body:head.getRotation());
        var player=store.getComponent(ref,Player.getComponentType());
        if(player!=null&&player.getMountEntityId()!=0){
            var mountRef=store.getExternalData().getRefFromNetworkId(player.getMountEntityId());
            if(mountRef!=null&&mountRef.isValid()){
                var mount=store.getComponent(mountRef,NPCMountComponent.getComponentType());
                var owner=store.getComponent(ref,PlayerRef.getComponentType());
                // Do not interfere with an unrelated or inconsistent third-party mount.
                if(mount==null||mount.getOwnerPlayerRef()!=owner)return false;
            }
            // Native TeleportMountedEntity covers MountedComponent seats/minecarts,
            // but NPC riding uses a separate mountEntityId path and needs this call.
            MountPlugin.checkDismountNpc(store,ref,player);
            if(!eligible(store,ref)||player.getMountEntityId()!=0)return false;
        }
        // Never replace a Teleport component: native onComponentSet does not execute it.
        // Native addition sends ClientTeleport, tracks its ACK, and applies PendingTeleport.
        var teleport=player==null?new Teleport(destination,body):Teleport.createExact(destination,body,look);
        var arrival=player==null?null:prepareArrival(store,ref);
        try{store.addComponent(ref,Teleport.getComponentType(),teleport);}
        catch(RuntimeException|Error failure){
            if(arrival!=null){restore(arrival);var inWorld=arrivals.get(store.getExternalData().getWorld());if(inWorld!=null)inWorld.remove(ref,arrival);}
            throw failure;
        }
        return true;
    }
}
