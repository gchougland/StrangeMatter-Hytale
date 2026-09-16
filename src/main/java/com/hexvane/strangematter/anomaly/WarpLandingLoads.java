package com.hexvane.strangematter.anomaly;

import com.hexvane.strangematter.util.WorldAccess;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.ChunkFlag;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import org.joml.Vector3d;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** World-thread preparation with shared native loads, bounded concurrency and paced starts. */
final class WarpLandingLoads {
    static final int MAX_IN_FLIGHT=2,MAX_REQUESTS=8;
    static final long START_INTERVAL_NANOS=50_000_000L,TIMEOUT_NANOS=30_000_000_000L;
    static final long RETAIN_NANOS=10_000_000_000L;
    static final long ARRIVAL_NANOS=4_000_000_000L;
    private static final long RETRY_NANOS=5_000_000_000L;
    private static final class Request {
        final Set<Long> remaining;
        final Consumer<Boolean> completion;
        final BooleanSupplier current;
        final long deadline;
        boolean finishing;
        long retryAfter;
        Request(Set<Long> remaining,BooleanSupplier current,Consumer<Boolean> completion,long deadline){this.remaining=remaining;this.current=current;this.completion=completion;this.deadline=deadline;}
    }
    private static final class InWorld {
        final Map<UUID,Request> requests=new LinkedHashMap<>();
        final Map<Long,Object> loading=new HashMap<>();
        final Map<UUID,Landing> landings=new LinkedHashMap<>();
        long nextStart;
        int cursor;
    }
    /** At most eight nine-column areas per world, released after approach/arrival or cancellation. */
    private static final class Landing {
        final Set<Long> area;
        final Map<Long,WorldChunk> retained=new HashMap<>();
        BooleanSupplier current;
        long until;
        long arrivalUntil;
        Landing(Set<Long> area,BooleanSupplier current,long until){this.area=Set.copyOf(area);this.current=current;this.until=until;}
        void release(){retained.values().forEach(WorldChunk::removeKeepLoaded);retained.clear();}
    }
    private final Map<World,InWorld> requests=new ConcurrentHashMap<>();
    private final BiFunction<World,Long,CompletableFuture<?>> loader;
    private final LongSupplier clock;

    WarpLandingLoads(){this((world,index)->WorldAccess.load(world,index),System::nanoTime);}
    WarpLandingLoads(BiFunction<World,Long,CompletableFuture<?>> loader,LongSupplier clock){this.loader=loader;this.clock=clock;}

