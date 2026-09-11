package com.hexvane.strangematter.automation;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import org.bson.BsonDocument;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;
import java.util.*;

/** Complete native stack snapshots, including maximum durability, quality and extension metadata. */
public final class TubeStacks {
    private static final JsonWriterSettings BSON=JsonWriterSettings.builder().outputMode(JsonMode.EXTENDED).build();
    private TubeStacks(){}
    public static String encode(ItemStack stack){return ItemStack.isEmpty(stack)?null:ItemStack.CODEC.encode(stack,new com.hypixel.hytale.codec.ExtraInfo()).asDocument().toJson(BSON);}
    public static ItemStack decode(String encoded){return encoded==null?null:ItemStack.CODEC.decode(BsonDocument.parse(encoded),new com.hypixel.hytale.codec.ExtraInfo());}
    public static ItemStack quantity(ItemStack original,int amount){
        if(amount==0)return null;
        var bson=ItemStack.CODEC.encode(original,new com.hypixel.hytale.codec.ExtraInfo()).asDocument();bson.put("Quantity",new org.bson.BsonInt32(amount));return ItemStack.CODEC.decode(bson,new com.hypixel.hytale.codec.ExtraInfo());
    }
    public static List<String> snapshot(ItemContainer inventory){
        var result=new ArrayList<String>(inventory.getCapacity());for(short slot=0;slot<inventory.getCapacity();slot++)result.add(encode(inventory.getItemStack(slot)));return result;
    }
    /** Detached planning inventory. Combined and delegated native containers need not implement clone(). */
    static com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer copy(ItemContainer inventory){
        var result=new com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer(inventory.getCapacity());
        var filters=new NativeFilters(inventory);
        for(short slot=0;slot<inventory.getCapacity();slot++){
            result.setItemStackForSlot(slot,decode(encode(inventory.getItemStack(slot))),false);
            result.setSlotFilter(com.hypixel.hytale.server.core.inventory.container.filter.FilterActionType.ADD,slot,
                (action,container,index,stack)->filters.add(index,stack,container.getItemStack(index)));
            result.setSlotFilter(com.hypixel.hytale.server.core.inventory.container.filter.FilterActionType.REMOVE,slot,
                (action,container,index,stack)->filters.remove(index));
        }
        return result;
    }
    /** Native delegation preserves per-slot filters without cloning, reflection, or touching live inventory. */
    private static final class NativeFilters extends com.hypixel.hytale.server.core.inventory.container.DelegateItemContainer<ItemContainer>{
        NativeFilters(ItemContainer inventory){super(inventory);}
        boolean add(short slot,ItemStack incoming,ItemStack current){return !cantAddToSlot(slot,incoming,current);}
        boolean remove(short slot){return !cantRemoveFromSlot(slot);}
    }
    static boolean removable(ItemContainer inventory,short slot){return new NativeFilters(inventory).remove(slot);}
    public static boolean matches(ItemContainer inventory,List<String> snapshot){return snapshot(inventory).equals(snapshot);}
    public static boolean same(ItemStack a,ItemStack b){return Objects.equals(encode(a),encode(b));}
}
