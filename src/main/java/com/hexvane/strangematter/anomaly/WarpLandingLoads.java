package com.hexvane.strangematter.anomaly;

import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import org.joml.Vector3d;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Bounded destination preparation. Completion always returns to the owning world thread. */
final class WarpLandingLoads {
    private static final long RETRY_NANOS = 5_000_000_000L;
    private static final class Request { boolean pending=true; long retryAfter; }
    private final Map<World,Map<UUID,Request>> requests=new ConcurrentHashMap<>();
    private final BiFunction<World,Long,CompletableFuture<?>> loader;
    private final LongSupplier clock;

    WarpLandingLoads(){this((world,index)->world.getChunkAsync(index),System::nanoTime);}
    WarpLandingLoads(BiFunction<World,Long,CompletableFuture<?>> loader,LongSupplier clock){this.loader=loader;this.clock=clock;}

    /** The 6-block candidate ring plus any supported 16-block body fits in these nine chunks. */
    static Set<Long> area(Vector3d center){
        int cx=ChunkUtil.chunkCoordinate((int)Math.floor(center.x)),cz=ChunkUtil.chunkCoordinate((int)Math.floor(center.z));
        var result=new LinkedHashSet<Long>();
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)result.add(ChunkUtil.indexChunk(cx+dx,cz+dz));
        return result;
    }
    boolean ready(World world,Vector3d center){
        for(long index:area(center))if(world.getChunkIfLoaded(index)==null)return false;
        return true;
    }
    /** False means an earlier request is pending or its failure backoff is still active. */
    boolean request(World world,UUID source,Vector3d center,Consumer<Boolean> completion){
        var inWorld=requests.computeIfAbsent(world,key->new HashMap<>());
        var previous=inWorld.get(source);
        if(previous!=null&&(previous.pending||clock.getAsLong()<previous.retryAfter))return false;
        var request=new Request();inWorld.put(source,request);
        var futures=new ArrayList<CompletableFuture<?>>();
        for(long index:area(center)){
            if(world.getChunkIfLoaded(index)!=null)continue;
            try {futures.add(Objects.requireNonNull(loader.apply(world,index)).thenApply(chunk->{
                if(chunk==null)throw new IllegalStateException("Warp destination chunk was unavailable: "+index);
                return chunk;
            }));}
            catch(RuntimeException error){futures.add(CompletableFuture.failedFuture(error));}
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).whenComplete((ignored,error)->{
            if(requests.get(world)!=inWorld)return;
            try {world.execute(()->{
                // World removal or a newer request must not revive an old callback.
                if(requests.get(world)!=inWorld||inWorld.get(source)!=request)return;
                if(error==null)inWorld.remove(source);
                else {request.pending=false;request.retryAfter=clock.getAsLong()+RETRY_NANOS;}
                completion.accept(error==null);
            });}catch(RuntimeException stopped){
                // Native World.execute rejects tasks once shutdown starts. Do not retain
                // its requests or deliver callbacks on a foreign thread after that boundary.
                requests.remove(world,inWorld);
            }
        });
        return true;
    }
    void cancel(World world,UUID source){var pending=requests.get(world);if(pending!=null)pending.remove(source);}
    void clear(World world){requests.remove(world);}
}
