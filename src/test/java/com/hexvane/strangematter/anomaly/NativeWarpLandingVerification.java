package com.hexvane.strangematter.anomaly;

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
        verifyStaleCreation(world);
        System.out.println("NATIVE_WARP_LANDING_VERIFICATION_PASSED: real two-chunk body clearance and solid support, opacity-only native bread rejected, unloaded destination stays unloaded during validation, nine-chunk bounded asynchronous area, one in-flight request, failure backoff, world cancellation, stale removed/disabled/relocated/repaired source callbacks.");
    }
    private static void verifySupport(World world){
        var saved=new LinkedHashMap<Vector3i,Cell>();
        try{
            for(int x=30;x<=33;x++)for(int z=11;z<=13;z++){
                var chunk=world.getChunkIfLoaded(ChunkUtil.indexChunkFromBlock(x,z));require(chunk!=null,"Both native chunk-boundary fixture columns are loaded");
                for(int y=239;y<ChunkUtil.HEIGHT;y++){
                    var section=chunk.getBlockChunk().getSectionAtBlockY(y);
                    saved.put(new Vector3i(x,y,z),new Cell(section.get(x,y,z),section.getRotationIndex(x,y,z),section.getFiller(x,y,z)));
                    section.set(x,y,z,y==240?BlockType.getAssetMap().getIndex("Rock_Stone"):0,0,0);
                }
                chunk.getBlockChunk().updateHeight(x,z);
            }
            var effects=new HytaleAnomalyEffects();var body=new BoundingBox(new Box(-.6,0,-.35,.6,1.8,.35));
            var position=HytaleAnomalyEffects.safeSurface(world,31,12);
            require(position!=null&&position.y==241&&effects.fitsAt(world,body,position),"Native full body safely straddles the loaded x31/x32 chunk boundary with real ground contact");
            var chunk=world.getChunkIfLoaded(ChunkUtil.indexChunk(0,0));
            int bread=BlockType.getAssetMap().getIndex("Food_Bread");require(bread>=0,"Native noncolliding semitransparent bread asset exists");
            chunk.getBlockChunk().getSectionAtBlockY(250).set(31,250,12,bread,0,0);chunk.getBlockChunk().updateHeight(31,12);
            position=HytaleAnomalyEffects.safeSurface(world,31,12);
            require(position!=null&&position.y==251,"Native opacity heightmap actually selects unsupported bread above the solid floor");
            int result=CollisionModule.get().validatePosition(world,body.getBoundingBox(),position,new CollisionResult());
            require(result==CollisionModule.VALIDATE_OK,"Native collision accepts empty airborne space but does not report footing: "+result);
            require(!effects.fitsAt(world,body,position),"Gate rejects the unsupported destination that the old nonoverlap-only predicate accepted");
            var remote=new Vector3d(1_048_576.5,241,1_048_576.5);long index=ChunkUtil.indexChunkFromBlock((int)remote.x,(int)remote.z);
            require(world.getChunkIfLoaded(index)==null,"Remote destination starts unloaded");
            require(!effects.fitsAt(world,body,remote)&&HytaleAnomalyEffects.safeSurface(world,(int)remote.x,(int)remote.z)==null
                    &&world.getChunkIfLoaded(index)==null,"Both safety checks reject unloaded terrain without synchronously loading it");
        }finally{
            var columns=new HashSet<Vector3i>();
            for(var entry:saved.entrySet()){
                var p=entry.getKey();var value=entry.getValue();var chunk=world.getChunkIfLoaded(ChunkUtil.indexChunkFromBlock(p.x,p.z));
                chunk.getBlockChunk().getSectionAtBlockY(p.y).set(p.x,p.y,p.z,value.block,value.rotation,value.filler);columns.add(new Vector3i(p.x,0,p.z));
            }
            for(var p:columns)world.getChunkIfLoaded(ChunkUtil.indexChunkFromBlock(p.x,p.z)).getBlockChunk().updateHeight(p.x,p.z);
        }
    }
    private static void verifyLoads(World world){
        var clock=new AtomicLong();var requested=new ArrayList<Long>();var pending=new ArrayList<CompletableFuture<?>>();
        var outcomes=new ArrayList<Boolean>();var loads=new WarpLandingLoads((w,index)->{requested.add(index);var future=new CompletableFuture<Void>();pending.add(future);return future;},clock::get);
        var center=new Vector3d(31.5,241,12.5);var source=UUID.randomUUID();var area=WarpLandingLoads.area(center);
        require(area.size()==9&&area.contains(ChunkUtil.indexChunk(-1,-1))&&area.contains(ChunkUtil.indexChunk(1,1)),"Boundary landing preloads the entire bounded candidate/body neighborhood");
        long missing=area.stream().filter(index->world.getChunkIfLoaded(index)==null).count();require(missing>0,"Boundary fixture has unloaded neighboring chunks");
        require(loads.request(world,source,center,outcomes::add)&&requested.size()==missing&&outcomes.isEmpty(),"Only missing chunks are requested and no completion blocks the world thread");
        require(!loads.request(world,source,center,outcomes::add)&&requested.size()==missing,"Repeated gate ticks never duplicate an in-flight batch");
        boolean[] sentinel={false};world.execute(()->sentinel[0]=true);world.consumeTaskQueue();require(sentinel[0]&&outcomes.isEmpty(),"Owning world tasks continue while all destination futures remain pending");
        complete(pending,false);world.consumeTaskQueue();require(outcomes.equals(List.of(true)),"Completed batch resumes once on the world thread");
        require(!loads.ready(world,center),"Completion callbacks cannot pretend fake/unloaded chunks are available");
        pending.clear();require(loads.request(world,source,center,outcomes::add),"A later attempt may prepare a still-unloaded area");
        complete(pending,true);world.consumeTaskQueue();require(outcomes.equals(List.of(true,false)),"A failed chunk rejects the entire landing preparation");
        int count=requested.size();clock.set(4_999_999_999L);
        require(!loads.request(world,source,center,outcomes::add)&&requested.size()==count,"Failed loads have a full five-second retry backoff");
        clock.set(5_000_000_001L);pending.clear();require(loads.request(world,source,center,outcomes::add),"Failure backoff eventually permits a bounded retry");
        loads.clear(world);complete(pending,false);world.consumeTaskQueue();require(outcomes.size()==2,"World cleanup cancels stale completions without reviving work");
        pending.clear();require(loads.request(world,source,center,outcomes::add),"A fresh world request can start after cleanup");
        pending.getFirst().complete(null);complete(pending,false);world.consumeTaskQueue();
        require(outcomes.equals(List.of(true,false,false))&&!loads.request(world,source,center,outcomes::add),"Null chunk completion is a real failure with backoff, never false readiness");
        loads.clear(world);
    }
    private static void verifyStaleCreation(World world)throws Exception{
        var method=AnomalyService.class.getDeclaredMethod("createDistantPair",World.class,AnomalyRecord.class,int.class);method.setAccessible(true);
        var field=AnomalyService.class.getDeclaredField("gateLoads");field.setAccessible(true);
        for(String change:List.of("removed","disabled","relocated","repaired")){
            var service=new AnomalyService(Files.createTempDirectory("sm-warp-stale-"));service.naturalGeneration=false;
            var pending=new ArrayList<CompletableFuture<?>>();var loads=new WarpLandingLoads((w,index)->{var future=new CompletableFuture<Void>();pending.add(future);return future;},System::nanoTime);field.set(service,loads);
            try{
                var source=service.spawn(AnomalyType.WARP_GATE,world,new Vector3d(16.5,241,16.5),true);source.creatingPair=true;
                method.invoke(service,world,source,0);require(pending.size()==9&&source.creatingPair,"Natural destination requests nine unloaded chunks without waiting: "+change);
                AnomalyRecord replacement=null;
                switch(change){
                    case "removed"->service.remove(source.id);
                    case "disabled"->service.setEnabled(source.id,false);
                    case "relocated"->source.move(new Vector3d(18.5,241,16.5));
                    case "repaired"->{replacement=service.spawn(AnomalyType.WARP_GATE,world,new Vector3d(20.5,241,16.5),false);service.pair(source.id,replacement.id);}
                }
                int count=service.all().size();complete(pending,false);world.consumeTaskQueue();
                require(service.all().size()==count&&!source.creatingPair&&pending.size()==9,"Stale "+change+" callback creates no partner and starts no additional generation");
                if(replacement!=null)require(replacement.id.equals(source.pairedGate)&&source.id.equals(replacement.pairedGate),"New explicit pairing survives the old natural request completion");
            }finally{service.stopWorld(world);}
        }
    }
    @SuppressWarnings("unchecked")
    private static void complete(List<CompletableFuture<?>> futures,boolean fail){
        for(int i=0;i<futures.size();i++){
            var future=(CompletableFuture<Object>)futures.get(i);
            if(fail&&i==0)future.completeExceptionally(new IllegalStateException("Expected test generation failure"));else future.complete(Boolean.TRUE);
        }
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
