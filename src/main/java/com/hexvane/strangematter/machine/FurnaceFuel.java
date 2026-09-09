package com.hexvane.strangematter.machine;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import java.util.*;
import java.util.function.Consumer;

/** The native furnace accepts the Fuel resource, not every item with a fuel-quality default. */
public final class FurnaceFuel {
    public static final int MAX_FUEL_TICKS=32000;
    private FurnaceFuel() {}
    public record Charge(ItemStack stack,int ticks) {}
    public static int ticks(ItemStack stack) {
        if(stack==null||stack.isEmpty())return 0;
        var fuel=ItemContainer.getMatchingResourceType(stack.getItem(),"Fuel");
        if(fuel==null||fuel.quantity<=0)return 0;
        // ProcessingBenchBlock burns consumed resource units * FuelQuality seconds.
        double ticks=fuel.quantity*stack.getItem().getFuelQuality()*20;
        return !Double.isFinite(ticks)||ticks<=0?0:(int)Math.min(MAX_FUEL_TICKS,Math.max(1,Math.ceil(ticks)));
    }
    public static Charge takeFirst(ItemContainer inventory) {
        return takeFirst(inventory,MAX_FUEL_TICKS);
    }
    public static Charge takeFirst(ItemContainer inventory,int remainingTicks) {
        var taken=takeUpTo(inventory,remainingTicks,1,charges->{});
        return taken.isEmpty()?null:taken.getFirst();
    }
    /**
     * One native write transaction locks all backpack/storage/hotbar containers. Plan every
     * replacement and publish the prepared queue before any slot changes or inventory events.
     * The publisher must only update the caller's already-locked machine state, never inventory.
     * A planning/publishing failure leaves all inventory slots unchanged.
     */
    static List<Charge> takeUpTo(ItemContainer inventory,int remainingTicks,int maxItems,Consumer<List<Charge>> publish){
        if(remainingTicks<=0||maxItems<=0)return List.of();
        var taken=new ArrayList<Charge>();var replacements=new HashMap<Short,ItemStack>();boolean[] planned={false};
        inventory.replaceAll((slot,stack)->{
            if(!planned[0]){
                planned[0]=true;int remaining=Math.min(MAX_FUEL_TICKS,remainingTicks);
                // replaceAll holds the native reentrant write locks during this complete snapshot.
                for(short s=0;s<inventory.getCapacity()&&remaining>0&&taken.size()<maxItems;s++){
                    var existing=inventory.getItemStack(s);int duration=ticks(existing);
                    if(duration<=0||duration>remaining)continue;
                    int count=Math.min(existing.getQuantity(),Math.min(remaining/duration,maxItems-taken.size()));
                    var item=existing.withQuantity(1);
                    for(int n=0;n<count;n++)taken.add(new Charge(item,duration));
                    replacements.put(s,count==existing.getQuantity()?null:existing.withQuantity(existing.getQuantity()-count));
                    remaining-=count*duration;
                }
                if(!taken.isEmpty())publish.accept(List.copyOf(taken));
            }
            return replacements.containsKey(slot)?replacements.get(slot):stack;
        });
        return List.copyOf(taken);
    }
    public static long storedTicks(MachineState state){return Math.max(0L,state.fuelTicks)+Math.max(0L,state.queuedFuelTicks);}
    public static int remainingCapacity(MachineState state){return (int)Math.max(0,MAX_FUEL_TICKS-storedTicks(state));}
}
