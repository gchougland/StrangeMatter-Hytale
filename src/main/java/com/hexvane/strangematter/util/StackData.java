package com.hexvane.strangematter.util;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import org.bson.BsonDocument;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;

/** Complete native stack serialization, including quality, durability and typed metadata. */
public final class StackData {
    private static final JsonWriterSettings JSON=JsonWriterSettings.builder().outputMode(JsonMode.EXTENDED).build();
    private StackData(){}
    public static String encode(ItemStack stack){return ItemStack.CODEC.encode(stack,new ExtraInfo()).asDocument().toJson(JSON);}
    public static ItemStack decode(String encoded){return ItemStack.CODEC.decode(BsonDocument.parse(encoded),new ExtraInfo());}
    public static BsonDocument metadata(ItemStack stack){
        var value=ItemStack.CODEC.encode(stack,new ExtraInfo()).asDocument().get("Metadata");
        return value==null||value.isNull()?null:value.asDocument().clone();
    }
}
