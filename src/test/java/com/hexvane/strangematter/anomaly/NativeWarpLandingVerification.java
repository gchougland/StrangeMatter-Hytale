package com.hexvane.strangematter.anomaly;

import com.hexvane.strangematter.util.WorldAccess;
import com.hexvane.strangematter.equipment.NativePlayerFixture;

import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.modules.collision.CollisionModule;
import com.hypixel.hytale.server.core.modules.collision.CollisionResult;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.universe.world.World;
import org.joml.Vector3d;
import org.joml.Vector3i;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/** Native collision/support checks and controlled asynchronous destination lifecycle regressions. */
public final class NativeWarpLandingVerification {
    private record Cell(int block,int rotation,int filler){}
    public static void verify(World world)throws Exception{
        verifySupport(world);
        verifyLoads(world);
        verifyResidency(world);
        verifyStaleCreation(world);
        verifyApproach(world);
        verifyObsoletePairedLoads(world);
        System.out.println("NATIVE_WARP_LANDING_VERIFICATION_PASSED: native body clearance and footing, nine-column safety region, measured peak two in-flight loads and one start per 50ms, shared requests, fail-fast/backoff, timeout without native future cancellation, native residency reference counts, lease expiry/cancel/stale/stop cleanup and bounded retained destinations.");
    }
    private static void verifyResidency(World world)throws Exception{
        var clock=new AtomicLong();var loads=new WarpLandingLoads((w,index)->{throw new AssertionError("Resident center must not issue generation");},clock::get);
        var center=new Vector3d(16.5,241,16.5);var chunk=WorldAccess.loaded(world,ChunkUtil.indexChunk(0,0));
        var field=chunk.getClass().getDeclaredField("keepLoaded");field.setAccessible(true);
        var pins=(java.util.concurrent.atomic.AtomicInteger)field.get(chunk);int baseline=pins.get();
        var first=UUID.randomUUID();var second=UUID.randomUUID();var results=new ArrayList<Boolean>();
        try{
            require(loads.requestCenter(world,first,center,results::add),"Resident native center starts a completion handoff");world.consumeTaskQueue();
            require(results.equals(List.of(true))&&pins.get()==baseline+1,"Successful preparation retains one real native residency reference until arrival");
            for(int n=0;n<5;n++){require(loads.requestCenter(world,first,center,results::add),"Same source can refresh its prepared destination");world.consumeTaskQueue();}
            require(pins.get()==baseline+1,"Repeated destination preparation never leaks extra native references");
            require(loads.requestCenter(world,second,center,results::add),"A second gate independently retains its shared native destination");world.consumeTaskQueue();
            require(pins.get()==baseline+2,"Overlapping gate leases each own exactly one reference");
            loads.cancel(world,first);require(pins.get()==baseline+1,"Cancellation releases only its own gate's native reference");
            clock.set(WarpLandingLoads.RETAIN_NANOS-1);loads.tick(world);require(pins.get()==baseline+1,"Destination stays retained until the exact arrival lease boundary");
            clock.incrementAndGet();loads.tick(world);require(pins.get()==baseline,"Idle residency expires without being prolonged by routine pumping");
            boolean[] current={true};require(loads.requestCenter(world,first,center,()->current[0],results::add),"Current paired identity may retain terrain");world.consumeTaskQueue();
            current[0]=false;loads.tick(world);require(pins.get()==baseline,"Stale paired identity releases native residency immediately");
            var owners=new ArrayList<UUID>();
            for(int n=0;n<WarpLandingLoads.MAX_REQUESTS;n++){var id=UUID.randomUUID();owners.add(id);loads.retainReady(world,id,center,()->true,false);}
            require(pins.get()==baseline+WarpLandingLoads.MAX_REQUESTS,"Idle approaching destinations obey the same bound as pending preparations");
            var overflow=UUID.randomUUID();loads.retainReady(world,overflow,center,()->true,false);
            require(pins.get()==baseline+WarpLandingLoads.MAX_REQUESTS,"A ninth approaching destination cannot silently grow the residency set");
            // Native reference ownership proves admission independently of how many
            // neighboring columns earlier native player fixtures left in memory.
            loads.retainReady(world,overflow,center,()->true,true);
            require(pins.get()==baseline+WarpLandingLoads.MAX_REQUESTS,"Actual portal contact replaces idle residency without growing its bound");
            loads.cancel(world,owners.getFirst());require(pins.get()==baseline+WarpLandingLoads.MAX_REQUESTS,"The oldest idle approach was evicted for real travel");
            loads.cancel(world,overflow);require(pins.get()==baseline+WarpLandingLoads.MAX_REQUESTS-1,"The urgent contacting gate owns its admitted reference");
            loads.retainReady(world,owners.getFirst(),center,()->true,false);
            for(var owner:owners)loads.arriving(world,owner);
            loads.retainReady(world,overflow,center,()->true,true);
            require(pins.get()==baseline+WarpLandingLoads.MAX_REQUESTS,"Recent client arrivals cannot be evicted by new portal traffic");
            loads.cancel(world,overflow);require(pins.get()==baseline+WarpLandingLoads.MAX_REQUESTS,"An arrival-protected area never admitted the overflowing gate");
            clock.addAndGet(4_000_000_000L);
            loads.retainReady(world,overflow,center,()->true,true);
            loads.cancel(world,overflow);require(pins.get()==baseline+WarpLandingLoads.MAX_REQUESTS-1,"Arrival protection ends at its boundary and permits urgent replacement");
            // An unrelated native owner must survive our world cleanup.
            chunk.addKeepLoaded();loads.clear(world);require(pins.get()==baseline+1,"World cleanup releases every gate reference while preserving an unrelated native owner");chunk.removeKeepLoaded();
            require(pins.get()==baseline,"All native residency reference counts return exactly to their original values");
        }finally{loads.clear(world);}
    }
    private static void verifySupport(World world){
        var saved=new LinkedHashMap<Vector3i,Cell>();
        try{
            for(int x=30;x<=33;x++)for(int z=11;z<=13;z++){
                var chunk=WorldAccess.loaded(world,ChunkUtil.indexChunkFromBlock(x,z));require(chunk!=null,"Both native chunk-boundary fixture columns are loaded");
                for(int y=239;y<ChunkUtil.HEIGHT;y++){
                    var section=WorldAccess.section(chunk,y);
                    saved.put(new Vector3i(x,y,z),new Cell(section.get(x,y,z),section.getRotationIndex(x,y,z),section.getFiller(x,y,z)));
                    section.set(x,y,z,y==240?BlockType.getAssetMap().getIndex("Rock_Stone"):0,0,0);
                }
                WorldAccess.column(chunk).updateHeight(x,z);
            }
            var effects=new HytaleAnomalyEffects();var body=new BoundingBox(new Box(-.6,0,-.35,.6,1.8,.35));
            var position=HytaleAnomalyEffects.safeSurface(world,31,12);
            require(position!=null&&position.y==241&&effects.fitsAt(world,body,position),"Native full body safely straddles the loaded x31/x32 chunk boundary with real ground contact");
            var chunk=WorldAccess.loaded(world,ChunkUtil.indexChunk(0,0));
            int bread=BlockType.getAssetMap().getIndex("Food_Bread");require(bread>=0,"Native noncolliding semitransparent bread asset exists");
            WorldAccess.section(chunk,250).set(31,250,12,bread,0,0);WorldAccess.column(chunk).updateHeight(31,12);
            position=HytaleAnomalyEffects.safeSurface(world,31,12);
            require(position!=null&&position.y==251,"Native opacity heightmap actually selects unsupported bread above the solid floor");
            int result=CollisionModule.get().validatePosition(world,body.getBoundingBox(),position,new CollisionResult());
            require(result==CollisionModule.VALIDATE_OK,"Native collision accepts empty airborne space but does not report footing: "+result);
            require(!effects.fitsAt(world,body,position),"Gate rejects the unsupported destination that the old nonoverlap-only predicate accepted");
            var remote=new Vector3d(1_048_576.5,241,1_048_576.5);long index=ChunkUtil.indexChunkFromBlock((int)remote.x,(int)remote.z);
            require(WorldAccess.loaded(world,index)==null,"Remote destination starts unloaded");
            require(!effects.fitsAt(world,body,remote)&&HytaleAnomalyEffects.safeSurface(world,(int)remote.x,(int)remote.z)==null
                    &&WorldAccess.loaded(world,index)==null,"Both safety checks reject unloaded terrain without synchronously loading it");
        }finally{
            var columns=new HashSet<Vector3i>();
            for(var entry:saved.entrySet()){
                var p=entry.getKey();var value=entry.getValue();var chunk=WorldAccess.loaded(world,ChunkUtil.indexChunkFromBlock(p.x,p.z));
                WorldAccess.section(chunk,p.y).set(p.x,p.y,p.z,value.block,value.rotation,value.filler);columns.add(new Vector3i(p.x,0,p.z));
            }
            for(var p:columns)WorldAccess.column(WorldAccess.loaded(world,ChunkUtil.indexChunkFromBlock(p.x,p.z))).updateHeight(p.x,p.z);
        }
    }
    private static void verifyLoads(World world){
        var clock=new AtomicLong();var requested=new ArrayList<Long>();var pending=new ArrayList<CompletableFuture<?>>();
        var outcomes=new ArrayList<Boolean>();var loads=new WarpLandingLoads((w,index)->{requested.add(index);var future=new CompletableFuture<Void>();pending.add(future);return future;},clock::get);
        var boundary=WarpLandingLoads.area(new Vector3d(31.5,241,12.5));
        require(boundary.size()==9&&boundary.contains(ChunkUtil.indexChunk(-1,-1))&&boundary.contains(ChunkUtil.indexChunk(1,1)),"Boundary landing preloads the entire bounded candidate/body neighborhood");
        // Prior real player fixtures may leave every nearby column resident but parked.
        // Those are correctly reactivated without generation; pacing needs absent data.
        var center=new Vector3d(1_048_576.5,241,1_048_576.5);var source=UUID.randomUUID();var area=WarpLandingLoads.area(center);
        long missing=area.stream().filter(index->WorldAccess.inMemory(world,index)==null).count();require(missing==9,"Paced-load fixture uses nine genuinely unpublished native columns");
        require(loads.request(world,source,center,outcomes::add)&&requested.size()==1&&outcomes.isEmpty(),"Preparation starts only one missing column without blocking the world thread");
        require(!loads.request(world,source,center,outcomes::add)&&requested.size()==1,"Repeated gate ticks never duplicate an in-flight batch");
        clock.addAndGet(WarpLandingLoads.START_INTERVAL_NANOS);loads.tick(world);require(requested.size()==2,"Second native load starts after its pacing interval");
        clock.addAndGet(WarpLandingLoads.START_INTERVAL_NANOS);loads.tick(world);require(requested.size()==2,"Unresolved native loads consume the global two-load budget");
        boolean[] sentinel={false};world.execute(()->sentinel[0]=true);world.consumeTaskQueue();require(sentinel[0]&&outcomes.isEmpty(),"Owning world tasks continue while all destination futures remain pending");
        int peak=0;
        for(int i=0;i<20&&outcomes.isEmpty();i++){
            peak=Math.max(peak,(int)pending.stream().filter(future->!future.isDone()).count());
            int before=requested.size();complete(pending,false);world.consumeTaskQueue();require(requested.size()==before,"Completion cannot recursively launch more chunk generation");
            clock.addAndGet(WarpLandingLoads.START_INTERVAL_NANOS);loads.tick(world);require(requested.size()-before<=1,"Each paced pump issues at most one load");
        }
        require(peak==2&&requested.size()==missing&&outcomes.equals(List.of(true)),"Complete safety area resumes exactly once with measured peak two native loads");
        require(!loads.ready(world,center),"Completion callbacks cannot pretend fake/unloaded chunks are available");
        pending.clear();clock.addAndGet(WarpLandingLoads.START_INTERVAL_NANOS);require(loads.request(world,source,center,outcomes::add),"A later attempt may prepare a still-unloaded area");
        complete(pending,true);world.consumeTaskQueue();require(outcomes.equals(List.of(true,false)),"A failed chunk rejects the entire landing preparation");
        int count=requested.size();long failedAt=clock.get();clock.set(failedAt+4_999_999_999L);
        require(!loads.request(world,source,center,outcomes::add)&&requested.size()==count,"Failed loads have a full five-second retry backoff");
        clock.set(failedAt+5_000_000_001L);pending.clear();require(loads.request(world,source,center,outcomes::add),"Failure backoff eventually permits a bounded retry");
        loads.clear(world);complete(pending,false);world.consumeTaskQueue();require(outcomes.size()==2,"World cleanup cancels stale completions without reviving work");
        pending.clear();require(loads.request(world,source,center,outcomes::add),"A fresh world request can start after cleanup");
        pending.getFirst().complete(null);complete(pending,false);world.consumeTaskQueue();
        require(outcomes.equals(List.of(true,false,false))&&!loads.request(world,source,center,outcomes::add),"Null chunk completion is a real failure with backoff, never false readiness");
        loads.clear(world);
        verifySharedTimeout(world,center);
    }
    private static void verifySharedTimeout(World world,Vector3d center){
        var clock=new AtomicLong();var pending=new ArrayList<CompletableFuture<Object>>();var indexes=new ArrayList<Long>();var results=new ArrayList<Boolean>();
        var loads=new WarpLandingLoads((w,index)->{indexes.add(index);var future=new CompletableFuture<Object>();pending.add(future);return future;},clock::get);
        var first=UUID.randomUUID();var second=UUID.randomUUID();
        require(loads.request(world,first,center,results::add)&&loads.request(world,second,center,results::add)&&pending.size()==1,"Two gates share one pending column instead of duplicating native requests");
        clock.addAndGet(WarpLandingLoads.START_INTERVAL_NANOS);loads.tick(world);
        require(pending.size()==2&&new HashSet<>(indexes).size()==2,"Shared requests still obey the per-world two-load limit");
        clock.set(WarpLandingLoads.TIMEOUT_NANOS);loads.tick(world);world.consumeTaskQueue();
        require(results.equals(List.of(false,false))&&pending.stream().noneMatch(CompletableFuture::isCancelled),"Timeout notifies both gates and leaves shared native futures untouched");
        clock.addAndGet(5_000_000_001L);require(loads.request(world,first,center,results::add),"A timed-out request can queue a retry after backoff");
        require(pending.size()==2,"Timed-out native work retains its slots, so retries cannot accumulate generation");
        loads.cancel(world,first);complete(pending,false);world.consumeTaskQueue();clock.addAndGet(WarpLandingLoads.START_INTERVAL_NANOS);loads.tick(world);
        require(results.size()==2&&pending.size()==2,"Cancelled retry neither revives callbacks nor starts its unissued columns");
        loads.clear(world);
        for(int i=0;i<WarpLandingLoads.MAX_REQUESTS;i++)require(loads.request(world,UUID.randomUUID(),center,ignored->{}),"Bounded queue accepts its documented capacity");
        require(!loads.request(world,UUID.randomUUID(),center,ignored->{}),"More simultaneous gate requests cannot grow the queue without bound");
        loads.clear(world);complete(pending,false);world.consumeTaskQueue();
    }
    private static void verifyStaleCreation(World world)throws Exception{
        var method=AnomalyService.class.getDeclaredMethod("createDistantPair",World.class,AnomalyRecord.class,int.class);method.setAccessible(true);
        var field=AnomalyService.class.getDeclaredField("gateLoads");field.setAccessible(true);
        for(String change:List.of("removed","disabled","relocated","repaired")){
            var service=new AnomalyService(Files.createTempDirectory("sm-warp-stale-"));service.naturalGeneration=false;
            var pending=new ArrayList<CompletableFuture<?>>();var loads=new WarpLandingLoads((w,index)->{var future=new CompletableFuture<Void>();pending.add(future);return future;},System::nanoTime);field.set(service,loads);
            try{
                var source=service.spawn(AnomalyType.WARP_GATE,world,new Vector3d(16.5,241,16.5),true);source.creatingPair=true;
                method.invoke(service,world,source,0);require(pending.size()==1&&source.creatingPair,"Natural destination checks only its center before generating any landing neighbors: "+change);
                AnomalyRecord replacement=null;
                switch(change){
                    case "removed"->service.remove(source.id);
                    case "disabled"->service.setEnabled(source.id,false);
                    case "relocated"->source.move(new Vector3d(18.5,241,16.5));
                    case "repaired"->{replacement=service.spawn(AnomalyType.WARP_GATE,world,new Vector3d(20.5,241,16.5),false);service.pair(source.id,replacement.id);}
                }
                int count=service.all().size();complete(pending,false);world.consumeTaskQueue();
                require(service.all().size()==count&&!source.creatingPair&&pending.size()==1,"Stale "+change+" callback creates no partner and starts no additional generation");
                if(replacement!=null)require(replacement.id.equals(source.pairedGate)&&source.id.equals(replacement.pairedGate),"New explicit pairing survives the old natural request completion");
            }finally{service.stopWorld(world);}
        }
    }
    private static void verifyApproach(World world)throws Exception{
        var service=new AnomalyService(Files.createTempDirectory("sm-warp-approach-"));service.naturalGeneration=false;
        var pending=new ArrayList<CompletableFuture<Object>>();
        var loads=new WarpLandingLoads((w,index)->{var future=new CompletableFuture<Object>();pending.add(future);return future;},System::nanoTime);
        var field=AnomalyService.class.getDeclaredField("gateLoads");field.setAccessible(true);field.set(service,loads);
        var gateTick=AnomalyService.class.getDeclaredMethod("tickGate",World.class,AnomalyRecord.class);gateTick.setAccessible(true);
        try(var player=NativePlayerFixture.create(world,"NativeGateApproach",new Vector3d(46.5,241,16.5))){
            player.player().handleClientReady(false);player.packets().packets.clear();
            var source=service.spawn(AnomalyType.WARP_GATE,world,new Vector3d(16.5,241,16.5),false);
            var target=service.spawn(AnomalyType.WARP_GATE,world,new Vector3d(8192.5,241,8192.5),false);service.pair(source.id,target.id);
            gateTick.invoke(service,world,source);require(pending.isEmpty(),"A player thirty blocks away does not start destination generation");
            var transform=player.store().getComponent(player.ref(),com.hypixel.hytale.server.core.modules.entity.component.TransformComponent.getComponentType());
            transform.setPosition(new Vector3d(36.5,241,16.5));gateTick.invoke(service,world,source);
            require(pending.size()==1&&source.creatingPair,"An approaching native player starts a bounded paired-destination load before contact");
            require(player.packets().ofType(com.hypixel.hytale.protocol.packets.player.ClientTeleport.class).isEmpty()&&transform.getPosition().equals(new Vector3d(36.5,241,16.5)),"Approach preparation never moves or queues a teleport for the player");
        }finally{service.stopWorld(world);complete(pending,false);world.consumeTaskQueue();}
    }
    private static void verifyObsoletePairedLoads(World world)throws Exception{
        var field=AnomalyService.class.getDeclaredField("gateLoads");field.setAccessible(true);
        var gateTick=AnomalyService.class.getDeclaredMethod("tickGate",World.class,AnomalyRecord.class);gateTick.setAccessible(true);
        for(String change:List.of("target_disabled","target_suppressed","target_moved","source_moved","repaired")){
            var service=new AnomalyService(Files.createTempDirectory("sm-warp-obsolete-"));service.naturalGeneration=false;
            var clock=new AtomicLong();var pending=new ArrayList<CompletableFuture<Object>>();var requested=new ArrayList<Long>();
            var loads=new WarpLandingLoads((w,index)->{requested.add(index);var future=new CompletableFuture<Object>();pending.add(future);return future;},clock::get);field.set(service,loads);
            try(var player=NativePlayerFixture.create(world,"NativeGateRouteChange",new Vector3d(36.5,241,16.5))){
                player.player().handleClientReady(false);
                var source=service.spawn(AnomalyType.WARP_GATE,world,new Vector3d(16.5,241,16.5),false);
                var target=service.spawn(AnomalyType.WARP_GATE,world,new Vector3d(8192.5,241,8192.5),false);
                var replacement=service.spawn(AnomalyType.WARP_GATE,world,new Vector3d(12288.5,241,12288.5),false);
                service.pair(source.id,target.id);gateTick.invoke(service,world,source);
                require(pending.size()==1&&source.creatingPair,"Paired cancellation fixture starts one queued route: "+change);
                var oldArea=WarpLandingLoads.area(target.position());
                switch(change){
                    case "target_disabled"->service.setEnabled(target.id,false);
                    case "target_suppressed"->service.setSuppressionHook((w,p,t)->p.equals(target.position()));
                    case "target_moved"->target.move(new Vector3d(9000.5,241,9000.5));
                    case "source_moved"->source.move(new Vector3d(17.5,241,16.5));
                    case "repaired"->service.pair(source.id,replacement.id);
                }
                clock.addAndGet(WarpLandingLoads.START_INTERVAL_NANOS);loads.tick(world);world.consumeTaskQueue();
                require(pending.size()==1&&!source.creatingPair&&!pending.getFirst().isCancelled(),"The load pump invalidates obsolete identity before starting another old column, preserving the issued native future: "+change);
                service.pair(source.id,replacement.id);gateTick.invoke(service,world,source);
                require(pending.size()==2&&source.creatingPair&&WarpLandingLoads.area(replacement.position()).contains(requested.getLast()),"A newly paired destination starts immediately in the remaining slot without the obsolete route's timeout/backoff: "+change);
                pending.getFirst().complete(Boolean.TRUE);world.consumeTaskQueue();
                require(source.creatingPair&&replacement.id.equals(source.pairedGate),"Late completion from the obsolete route cannot clear the new route's preparation or pairing: "+change);
                clock.addAndGet(WarpLandingLoads.START_INTERVAL_NANOS);loads.tick(world);
                require(requested.size()==3&&requested.subList(1,requested.size()).stream().noneMatch(oldArea::contains),"Only the replacement destination can use slots freed by obsolete native work: "+change);
            }finally{service.stopWorld(world);complete(pending,false);world.consumeTaskQueue();}
        }
    }
    @SuppressWarnings("unchecked")
    private static void complete(List<? extends CompletableFuture<?>> futures,boolean fail){
        for(int i=0;i<futures.size();i++){
            var future=(CompletableFuture<Object>)futures.get(i);
            if(fail&&i==0)future.completeExceptionally(new IllegalStateException("Expected test generation failure"));else future.complete(Boolean.TRUE);
        }
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
