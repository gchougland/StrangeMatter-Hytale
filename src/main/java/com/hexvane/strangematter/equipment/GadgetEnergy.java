package com.hexvane.strangematter.equipment;

import com.google.gson.*;
import com.hexvane.strangematter.util.StackData;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.metadata.ItemDisplayMetadata;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import org.bson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Item-owned RE. The stable v2 marker identifies native numeric charge fields without changing equipment identity. */
public final class GadgetEnergy {
    public static final String KEY="SMGadgetEnergy", PACK="SM_Resonant_Battery_Pack";
    private static final String DISPLAY="SMGadgetOriginalDisplay";
    private static final Map<String,Integer> DEFAULT_CAPACITIES=Map.ofEntries(
        Map.entry("SM_Field_Scanner",2000),Map.entry("SM_Echo_Vacuum",4000),Map.entry("SM_Anomaly_Resonator",4000),
        Map.entry("SM_Warp_Gun",10000),Map.entry("SM_Chrono_Blister",10000),Map.entry("SM_Graviton_Hammer",10000),
        Map.entry("SM_Hoverboard",12000),Map.entry("SM_Echoform_Imprinter",12000),Map.entry(PACK,60000),
        Map.entry("SM_Gravitic_Manipulator",12000),Map.entry("SM_Arc_Projector",12000));
    private static final Map<String,Integer> DEFAULT_COSTS=Map.ofEntries(
        Map.entry("scan",20),Map.entry("capture",100),Map.entry("resonate",40),Map.entry("warp",100),Map.entry("chrono",100),
        Map.entry("hammer0",40),Map.entry("hammer1",100),Map.entry("hammer2",160),Map.entry("hammer3",240),
        Map.entry("board_start",100),Map.entry("board_second",40),Map.entry("imprint_start",100),Map.entry("imprint_second",20),
        Map.entry("gravity_grab",100),Map.entry("gravity_second",40),Map.entry("gravity_launch",300),Map.entry("arc_fire",200),Map.entry("pack_second",200));
    private static volatile Map<String,Integer> capacities=DEFAULT_CAPACITIES,costs=DEFAULT_COSTS;
    private GadgetEnergy(){}
    public static void configure(Path directory){
        Path path=directory.resolve("gadget-energy.json");
        if(!Files.exists(path)){capacities=DEFAULT_CAPACITIES;costs=DEFAULT_COSTS;return;}
        try(var reader=Files.newBufferedReader(path)){
            var root=JsonParser.parseReader(reader).getAsJsonObject();
            for(String key:root.keySet())if(!Set.of("capacities","costs").contains(key))throw new IllegalArgumentException("Unknown gadget setting: "+key);
            var nextCaps=overrides(root,"capacities",DEFAULT_CAPACITIES,1);var nextCosts=overrides(root,"costs",DEFAULT_COSTS,0);
            capacities=nextCaps;costs=nextCosts;
        }catch(IOException|RuntimeException ex){throw new IllegalStateException("Invalid gadget-energy.json",ex);}
    }
    private static Map<String,Integer> overrides(JsonObject root,String field,Map<String,Integer> defaults,int min){
        var result=new HashMap<>(defaults);if(root.has(field))for(var e:root.getAsJsonObject(field).entrySet()){
            if(!defaults.containsKey(e.getKey())||!e.getValue().isJsonPrimitive()||!e.getValue().getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("Unknown/invalid "+field+": "+e.getKey());
            int value=e.getValue().getAsBigDecimal().intValueExact();if(value<min||value>10000000)throw new IllegalArgumentException("Invalid gadget value");result.put(e.getKey(),value);
        }return Map.copyOf(result);
    }
    public static int cost(String action){return costs.getOrDefault(action,0);}
    public static int capacity(String id){return capacities.getOrDefault(id,0);}
    public static int capacity(ItemStack stack){return ItemStack.isEmpty(stack)?0:capacity(stack.getItemId());}
    public static boolean powered(ItemStack stack){return capacity(stack)>0&&stack.getQuantity()==1;}
    public static int charge(ItemStack stack){
        int cap=capacity(stack);if(cap==0)return 0;
        var metadata=StackData.metadata(stack);var value=metadata==null?null:metadata.get(KEY);
        if(value!=null){
            if(!value.isDocument())return 0;var data=value.asDocument();var version=data.get("Version");var stored=data.get("Charge");
            if(version==null||!version.isInt32())return 0;
            if(version.asInt32().getValue()==2){
                double charge=stack.getDurability(),maximum=stack.getMaxDurability();
                if(!Double.isFinite(charge)||!Double.isFinite(maximum)||maximum<=0||charge<0||charge>maximum)return 0;
                return (int)Math.min(cap,charge);
            }
            if(version.asInt32().getValue()!=1||stored==null||!stored.isInt32())return 0;
            return Math.clamp(stored.asInt32().getValue(),0,cap);
        }
        // Saved native stacks carry their original MaxDurability even after assets remove it.
        if(stack.getMaxDurability()>0){double fraction=stack.getDurability()/stack.getMaxDurability();return Double.isFinite(fraction)?(int)(Math.clamp(fraction,0,1)*cap):0;}
        return cap;
    }
    public static ItemStack normalize(ItemStack stack){return powered(stack)?withCharge(stack,charge(stack)):stack;}
    public static ItemStack withCharge(ItemStack stack,int amount){
        if(!powered(stack))return stack;
        int value=Math.clamp(amount,0,capacity(stack));
        var meta=StackData.metadata(stack);if(meta==null)meta=new BsonDocument();
        // v1 wrote changing tooltip text into identity metadata. Restore the original once.
        var original=meta.remove(DISPLAY);
        if(original!=null){
            if(original.isDocument()&&original.asDocument().isEmpty())meta.remove(ItemDisplayMetadata.KEY);
            else meta.put(ItemDisplayMetadata.KEY,original);
        }
        meta.put(KEY,new BsonDocument("Version",new BsonInt32(2)));
        ItemStack result=stack.withMetadata(meta).withMaxDurability(capacity(stack)).withDurability(value);
        result.setOverrideDroppedItemAnimation(stack.getOverrideDroppedItemAnimation());
        return result.equals(stack)?stack:result;
    }
    /** Numeric tooltip is a presentation value, never part of the saved equipment identity. */
    public static Message description(ItemStack stack){
        return Message.join(stack.getDisplayDescription(),Message.raw("\n\nEnergy: "+charge(stack)+" / "+capacity(stack)+" RE"));
    }
    public static String descriptionText(ItemStack stack){return plain(description(stack));}
    private static String plain(Message message){
        var text=new StringBuilder(message.getAnsiMessage());
        for(var child:message.getChildren())text.append(plain(child));
        return text.toString();
    }
    public static boolean spend(ItemContainer inventory,short slot,ItemStack expected,int amount,boolean creative){
        if(amount<0||inventory==null||slot<0||slot>=inventory.getCapacity()||!powered(expected)||!Objects.equals(expected,inventory.getItemStack(slot)))return false;
        if(creative)return true;if(charge(expected)<amount)return false;
        return inventory.setItemStackForSlot(slot,withCharge(expected,charge(expected)-amount),false).succeeded();
    }
    public static final class Debit implements AutoCloseable {
        private final ItemContainer inventory;private final short slot;private final ItemStack before,after;private boolean committed;
        private Debit(ItemContainer inventory,short slot,ItemStack before,ItemStack after){this.inventory=inventory;this.slot=slot;this.before=before;this.after=after;}
        public void commit(){committed=true;}
        @Override public void close(){if(!committed&&Objects.equals(after,inventory.getItemStack(slot)))inventory.setItemStackForSlot(slot,before,false);}
    }
    public static Debit reserve(ItemContainer inventory,short slot,ItemStack expected,int amount,boolean creative){
        return spend(inventory,slot,expected,amount,creative)?new Debit(inventory,slot,expected,inventory.getItemStack(slot)):null;
    }
    /** Move actual energy only. Call on the owning world thread, using current native containers. */
    public static int transfer(ItemContainer source,short from,ItemContainer target,short to,int limit){
        if(limit<=0||source==null||target==null||(source==target&&from==to))return 0;
        ItemStack battery=source.getItemStack(from),gadget=target.getItemStack(to);
        if(!powered(battery)||!powered(gadget))return 0;
        int moved=Math.min(limit,Math.min(charge(battery),capacity(gadget)-charge(gadget)));if(moved<=0)return 0;
        var debit=withCharge(battery,charge(battery)-moved);
        if(!source.setItemStackForSlot(from,debit,false).succeeded())return 0;
        if(!Objects.equals(gadget,target.getItemStack(to))||!target.setItemStackForSlot(to,withCharge(gadget,charge(gadget)+moved),false).succeeded()){
            if(Objects.equals(debit,source.getItemStack(from)))source.setItemStackForSlot(from,battery,false);
            return 0;
        }return moved;
    }
}
