package com.hexvane.strangematter.automation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.joml.Vector3i;

/** Durable ownership of an intact machine; a parcel token can restore its payload once. */
final class FactoryParcelLedger {
    static final String TOKEN="SMFactoryParcel";
    enum Phase { PACKING, REMOVING, RETURNING, AVAILABLE, PLACING, PLACED, BROKEN }
    static final class Receipt {
        String token=UUID.randomUUID().toString(),itemId,payload,world,sourceIdentity,targetIdentity;
        UUID owner;int x,y,z;boolean enabled;volatile Phase phase=Phase.PACKING;
        Vector3i position(){return new Vector3i(x,y,z);}
        ItemStack item(){return new ItemStack(itemId,1).withMetadata(TOKEN,Codec.STRING,token);}
    }
    private record Saved(int version,List<Receipt> parcels){}
    private static final Gson GSON=new GsonBuilder().setPrettyPrinting().create();
    private final Path file;
    private final Map<String,Receipt> entries=new LinkedHashMap<>();
    private volatile List<Receipt> pending=List.of();
    private String committed;
    FactoryParcelLedger(Path directory){
        file=directory.resolve("factory-parcels.json");
        if(Files.exists(file))try(var reader=Files.newBufferedReader(file)){
            var saved=GSON.fromJson(reader,Saved.class);
            if(saved==null||saved.version!=1||saved.parcels==null)throw new IOException("Invalid factory parcel ledger");
            for(var r:saved.parcels){
                if(r==null||r.token==null||r.owner==null||r.phase==null||r.world==null||r.sourceIdentity==null||r.payload==null||!FactoryService.packableMachine(r.itemId)||entries.putIfAbsent(r.token,r)!=null||(r.phase==Phase.PLACING||r.phase==Phase.PLACED)&&r.targetIdentity==null)throw new IOException("Invalid factory parcel");
                UUID.fromString(r.token);org.bson.BsonDocument.parse(r.payload);
            }
        }catch(IOException|RuntimeException failure){throw new IllegalStateException("Cannot load factory parcels; original file preserved",failure);}
        committed=snapshot();refreshPending();
    }
    static String token(ItemStack item){try{return ItemStack.isEmpty(item)?null:item.getFromMetadataOrNull(TOKEN,Codec.STRING);}catch(RuntimeException invalid){return "invalid";}}
    synchronized Receipt get(String token){return entries.get(token);}
    synchronized List<Receipt> all(){return List.copyOf(entries.values());}
    List<Receipt> pending(){return pending;}
    synchronized void create(Receipt receipt){entries.put(receipt.token,receipt);save();}
    synchronized void phase(Receipt receipt,Phase phase){receipt.phase=phase;save();}
    synchronized boolean placing(Receipt receipt,String world,Vector3i pos,String identity,UUID owner){if(entries.get(receipt.token)!=receipt||receipt.phase!=Phase.AVAILABLE)return false;receipt.world=world;receipt.x=pos.x;receipt.y=pos.y;receipt.z=pos.z;receipt.targetIdentity=identity;receipt.owner=owner;receipt.phase=Phase.PLACING;save();return true;}
    private String snapshot(){return GSON.toJson(new Saved(1,new ArrayList<>(entries.values())));}
    private void refreshPending(){pending=entries.values().stream().filter(r->r.phase!=Phase.AVAILABLE&&r.phase!=Phase.PLACED&&r.phase!=Phase.BROKEN).toList();}
    private void save(){
        try{
            Files.createDirectories(file.getParent());var next=file.resolveSibling(file.getFileName()+".tmp");String snapshot=snapshot();byte[] data=snapshot.getBytes(StandardCharsets.UTF_8);
            try(var channel=FileChannel.open(next,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)){var buffer=ByteBuffer.wrap(data);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}
            try{Files.move(next,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException unsupported){Files.move(next,file,StandardCopyOption.REPLACE_EXISTING);}
            committed=snapshot;refreshPending();
        }catch(IOException failure){entries.clear();for(var r:GSON.fromJson(committed,Saved.class).parcels)entries.put(r.token,r);refreshPending();throw new UncheckedIOException("Cannot persist factory parcel",failure);}
    }
}
