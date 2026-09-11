package com.hexvane.strangematter.anomaly;

import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.events.ChunkPreLoadProcessEvent;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.WorldConfig;
import com.hypixel.hytale.server.core.universe.world.worldgen.provider.FlatWorldGenProvider;
import com.hypixel.hytale.math.util.ChunkUtil;
import org.joml.Vector3d;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Actual unloaded distant terrain, engine pre-load events, and world-queue responsiveness. */
public final class NativeNaturalGateVerification {
    private volatile AnomalyService service;
    private final Set<Long> generated=ConcurrentHashMap.newKeySet();

    public void onPreLoad(ChunkPreLoadProcessEvent event) {
        var current=service;if(current==null)return;
        current.onChunkPreLoad(event);
        if(event.isNewlyGenerated())generated.add(ChunkUtil.indexChunk(event.getChunk().getX(),event.getChunk().getZ()));
    }
    public CompletableFuture<Void> verifyAsync(Path directory) {
        // The other fixtures deliberately use the stock one-layer flat world at y=0.
        // Give this independent world actual safe terrain above the gate's build-floor exclusion.
        var config=new WorldConfig();
        config.setWorldGenProvider(new FlatWorldGenProvider(FlatWorldGenProvider.DEFAULT_TINT,new FlatWorldGenProvider.Layer[]{
            new FlatWorldGenProvider.Layer(0,8,null,"Rock_Stone"),new FlatWorldGenProvider.Layer(8,9,null,"Soil_Grass")
        }));
        config.setSpawningNPC(false);config.setIsSpawnMarkersEnabled(false);config.setBlockTicking(false);config.setCanUnloadChunks(false);
        String name="sm_gate_verification_"+UUID.randomUUID().toString().replace("-","");
        return Universe.get().makeWorld(name,Universe.get().validateWorldPath(name),config)
            .thenCompose(world->world.getChunkAsync(ChunkUtil.indexChunk(0,0)).thenComposeAsync(ignored->verifyOnWorld(world,directory),world));
    }
    private CompletableFuture<Void> verifyOnWorld(World world,Path directory) {
        var result=new CompletableFuture<Void>();
        try {
            world.getEntityStore().getStore().assertThread();
            service=new AnomalyService(directory.resolve("natural-gate-regression"));
            service.generationSettings.terrainPatches=false;service.rarity=Integer.MAX_VALUE;
            var source=service.spawnRaised(AnomalyType.WARP_GATE,world,new Vector3d(20.5,40,20.5),true);
            source.creatingPair=true;
            var create=AnomalyService.class.getDeclaredMethod("createDistantPair",World.class,AnomalyRecord.class,int.class);
            create.setAccessible(true);
            synchronized(service){create.invoke(service,world,source,0);}
            require(source.pairedGate==null&&source.creatingPair,"Distant generation is deferred, without blocking the initiating world task");
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);
            var probes=new AtomicInteger();
            world.execute(()->poll(world,source,result,probes,deadline));
        } catch(Throwable failure){result.completeExceptionally(failure);}
        return result.whenComplete((ignored,error)->{
            if(world.isInThread()&&service!=null){service.stopWorld(world);service=null;}
        });
    }
    private void poll(World world,AnomalyRecord source,CompletableFuture<Void> result,AtomicInteger probes,long deadline) {
        if(result.isDone())return;
        try {
            probes.incrementAndGet();
            var target=source.pairedGate==null?null:service.get(source.pairedGate).orElse(null);
            if(target!=null) {
                require(target.active()&&source.id.equals(target.pairedGate)&&!source.creatingPair,"Actual far destination becomes a symmetric pair");
                require(source.position().distance(target.position())>900,"Natural gate creates genuinely distant terrain");
                require(new WarpLandingLoads().ready(world,target.position()),"All destination landing chunks are loaded before pairing finishes");
                require(generated.containsAll(WarpLandingLoads.area(target.position())),"Real engine generation dispatches each exact destination chunk's pre-load hook");
                require(probes.get()>0,"Owning world executes queued probes during asynchronous destination preparation");
                System.out.println("NATIVE_NATURAL_GATE_VERIFICATION_PASSED: real distant generation, "+generated.size()+" distinct pre-load chunks, "+probes.get()+" responsive world probes, complete nine-chunk landing region and symmetric persistent pair.");
                result.complete(null);return;
            }
            require(System.nanoTime()<deadline,"Natural gate generation completes while the owning world keeps responding");
            require(source.creatingPair,"Destination preparation has not failed or exhausted its attempts");
            CompletableFuture.delayedExecutor(10,TimeUnit.MILLISECONDS).execute(()->{
                try {world.execute(()->poll(world,source,result,probes,deadline));}
                catch(RuntimeException stopped){result.completeExceptionally(stopped);}
            });
        }catch(Throwable failure){result.completeExceptionally(failure);}
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
