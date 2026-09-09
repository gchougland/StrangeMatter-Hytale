package com.hexvane.strangematter.util;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import java.util.*;
import java.util.function.Predicate;

/** Transactions run on the player's world thread. Match by ID while preserving actual stack metadata. */
public final class InventoryOps {
    private InventoryOps() {}
    public static int count(ItemContainer inventory,String id) {
        return count(inventory,id,stack->true);
    }
    public static int count(ItemContainer inventory,String id,Predicate<ItemStack> available) {
        int count=0;
        for(short s=0;s<inventory.getCapacity();s++){var stack=inventory.getItemStack(s);if(matches(stack,id)&&available.test(stack))count+=stack.getQuantity();}
        return count;
    }
    private static boolean matches(ItemStack stack,String id) {
        if(stack==null||stack.isEmpty())return false;
        return id.startsWith("resource:")?ItemContainer.getMatchingResourceType(stack.getItem(),id.substring(9))!=null:id.equals(stack.getItemId());
    }
    public static boolean has(ItemContainer inventory,Map<String,Integer> cost) {
        return cost.entrySet().stream().allMatch(e->e.getValue()>0&&count(inventory,e.getKey())>=e.getValue());
    }
    public static List<ItemStack> take(ItemContainer inventory,Map<String,Integer> cost) {
        return take(inventory,cost,stack->true);
    }
    public static List<ItemStack> take(ItemContainer inventory,Map<String,Integer> cost,Predicate<ItemStack> available) {
        if(!cost.entrySet().stream().allMatch(e->e.getValue()>0&&count(inventory,e.getKey(),available)>=e.getValue()))return null;
        List<ItemStack> removed=new ArrayList<>();
        for(var entry:cost.entrySet()) {
            int left=entry.getValue();
            for(short slot=0;slot<inventory.getCapacity()&&left>0;slot++) {
                var stack=inventory.getItemStack(slot);
                if(!matches(stack,entry.getKey())||!available.test(stack))continue;
                int amount=Math.min(left,stack.getQuantity());
                var result=inventory.removeItemStackFromSlot(slot,stack,amount,true,false);
                if(!result.succeeded()) {
                    for(var restore:removed) inventory.addItemStack(restore,true,false,false);
                    return null;
                }
                removed.add(stack.withQuantity(amount)); left-=amount;
            }
            if(left>0){for(var restore:removed)inventory.addItemStack(restore,true,false,false);return null;}
        }
        return removed;
    }
    public static boolean give(ItemContainer inventory,ItemStack stack) {
        return inventory.addItemStack(stack,true,false,false).succeeded();
    }
    public static String label(String id) { return id.replaceFirst("^(SM_|resource:)", "").replace('_',' '); }
}
