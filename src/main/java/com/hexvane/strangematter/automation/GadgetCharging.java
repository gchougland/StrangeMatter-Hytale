package com.hexvane.strangematter.automation;

import com.hexvane.strangematter.equipment.GadgetEnergy;
import com.hexvane.strangematter.machine.MachineState;
import com.hexvane.strangematter.util.StackData;
import com.hypixel.hytale.server.core.inventory.ItemStack;

/** One authoritative dock transfer. Item charge and the source reserve share one native holder. */
public final class GadgetCharging {
    private GadgetCharging() {}
    public static boolean accepts(ItemStack stack){return !ItemStack.isEmpty(stack)&&stack.getQuantity()==1&&GadgetEnergy.powered(stack);}
    public static boolean depleted(ItemStack stack){return accepts(stack)&&GadgetEnergy.charge(stack)<GadgetEnergy.capacity(stack);}
    public static boolean full(ItemStack stack){return accepts(stack)&&GadgetEnergy.charge(stack)>=GadgetEnergy.capacity(stack);}
    public static int transfer(MachineState state,FactoryComponent component,int rate){
        if(!state.enabled||rate<=0||state.energy<=0)return 0;
        var stack=component.charging.getItemStack((short)0);if(!depleted(stack))return 0;
        int charge=GadgetEnergy.charge(stack),amount=Math.min(Math.min(rate,state.energy),GadgetEnergy.capacity(stack)-charge);
        if(amount<=0)return 0;
        var updated=GadgetEnergy.withCharge(stack,charge+amount);
        // Native stack equality alone can ignore auxiliary metadata; compare the complete stack.
        var live=component.charging.getItemStack((short)0);
        if(ItemStack.isEmpty(live)||!StackData.encode(stack).equals(StackData.encode(live)))return 0;
        var tx=component.charging.setItemStackForSlot((short)0,updated,true);
        if(!tx.succeeded())return 0;
        state.energy-=amount;component.data.energy=state.energy;
        return amount;
    }
    public static String status(MachineState state,FactoryComponent component){
        if(!state.enabled)return "Paused";
        var stack=component.charging.getItemStack((short)0);
        if(!accepts(stack))return "Ready";
        if(full(stack))return "Full";
        return state.energy<=0?"No Power":"Charging";
    }
}
