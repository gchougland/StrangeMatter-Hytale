package com.hexvane.strangematter.anomaly;

import com.hypixel.hytale.builtin.mounts.MountPlugin;
import com.hypixel.hytale.builtin.mounts.MountedByComponent;
import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.teleport.PendingTeleport;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

/** Same-world native teleport requests. The engine alone owns packets and their acknowledgements. */
final class WarpGateTeleport {
    private WarpGateTeleport(){}

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
        store.addComponent(ref,Teleport.getComponentType(),teleport);
        return true;
    }
}
