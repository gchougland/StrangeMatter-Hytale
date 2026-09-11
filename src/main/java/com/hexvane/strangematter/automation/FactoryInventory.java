package com.hexvane.strangematter.automation;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.MaterialQuantity;
import com.hypixel.hytale.server.core.inventory.container.*;
import java.util.*;
import java.util.function.Predicate;

/** Plan against an isolated native container first, then commit its exact slot removals. */
public final class FactoryInventory {
    public record Removal(short slot,ItemStack before,ItemStack taken){}
    public record Plan(List<Removal> removals,List<ItemStack> stacks){}
    public record Requirement(MaterialQuantity material,int available,int missing){}
    /** Same sequential native matching as reservations, including overlapping resource families. */
    public static List<Requirement> requirements(ItemContainer source,List<MaterialQuantity> materials){
        var staging=snapshot(source);var result=new ArrayList<Requirement>();
        for(var material:materials){int available=InternalContainerUtilMaterial.countMaterialFromItems(staging,material,false);int take=Math.min(available,material.getQuantity());
            if(take>0&&!staging.removeMaterial(material.clone(take),true,true,false).succeeded())available=0;
            result.add(new Requirement(material,available,Math.max(0,material.getQuantity()-available)));
        }
        return List.copyOf(result);
    }
    public static Plan plan(ItemContainer source,List<MaterialQuantity> materials,Predicate<ItemStack> allowed,boolean forge){
        ItemContainer staging=snapshot(source);
        for(short s=0;s<staging.getCapacity();s++){var item=staging.getItemStack(s);if(!ItemStack.isEmpty(item)&&!allowed.test(item))staging.setItemStackForSlot(s,ItemStack.EMPTY,false);}
        for(var material:materials){
            if(forge&&material.getItemId()!=null){int remaining=material.getQuantity();for(short s=0;s<staging.getCapacity()&&remaining>0;s++){var stack=staging.getItemStack(s);if(ItemStack.isEmpty(stack)||!material.getItemId().equals(stack.getItemId()))continue;int n=Math.min(remaining,stack.getQuantity());var t=staging.removeItemStackFromSlot(s,stack,n,true,false);if(!t.succeeded())return null;remaining-=n;}if(remaining!=0)return null;}
            else {var result=staging.removeMaterial(material,true,true,false);if(!result.succeeded())return null;}
        }
        var removals=new ArrayList<Removal>();var stacks=new ArrayList<ItemStack>();
        for(short s=0;s<source.getCapacity();s++){var before=source.getItemStack(s);if(ItemStack.isEmpty(before)||!allowed.test(before))continue;var after=staging.getItemStack(s);int n=before.getQuantity()-(ItemStack.isEmpty(after)?0:after.getQuantity());if(n>0){var taken=TubeStacks.quantity(before,n);removals.add(new Removal(s,before,taken));stacks.add(taken);}}
        return new Plan(List.copyOf(removals),List.copyOf(stacks));
    }
    public static boolean commit(ItemContainer source,Plan plan){
        for(var r:plan.removals){var current=source.getItemStack(r.slot);if(ItemStack.isEmpty(current)||current.getQuantity()!=r.before.getQuantity()||!TubeStacks.same(current,r.before))return false;}
        var removed=new ArrayList<Removal>();
        for(var r:plan.removals){var t=source.removeItemStackFromSlot(r.slot,r.before,r.taken.getQuantity(),true,false);if(!t.succeeded()){for(var undo:removed)source.setItemStackForSlot(undo.slot,undo.before,false);return false;}removed.add(r);}
        return true;
    }
    public static List<ItemStack> stacks(ItemContainer c){var out=new ArrayList<ItemStack>();for(short s=0;s<c.getCapacity();s++){var stack=c.getItemStack(s);if(!ItemStack.isEmpty(stack))out.add(stack);}return out;}
    public static boolean fits(ItemContainer c,List<ItemStack> items){var copy=snapshot(c);for(var item:items)if(!copy.addItemStack(item,true,false,false).succeeded())return false;return true;}
    /** Combined and delegated native inventory views deliberately do not implement clone(). */
    private static ItemContainer snapshot(ItemContainer source){if(source.getCapacity()==0)return EmptyItemContainer.INSTANCE;var copy=new SimpleItemContainer(source.getCapacity());for(short slot=0;slot<source.getCapacity();slot++)copy.setItemStackForSlot(slot,source.getItemStack(slot),false);return copy;}
    public static void add(ItemContainer c,List<ItemStack> items){for(var item:items){var tx=c.addItemStack(item,true,false,false);if(!tx.succeeded())throw new IllegalStateException("Reserved factory inventory no longer fits");}}
    public static void clear(ItemContainer c){for(short s=0;s<c.getCapacity();s++)c.setItemStackForSlot(s,ItemStack.EMPTY,false);}
    private FactoryInventory(){}
}
