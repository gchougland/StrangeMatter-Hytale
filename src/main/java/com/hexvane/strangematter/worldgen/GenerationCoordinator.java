package com.hexvane.strangematter.worldgen;

import com.hexvane.strangematter.anomaly.AnomalyService;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.ChunkSection;
import com.hypixel.hytale.server.core.universe.world.events.ChunkPreLoadProcessEvent;
import com.hypixel.hytale.server.core.universe.world.events.ChunkSectionPreLoadProcessEvent;
import java.util.concurrent.ConcurrentHashMap;

/** Collects modern section events without using the deprecated ChunkColumn bridge. */
public final class GenerationCoordinator {
    private record Key(World world,long index) {}
    private final ConcurrentHashMap<Key,GenerationColumn> pending=new ConcurrentHashMap<>();
    private final AnomalyService anomalies;
    private final ScientistService scientists;
    public GenerationCoordinator(AnomalyService anomalies,ScientistService scientists){this.anomalies=anomalies;this.scientists=scientists;}
    public void column(ChunkPreLoadProcessEvent event){
        if(!event.isNewlyGenerated())return;
        var chunk=event.getChunk();var blocks=event.getHolder().getComponent(BlockChunk.getComponentType());
        if(blocks!=null)pending.put(new Key(chunk.getWorld(),chunk.getIndex()),new GenerationColumn(chunk,blocks));
    }
    public void section(ChunkSectionPreLoadProcessEvent event){
        if(!event.isNewlyGenerated())return;
        var section=event.getHolder().getComponent(ChunkSection.getComponentType());if(section==null)return;
        var key=new Key(event.getWorld(),ChunkUtil.indexChunk(section.getX(),section.getZ()));
        var column=pending.get(key);if(column==null||!column.add(event.getHolder()))return;
        if(!pending.remove(key,column))return;
        if(scientists!=null)scientists.generate(column);
        if(anomalies!=null)anomalies.generate(column);
    }
    public void cleanup(World world){pending.keySet().removeIf(key->key.world==world);}
}
