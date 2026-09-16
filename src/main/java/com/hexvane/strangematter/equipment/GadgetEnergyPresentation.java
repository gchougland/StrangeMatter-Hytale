package com.hexvane.strangematter.equipment;

import com.hypixel.hytale.protocol.*;
import com.hypixel.hytale.protocol.packets.inventory.UpdatePlayerInventory;
import com.hypixel.hytale.protocol.packets.window.OpenWindow;
import com.hypixel.hytale.protocol.packets.window.UpdateWindow;
import com.hypixel.hytale.server.core.asset.type.item.config.metadata.ItemDisplayMetadata;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.io.adapter.*;
import org.bson.BsonDocument;
import java.util.HashMap;

/** Derive inventory tooltips at the wire boundary; never rewrite the held stack's identity. */
public final class GadgetEnergyPresentation implements AutoCloseable {
    private final PacketFilter outbound=PacketAdapters.registerOutbound((PlayerPacketFilter)(player,packet)->{project(packet);return false;});
    public static void project(Packet packet){
        if(packet instanceof UpdatePlayerInventory inventory){
            inventory.storage=section(inventory.storage);inventory.armor=section(inventory.armor);
            inventory.hotbar=section(inventory.hotbar);inventory.utility=section(inventory.utility);
            inventory.tools=section(inventory.tools);inventory.backpack=section(inventory.backpack);
        }else if(packet instanceof OpenWindow window)window.inventory=section(window.inventory);
        else if(packet instanceof UpdateWindow window)window.inventory=section(window.inventory);
    }
    private static InventorySection section(InventorySection source){
        if(source==null||source.items==null)return source;
        HashMap<Integer,ItemWithAllMetadata> items=null;
        for(var entry:source.items.entrySet()){
            var packet=entry.getValue();if(packet==null||packet.quantity!=1||GadgetEnergy.capacity(packet.itemId)==0)continue;
            try{
                var stack=new ItemStack(packet.itemId,packet.quantity,packet.durability,packet.maxDurability,packet.quality,
                        packet.metadata==null?null:BsonDocument.parse(packet.metadata));
                var normalized=GadgetEnergy.normalize(stack);
                var display=normalized.getFromMetadataOrNull(ItemDisplayMetadata.KEYED_CODEC);
                var original=normalized.getMetadata().get(ItemDisplayMetadata.KEY);
                var shown=normalized.withMetadata("SMGadgetOriginalDisplay",original==null?new BsonDocument():original)
                        .withMetadata(ItemDisplayMetadata.KEYED_CODEC,new ItemDisplayMetadata(
                        display==null?null:display.getName(),GadgetEnergy.description(normalized))).toPacket().clone();
                shown.overrideDroppedItemAnimation=packet.overrideDroppedItemAnimation;
                if(items==null)items=new HashMap<>(source.items);items.put(entry.getKey(),shown);
            }catch(RuntimeException invalidMetadata){/* Keep a malformed foreign stack visible and movable. */}
        }
        return items==null?source:new InventorySection(items,source.capacity);
    }
    @Override public void close(){PacketAdapters.deregisterOutbound(outbound);}
}
