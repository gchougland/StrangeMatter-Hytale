package com.hexvane.strangematter.equipment;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.dependency.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.inventory.*;
import com.hypixel.hytale.server.core.inventory.transaction.SlotTransaction;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Set;
import java.util.WeakHashMap;

/** Native armor invalidation is unconditional; a pack's charge does not change its equipped model. */
public final class GadgetEnergyArmorUpdates extends EntityTickingSystem<EntityStore> {
    private static final WeakHashMap<InventoryComponent.Armor,Boolean> priorInvalidation=new WeakHashMap<>();
    /** Remember visibility/settings invalidations before native charge invalidation is added. */
    public static final class Remember extends EntityTickingSystem<EntityStore> {
        @Override public Query<EntityStore> getQuery(){return InventoryComponent.Armor.getComponentType();}
        @Override public Set<Dependency<EntityStore>> getDependencies(){return Set.of(new SystemDependency<>(Order.BEFORE,InventorySystems.LegacyArmorChangeStatSystem.class));}
        @Override public void tick(float dt,int index,ArchetypeChunk<EntityStore> chunk,Store<EntityStore> store,CommandBuffer<EntityStore> commands){remember(chunk.getComponent(index,InventoryComponent.Armor.getComponentType()));}
    }
    @Override public Query<EntityStore> getQuery(){return InventoryComponent.Armor.getComponentType();}
    @Override public Set<Dependency<EntityStore>> getDependencies(){return Set.of(
            new SystemDependency<>(Order.AFTER,InventorySystems.LegacyArmorChangeStatSystem.class),
            new SystemDependency<>(Order.BEFORE,InventorySystems.ArmorChangeEventSystem.class),
            new SystemDependency<>(Order.BEFORE,InventorySystems.SyncEquipmentSystem.class));}
    @Override public void tick(float dt,int index,ArchetypeChunk<EntityStore> chunk,Store<EntityStore> store,CommandBuffer<EntityStore> commands){
        preserveModel(chunk.getComponent(index,InventoryComponent.Armor.getComponentType()));
    }
    static void preserveModel(InventoryComponent.Armor armor){
        final Boolean prior;synchronized(priorInvalidation){prior=priorInvalidation.remove(armor);}
        if(prior==null||!onlyCharge(armor))return;
        // Leave native events and dirty flags intact. Keep prior visibility/settings invalidations.
        armor.setOutdatedEquipment(prior);
    }
    static void remember(InventoryComponent.Armor armor){
        synchronized(priorInvalidation){
            priorInvalidation.remove(armor);
            if(!onlyCharge(armor))return;
            boolean prior=armor.consumeOutdatedEquipment();armor.setOutdatedEquipment(prior);priorInvalidation.put(armor,prior);
        }
    }
    private static boolean onlyCharge(InventoryComponent.Armor armor){
        var events=armor.getChangeEvents();if(events.isEmpty())return false;
        for(var event:events){
            if(!(event.transaction() instanceof SlotTransaction slot)||slot.getSlot()!=BatteryPackService.CHEST)return false;
            var before=slot.getSlotBefore();var after=slot.getSlotAfter();
            if(!GadgetEnergy.powered(before)||!GadgetEnergy.powered(after)||!GadgetEnergy.PACK.equals(before.getItemId())
                    ||!ItemStack.isEquivalentType(before,after)||before.getQualityIndex()!=after.getQualityIndex()
                    ||before.getMaxDurability()!=after.getMaxDurability())return false;
        }
        return true;
    }
}