    /** The six-block candidate ring and every supported body fit inside these nine columns. */
    static Set<Long> area(Vector3d center){
        int cx=ChunkUtil.chunkCoordinate((int)Math.floor(center.x)),cz=ChunkUtil.chunkCoordinate((int)Math.floor(center.z));
        var result=new LinkedHashSet<Long>();
        result.add(ChunkUtil.indexChunk(cx,cz));
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)result.add(ChunkUtil.indexChunk(cx+dx,cz+dz));
        return result;
    }
    boolean ready(World world,Vector3d center){
        for(long index:area(center))if(WorldAccess.loaded(world,index)==null)return false;
        return true;
    }
    /** Keep an approached destination usable through the next gate tick and native client arrival. */
    boolean retainReady(World world,UUID source,Vector3d center,BooleanSupplier current){
        return retainReady(world,source,center,current,true);
    }
    boolean retainReady(World world,UUID source,Vector3d center,BooleanSupplier current,boolean urgent){
        world.getChunkStore().getStore().assertThread();
        long now=clock.getAsLong();var inWorld=state(world,now);
        return retain(world,inWorld,source,area(center),current,now,urgent)&&ready(world,center);
    }
    /** Speculative prefetch may yield its slot; an actual arrival gets its streaming window first. */
    void arriving(World world,UUID source){
        var inWorld=requests.get(world);var landing=inWorld==null?null:inWorld.landings.get(source);
        if(landing!=null){long now=clock.getAsLong();landing.arrivalUntil=now+ARRIVAL_NANOS;landing.until=now+RETAIN_NANOS;}
    }
    private InWorld state(World world,long now){
        return requests.computeIfAbsent(world,key->{var state=new InWorld();state.nextStart=now;return state;});
    }
    private boolean retain(World world,InWorld inWorld,UUID source,Set<Long> area,BooleanSupplier current,long now,boolean urgent){
        var landing=inWorld.landings.get(source);
        if(landing!=null&&!landing.area.equals(area)){release(inWorld,source);landing=null;}
        if(landing==null){
            if(inWorld.landings.size()>=MAX_REQUESTS){
                if(!urgent)return false;
                var idle=inWorld.landings.entrySet().stream().filter(entry->{
                    var request=inWorld.requests.get(entry.getKey());
                    return (request==null||request.finishing)&&now>=entry.getValue().arrivalUntil;
                }).min(Comparator.comparingLong(entry->entry.getValue().until));
                if(idle.isEmpty())return false;
                release(inWorld,idle.get().getKey());
            }
            landing=new Landing(area,current,now+RETAIN_NANOS);inWorld.landings.put(source,landing);
        }
        landing.current=current;landing.until=now+RETAIN_NANOS;
        refresh(world,landing);
        return true;
    }
    private static void refresh(World world,Landing landing){
        for(long index:landing.area){
            var chunk=WorldAccess.inMemory(world,index);var previous=landing.retained.get(index);
            if(previous!=chunk){
                if(previous!=null){previous.removeKeepLoaded();landing.retained.remove(index);}
                if(chunk!=null){chunk.addKeepLoaded();landing.retained.put(index,chunk);}
            }
            if(chunk!=null){
                // KeepLoaded only prevents removal. Native unloading can still park
                // a retained column, and SET_TICKING alone does not reset its active timer.
                chunk.resetKeepAlive();chunk.resetActiveTimer();chunk.setFlag(ChunkFlag.TICKING,true);
            }
        }
    }
    private static void release(InWorld inWorld,UUID source){var landing=inWorld.landings.remove(source);if(landing!=null)landing.release();}
    /** Inspect a natural destination's surface before generating its surrounding landing area. */
    boolean requestCenter(World world,UUID source,Vector3d center,Consumer<Boolean> completion){
        return requestCenter(world,source,center,()->true,completion);
    }
    boolean requestCenter(World world,UUID source,Vector3d center,BooleanSupplier current,Consumer<Boolean> completion){
        return request(world,source,Set.of(ChunkUtil.indexChunkFromBlock((int)Math.floor(center.x),(int)Math.floor(center.z))),current,completion,true);
    }
    boolean request(World world,UUID source,Vector3d center,Consumer<Boolean> completion){return request(world,source,center,()->true,completion);}
    boolean request(World world,UUID source,Vector3d center,BooleanSupplier current,Consumer<Boolean> completion){return request(world,source,center,current,completion,true);}
    boolean request(World world,UUID source,Vector3d center,BooleanSupplier current,Consumer<Boolean> completion,boolean urgent){return request(world,source,area(center),current,completion,urgent);}
    private boolean request(World world,UUID source,Set<Long> area,BooleanSupplier current,Consumer<Boolean> completion,boolean urgent){
        world.getChunkStore().getStore().assertThread();
        long now=clock.getAsLong();var inWorld=state(world,now);
        var previous=inWorld.requests.get(source);
        if(previous!=null&&invalidate(inWorld,source,previous))previous=null;
        if(previous!=null&&(!previous.finishing||previous.retryAfter==0||now<previous.retryAfter))return false;
        inWorld.requests.entrySet().removeIf(e->e.getValue().finishing&&e.getValue().retryAfter!=0&&now>=e.getValue().retryAfter);
        if(inWorld.requests.size()>=MAX_REQUESTS)return false;
        if(!retain(world,inWorld,source,area,current,now,urgent))return false;
        var remaining=new LinkedHashSet<Long>();for(long index:area)if(WorldAccess.loaded(world,index)==null)remaining.add(index);
        var request=new Request(remaining,current,completion,now+TIMEOUT_NANOS);inWorld.requests.put(source,request);
        if(remaining.isEmpty())finish(world,inWorld,source,request,true);
        else tick(world);
        return true;
    }
    /** Call once per anomaly tick. Completion never launches another generation burst. */
    void tick(World world){
        var inWorld=requests.get(world);if(inWorld==null)return;
        world.getChunkStore().getStore().assertThread();long now=clock.getAsLong();
        for(var entry:new ArrayList<>(inWorld.landings.entrySet())){
            var landing=entry.getValue();var request=inWorld.requests.get(entry.getKey());
            if(!landing.current.getAsBoolean()){release(inWorld,entry.getKey());continue;}
            if(request!=null&&!request.finishing)landing.until=now+RETAIN_NANOS;
            if(now>=landing.until){release(inWorld,entry.getKey());continue;}
            refresh(world,landing);
        }
        for(var entry:new ArrayList<>(inWorld.requests.entrySet())){
            var request=entry.getValue();if(invalidate(inWorld,entry.getKey(),request)||request.finishing)continue;
            if(now>=request.deadline){finish(world,inWorld,entry.getKey(),request,false);continue;}
            request.remaining.removeIf(index->WorldAccess.loaded(world,index)!=null);
            if(request.remaining.isEmpty())finish(world,inWorld,entry.getKey(),request,true);
        }
        if(inWorld.loading.size()>=MAX_IN_FLIGHT||now<inWorld.nextStart)return;
        var candidates=new ArrayList<>(inWorld.requests.values());if(candidates.isEmpty())return;
        for(int n=0;n<candidates.size();n++){
            var request=candidates.get(Math.floorMod(inWorld.cursor++,candidates.size()));if(request.finishing)continue;
            for(long index:request.remaining){
                if(inWorld.loading.containsKey(index))continue;
                inWorld.nextStart=now+START_INTERVAL_NANOS;
                var token=new Object();inWorld.loading.put(index,token);
                CompletableFuture<?> future;
                try{future=Objects.requireNonNull(loader.apply(world,index));}
                catch(RuntimeException failure){future=CompletableFuture.failedFuture(failure);}
                future.whenComplete((chunk,error)->execute(world,inWorld,()->{
                    if(!inWorld.loading.remove(index,token))return;
                    // A real native completion must be retained before another unload
                    // pass can park it while the remaining columns are still generating.
                    for(var landing:inWorld.landings.values())if(landing.area.contains(index))refresh(world,landing);
                    for(var entry:new ArrayList<>(inWorld.requests.entrySet())){
                        var waiting=entry.getValue();if(invalidate(inWorld,entry.getKey(),waiting)||waiting.finishing||!waiting.remaining.contains(index))continue;
                        if(error!=null||chunk==null)finish(world,inWorld,entry.getKey(),waiting,false);
                        else{waiting.remaining.remove(index);if(waiting.remaining.isEmpty())finish(world,inWorld,entry.getKey(),waiting,true);}
                    }
                }));
                return;
            }
        }
    }
    private void finish(World world,InWorld inWorld,UUID source,Request request,boolean success){
        if(request.finishing)return;request.finishing=true;
        if(!success)release(inWorld,source);
        request.retryAfter=success?0:clock.getAsLong()+RETRY_NANOS;
        execute(world,inWorld,()->{
            if(inWorld.requests.get(source)!=request)return;
            if(invalidate(inWorld,source,request))return;
            if(success)inWorld.requests.remove(source);
            request.completion.accept(success);
        });
    }
    private boolean invalidate(InWorld inWorld,UUID source,Request request){
        if(request.current.getAsBoolean())return false;
        // Deliver only while this request still owns the source slot. A stale native
        // completion must never clear a newer route's preparation state or add backoff.
        if(inWorld.requests.remove(source,request)){release(inWorld,source);request.completion.accept(false);}
        return true;
    }
    private void execute(World world,InWorld inWorld,Runnable task){
        if(requests.get(world)!=inWorld)return;
        try{world.execute(()->{if(requests.get(world)==inWorld)task.run();});}
        catch(RuntimeException stopped){if(requests.remove(world,inWorld))inWorld.landings.values().forEach(Landing::release);}
    }
    // Native futures belong to ChunkStore and may be shared with players. Cancellation
    // removes our queued work, but an already-issued load retains its slot until it resolves.
    void cancel(World world,UUID source){var inWorld=requests.get(world);if(inWorld!=null){inWorld.requests.remove(source);release(inWorld,source);}}
    void clear(World world){var inWorld=requests.remove(world);if(inWorld!=null)inWorld.landings.values().forEach(Landing::release);}
}
