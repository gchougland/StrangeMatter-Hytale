package com.hexvane.strangematter.equipment;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.ItemArmorSlot;
import com.hypixel.hytale.server.core.inventory.*;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** One inventory transfer budget per worn pack; all updates stay on the owning world thread. */
public final class BatteryPackService {
    public static final short CHEST=(short)ItemArmorSlot.Chest.ordinal();
    private final Map<String,Double> elapsed=new ConcurrentHashMap<>();
    private final Map<UUID,Integer> nextSlot=new ConcurrentHashMap<>();
    private final Map<UUID,Integer> remainder=new ConcurrentHashMap<>();
    private final Map<String,Set<UUID>> viewers=new ConcurrentHashMap<>();
    private static final Map<UUID,Integer> rates=new ConcurrentHashMap<>();
    public static ItemStack equipped(PlayerRef player,Store<EntityStore> store){
        var ref=player.getReference();if(ref==null||!ref.isValid()||ref.getStore()!=store)return ItemStack.EMPTY;
        var armor=store.getComponent(ref,InventoryComponent.Armor.getComponentType());if(armor==null||CHEST>=armor.getInventory().getCapacity())return ItemStack.EMPTY;
        var item=armor.getInventory().getItemStack(CHEST);return GadgetEnergy.powered(item)&&GadgetEnergy.PACK.equals(item.getItemId())?item:ItemStack.EMPTY;
    }
    public static int rate(UUID player){return rates.getOrDefault(player,0);}
    public void tick(World world,double dt){
        double time=elapsed.getOrDefault(world.getName(),0d)+Math.clamp(dt,0,.25);
        if(time<.2){elapsed.put(world.getName(),time);return;}int steps=Math.min(2,(int)(time/.2));elapsed.put(world.getName(),time%.2);
        var store=world.getEntityStore().getStore();
        Set<UUID> current=new HashSet<>();for(var player:world.getPlayerRefs())current.add(player.getUuid());
        var previous=viewers.put(world.getName(),current);if(previous!=null)for(var id:previous)if(!current.contains(id)){nextSlot.remove(id);rates.remove(id);remainder.remove(id);}
        for(var player:world.getPlayerRefs()){
            var ref=player.getReference();if(ref==null||!ref.isValid()||ref.getStore()!=store)continue;
            rates.remove(player.getUuid());
            var inventory=InventoryComponent.getCombined(store,ref,InventoryComponent.HOTBAR_STORAGE_BACKPACK);if(inventory==null)continue;
            normalize(inventory);
            var armor=store.getComponent(ref,InventoryComponent.Armor.getComponentType());if(armor!=null)normalize(armor.getInventory());
            var pack=equipped(player,store);if(ItemStack.isEmpty(pack)||store.getComponent(ref,DeathComponent.getComponentType())!=null){remainder.remove(player.getUuid());continue;}
            int credit=GadgetEnergy.cost("pack_second")*steps+remainder.getOrDefault(player.getUuid(),0),budget=credit/5;remainder.put(player.getUuid(),credit%5);
            var hotbar=store.getComponent(ref,InventoryComponent.Hotbar.getComponentType());short active=hotbar==null?-1:hotbar.getActiveSlot();
            int moved=0;
            if(active>=0&&active<hotbar.getInventory().getCapacity()&&eligible(hotbar.getInventory().getItemStack(active)))
                moved+=GadgetEnergy.transfer(armor.getInventory(),CHEST,hotbar.getInventory(),active,budget);
            int cap=inventory.getCapacity(),start=Math.floorMod(nextSlot.getOrDefault(player.getUuid(),0),Math.max(1,cap));
            for(int i=0;i<cap&&moved<budget;i++){
                short slot=(short)((start+i)%cap);var item=inventory.getItemStack(slot);
                if(slot==active||!eligible(item))continue;
                moved+=GadgetEnergy.transfer(armor.getInventory(),CHEST,inventory,slot,budget-moved);
                nextSlot.put(player.getUuid(),(slot+1)%cap);
            }
            if(moved>0)rates.put(player.getUuid(),moved*5/steps);
        }
    }
    private static boolean eligible(ItemStack stack){return GadgetEnergy.powered(stack)&&!GadgetEnergy.PACK.equals(stack.getItemId());}
    private static void normalize(ItemContainer inventory){
        for(short slot=0;slot<inventory.getCapacity();slot++){
            var item=inventory.getItemStack(slot);if(!GadgetEnergy.powered(item))continue;
            var updated=GadgetEnergy.normalize(item);if(updated!=item&&item.equals(inventory.getItemStack(slot)))inventory.setItemStackForSlot(slot,updated,false);
        }
    }
    public void cleanup(World world){elapsed.remove(world.getName());var ids=viewers.remove(world.getName());if(ids!=null)for(var id:ids){nextSlot.remove(id);rates.remove(id);remainder.remove(id);}}
}
