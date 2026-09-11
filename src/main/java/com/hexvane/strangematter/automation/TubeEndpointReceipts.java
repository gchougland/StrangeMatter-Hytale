package com.hexvane.strangematter.automation;

import com.google.gson.Gson;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import java.util.*;

/** Saved alongside the actual container, never in a separate inventory mirror. */
public final class TubeEndpointReceipts implements Component<ChunkStore> {
    static ComponentType<ChunkStore,TubeEndpointReceipts> type;
    private static final Gson GSON=new Gson();
    public static final BuilderCodec<TubeEndpointReceipts> CODEC=BuilderCodec.builder(TubeEndpointReceipts.class,TubeEndpointReceipts::new)
        .append(new KeyedCodec<>("ReceiptState",Codec.STRING),(c,v)->c.read(v),c->GSON.toJson(c.data)).add().build();
    private Data data=new Data();
    private static final class Data {UUID identity=UUID.randomUUID();Map<UUID,Set<String>> applied=new LinkedHashMap<>();Set<UUID> inherited=new HashSet<>();Boolean processingActive;}
    public static ComponentType<ChunkStore,TubeEndpointReceipts> getComponentType(){return type;}
    public UUID identity(){return data.identity;}
    public boolean owns(UUID id){return data.identity.equals(id)||data.inherited.contains(id);}
    public boolean contains(UUID transfer){return data.applied.containsKey(transfer);}
    public boolean contains(UUID transfer,String section){return data.applied.getOrDefault(transfer,Set.of()).contains(section);}
    public void applied(UUID transfer,String section){data.applied.computeIfAbsent(transfer,k->new HashSet<>()).add(section);}
    public int size(){return data.applied.size();}
    public Set<UUID> transfers(){return Set.copyOf(data.applied.keySet());}
    public void retire(UUID transfer){data.applied.remove(transfer);}
    Boolean processingActive(){return data.processingActive;}
    void processingActive(Boolean value){data.processingActive=value;}
    public void merge(TubeEndpointReceipts previous){data.inherited.add(previous.identity());data.inherited.addAll(previous.data.inherited);previous.data.applied.forEach((id,sections)->data.applied.computeIfAbsent(id,k->new HashSet<>()).addAll(sections));}
    private void read(String raw){var next=GSON.fromJson(raw,Data.class);if(next==null||next.identity==null||next.applied==null||next.inherited==null)throw new IllegalArgumentException("Invalid tube endpoint receipt");data=next;}
    @Override public TubeEndpointReceipts clone(){var c=new TubeEndpointReceipts();c.read(GSON.toJson(data));return c;}
}
