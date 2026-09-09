package com.hexvane.strangematter.util;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.concurrent.CompletableFuture;

/** Native required player saves, called on the owning world thread before committing item receipts. */
public final class PlayerInventoryPersistence {
    private PlayerInventoryPersistence() {}
    public static CompletableFuture<Void> save(World world,PlayerRef owner){
        try{
            if(!world.isInThread())throw new IllegalStateException("Player inventory save must run on its world thread");
            var ref=owner.getReference();var store=world.getEntityStore().getStore();
            if(ref==null||!ref.isValid()||ref.getStore()!=store)throw new IllegalStateException("Player detached before inventory save");
            var player=store.getComponent(ref,Player.getComponentType());
            if(player==null||store.getComponent(ref,UUIDComponent.getComponentType())==null||store.getComponent(ref,MovementStatesComponent.getComponentType())==null)
                throw new IllegalStateException("Player save requires Player, UUID and MovementStates components");
            // PlayerSavingSystems.createShallowHolder uses this exact strategy. The native disk
            // storage serializes synchronously before queuing IO. copyEntity/cloneSerializable
            // invokes Player's legacy Entity.clone(), whose entity codec is absent in Hytale0.6.4.
            return player.saveConfig(world,shallowHolder(store,ref),true);
        }catch(RuntimeException failure){return CompletableFuture.failedFuture(failure);}
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    public static Holder<EntityStore> shallowHolder(Store<EntityStore> store,Ref<EntityStore> ref){
        if(!store.getExternalData().getWorld().isInThread())throw new IllegalStateException("Capture player state on its world thread");
        var archetype=store.getArchetype(ref);Component[] components=new Component[archetype.length()];
        for(int i=archetype.getMinIndex();i<archetype.length();i++){
            var type=(ComponentType)archetype.get(i);if(type!=null)components[i]=store.getComponent(ref,type);
        }
        return EntityStore.REGISTRY.newHolder(archetype,components);
    }
}
