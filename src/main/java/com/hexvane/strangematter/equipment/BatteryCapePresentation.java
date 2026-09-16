package com.hexvane.strangematter.equipment;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.dependency.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.protocol.PlayerSkin;
import com.hypixel.hytale.protocol.PlayerSkinUpdate;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSettings;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Projects cape coverage into native viewer packets without changing the player's canonical skin. */
public final class BatteryCapePresentation extends EntityTickingSystem<EntityStore> {
    private record Shown(PlayerSkin canonical,boolean covered){}
    private final Map<EntityTrackerSystems.EntityViewer,Map<Ref<EntityStore>,Shown>> shown=Collections.synchronizedMap(new WeakHashMap<>());

    @Override public Query<EntityStore> getQuery(){return EntityTrackerSystems.EntityViewer.getComponentType();}
    @Override public Set<Dependency<EntityStore>> getDependencies(){return Set.of(
            new SystemGroupDependency<>(Order.AFTER,EntityTrackerSystems.QUEUE_UPDATE_GROUP),
            new SystemGroupDependency<>(Order.BEFORE,EntityStore.SEND_PACKET_GROUP));}
    @Override public void tick(float dt,int index,ArchetypeChunk<EntityStore> chunk,Store<EntityStore> store,CommandBuffer<EntityStore> commands){
        project(store,chunk.getComponent(index,EntityTrackerSystems.EntityViewer.getComponentType()));
    }

    void project(Store<EntityStore> store,EntityTrackerSystems.EntityViewer viewer){
        var previous=shown.computeIfAbsent(viewer,key->new WeakHashMap<>());
        previous.keySet().retainAll(viewer.visible);
        for(var ref:viewer.visible){
            if(!ref.isValid()||ref.getStore()!=store)continue;
            var component=store.getComponent(ref,PlayerSkinComponent.getComponentType());
            if(component==null){previous.remove(ref);continue;}
            var source=component.getPlayerSkin();
            var old=previous.get(ref);
            var queued=viewer.updates.get(ref);
            var incoming=lastSkin(queued);
            if(!visiblePack(store,ref)){
                // An externally queued disguise/skin update remains authoritative on this transition.
                if(old!=null&&old.covered()&&incoming==null)viewer.queueUpdate(ref,new PlayerSkinUpdate(new PlayerSkin(source)));
                previous.remove(ref);continue;
            }
            if(incoming!=null){
                viewer.updates.put(ref,coverQueuedSkins(queued));
                previous.put(ref,new Shown(new PlayerSkin(source),incoming.skin!=null&&incoming.skin.cape!=null));
            }else if(old==null||!source.equals(old.canonical())){
                // A new canonical appearance replaces any previous viewer-only presentation.
                if(source.cape!=null||old!=null&&old.covered()){
                    var skin=new PlayerSkin(source);skin.cape=null;
                    viewer.queueUpdate(ref,new PlayerSkinUpdate(skin));
                }
                previous.put(ref,new Shown(new PlayerSkin(source),source.cape!=null));
            }
        }
        if(previous.isEmpty())shown.remove(viewer);
    }

    static boolean visiblePack(Store<EntityStore> store,Ref<EntityStore> ref){
        if(store.getComponent(ref,PlayerRef.getComponentType())==null)return false;
        var armor=store.getComponent(ref,InventoryComponent.Armor.getComponentType());
        if(armor==null)return false;
        var item=armor.getInventory().getItemStack(BatteryPackService.CHEST);
        if(item==null||!GadgetEnergy.PACK.equals(item.getItemId()))return false;
        var settings=store.getComponent(ref,PlayerSettings.getComponentType());
        return settings==null||!settings.hideCuirass()
                ||!store.getExternalData().getWorld().getGameplayConfig().getPlayerConfig().getArmorVisibilityOption().canHideCuirass();
    }

    private static PlayerSkinUpdate lastSkin(EntityTrackerSystems.EntityUpdate queued){
        if(queued==null)return null;
        PlayerSkinUpdate result=null;var updates=queued.toUpdatesArray();
        if(updates!=null)for(var update:updates)if(update instanceof PlayerSkinUpdate skin)result=skin;
        return result;
    }

    private static EntityTrackerSystems.EntityUpdate coverQueuedSkins(EntityTrackerSystems.EntityUpdate queued){
        var replacement=new EntityTrackerSystems.EntityUpdate();
        var removed=queued.toRemovedArray();
        if(removed!=null)for(var type:removed)replacement.queueRemove(type);
        for(var update:queued.toUpdatesArray()){
            if(update instanceof PlayerSkinUpdate skin&&skin.skin!=null){
                var copy=new PlayerSkin(skin.skin);copy.cape=null;
                replacement.queueUpdate(new PlayerSkinUpdate(copy));
            }else replacement.queueUpdate(update);
        }
        return replacement;
    }
}
