package com.hexvane.strangematter.equipment;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Durable board identities. A changing private nonce makes stale saved copies unusable. */
final class HoverboardLedger {
    static final String ITEM="SM_Hoverboard", TOKEN="SMBoardToken";
    enum Phase { AVAILABLE, PREPARING, RESERVED, MOUNTED, RETURNING }
    static final class Receipt {
        UUID id,nonce,owner;String item,retiredToken;Phase phase;
        String token(){return id+":"+nonce;}
    }
    private record Saved(int version,List<Receipt> boards){}
    private static final Gson GSON=new GsonBuilder().setPrettyPrinting().create();
    private static final JsonWriterSettings BSON=JsonWriterSettings.builder().outputMode(JsonMode.EXTENDED).build();
    private final Map<UUID,Receipt> entries=new LinkedHashMap<>();
    private final Path file;
    private String committed;
    HoverboardLedger(Path directory){
        file=directory.resolve("hoverboards.json");
        if(Files.exists(file))try(var reader=Files.newBufferedReader(file,StandardCharsets.UTF_8)){
            var saved=GSON.fromJson(reader,Saved.class);
            if(saved==null||saved.version!=1||saved.boards==null)throw new IOException("Invalid hoverboard ledger");
            for(var r:saved.boards){
                if(r.id==null||r.nonce==null||r.owner==null||r.phase==null||r.item==null||entries.putIfAbsent(r.id,r)!=null)throw new IOException("Invalid hoverboard identity");
                var payload=BsonDocument.parse(r.item);
                if(!ITEM.equals(payload.getString("Id").getValue())||payload.getNumber("Quantity").intValue()!=1||!r.token().equals(payload.getDocument("Metadata").getString(TOKEN).getValue()))throw new IOException("Hoverboard payload does not match receipt");
            }
        }catch(IOException e){throw new UncheckedIOException("Cannot load hoverboard ledger; original preserved",e);}
        committed=GSON.toJson(new Saved(1,new ArrayList<>(entries.values())));
        boolean changed=false;
        for(var r:entries.values()){
            // A preparing item was never consumed. Its saved physical copy remains the board.
            if(r.phase==Phase.PREPARING){r.phase=Phase.AVAILABLE;changed=true;}
            else if(r.phase==Phase.RESERVED||r.phase==Phase.MOUNTED){makeReturn(r);changed=true;}
        }
        if(changed)save();
    }
    static String encode(ItemStack item){return ItemStack.CODEC.encode(item,new com.hypixel.hytale.codec.ExtraInfo()).asDocument().toJson(BSON);}
    static ItemStack decode(String item){return ItemStack.CODEC.decode(BsonDocument.parse(item),new com.hypixel.hytale.codec.ExtraInfo());}
    static String token(ItemStack item){return ItemStack.isEmpty(item)?null:item.getFromMetadataOrNull(TOKEN,Codec.STRING);}
    static UUID identity(String token){try{var parts=token.split(":",-1);if(parts.length!=2)return null;UUID.fromString(parts[1]);return UUID.fromString(parts[0]);}catch(RuntimeException ex){return null;}}
    synchronized Receipt prepare(UUID owner,ItemStack item){
        if(!ITEM.equals(item.getItemId())||item.getQuantity()!=1)return null;
        if(entries.values().stream().anyMatch(r->r.owner.equals(owner)&&r.phase!=Phase.AVAILABLE))return null;
        String token=token(item);Receipt r;
        if(token==null){r=new Receipt();r.id=UUID.randomUUID();r.nonce=UUID.randomUUID();entries.put(r.id,r);}
        else{r=entries.get(identity(token));if(r==null||r.phase!=Phase.AVAILABLE||!r.token().equals(token))return null;}
        r.owner=owner;r.phase=Phase.PREPARING;r.item=encode(item.withMetadata(TOKEN,Codec.STRING,r.token()));r.retiredToken=null;save();return r;
    }
    synchronized void cancelPreparing(UUID id){var r=entries.get(id);if(r!=null&&r.phase==Phase.PREPARING){r.phase=Phase.AVAILABLE;save();}}
    synchronized boolean reserve(UUID id){var r=entries.get(id);if(r==null||r.phase!=Phase.PREPARING)return false;r.phase=Phase.RESERVED;save();return true;}
    synchronized void cancelUnconsumed(UUID id){var r=entries.get(id);if(r!=null&&r.phase==Phase.RESERVED){r.phase=Phase.AVAILABLE;save();}}
    synchronized boolean mounted(UUID id){var r=entries.get(id);if(r==null||r.phase!=Phase.RESERVED)return false;r.phase=Phase.MOUNTED;save();return true;}
    synchronized void returnBoard(UUID id){var r=entries.get(id);if(r!=null&&(r.phase==Phase.RESERVED||r.phase==Phase.MOUNTED)){makeReturn(r);save();}}
    private static void makeReturn(Receipt r){
        r.retiredToken=r.token();r.nonce=UUID.randomUUID();r.phase=Phase.RETURNING;
        var payload=BsonDocument.parse(r.item);payload.getDocument("Metadata").put(TOKEN,new BsonString(r.token()));r.item=payload.toJson(BSON);
    }
    synchronized void returned(UUID id){var r=entries.get(id);if(r!=null&&r.phase==Phase.RETURNING){r.phase=Phase.AVAILABLE;save();}}
    synchronized Receipt get(UUID id){return entries.get(id);}
    synchronized int charge(UUID id){var r=entries.get(id);return r==null?0:GadgetEnergy.charge(decode(r.item));}
    /** Prepay mounted use in the durable payload, so crash recovery cannot refill a board. */
    synchronized boolean spend(UUID id,int amount,boolean creative){
        var r=entries.get(id);if(r==null||r.phase!=Phase.MOUNTED||amount<0)return false;if(creative)return true;
        var item=decode(r.item);if(GadgetEnergy.charge(item)<amount)return false;
        r.item=encode(GadgetEnergy.withCharge(item,GadgetEnergy.charge(item)-amount));save();return true;
    }
    synchronized List<Receipt> pendingReturns(UUID owner){return entries.values().stream().filter(r->r.owner.equals(owner)&&r.phase==Phase.RETURNING).toList();}
    synchronized boolean validAvailable(ItemStack stack){var token=token(stack);var r=token==null?null:entries.get(identity(token));return r!=null&&r.phase==Phase.AVAILABLE&&r.token().equals(token);}
    synchronized boolean hasPending(UUID owner){return entries.values().stream().anyMatch(r->r.owner.equals(owner)&&r.phase!=Phase.AVAILABLE);}
    private void save(){
        try{
            Files.createDirectories(file.getParent());Path next=file.resolveSibling(file.getFileName()+".tmp");
            String snapshot=GSON.toJson(new Saved(1,new ArrayList<>(entries.values())));
            byte[] encoded=snapshot.getBytes(StandardCharsets.UTF_8);
            try(var channel=FileChannel.open(next,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)){
                ByteBuffer buffer=ByteBuffer.wrap(encoded);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
            }
            try{Files.move(next,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException ex){Files.move(next,file,StandardCopyOption.REPLACE_EXISTING);}
            committed=snapshot;
        }catch(IOException ex){
            // A failed state transition must not allow a later tick to consume, mount or refund
            // against an intent that was never durable.
            entries.clear();for(var receipt:GSON.fromJson(committed,Saved.class).boards)entries.put(receipt.id,receipt);
            throw new UncheckedIOException("Could not persist hoverboard identity",ex);
        }
    }
}
