package com.hexvane.strangematter.anomaly;

import com.hexvane.strangematter.worldgen.GenerationColumn;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import java.nio.file.Files;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Pauses a real fresh-holder painter while exercising service reads and duplicate generation. */
public final class NativeAnomalyGenerationConcurrencyVerification {
    public static void verify(World world,GenerationColumn terrain,BlockChunk column)throws Exception {
        var service=new AnomalyService(Files.createTempDirectory("sm-generation-concurrency-"));service.rarity=1;
        for(int dx=0;dx<32;dx++)for(int dz=0;dz<32;dz++){
            int x=terrain.chunk.getX()*32+dx,z=terrain.chunk.getZ()*32+dz;
            terrain.set(x,70,z,"Soil_Sand");terrain.set(x,69,z,"Rock_Sandstone");terrain.set(x,68,z,"Rock_Sandstone");column.setHeight(x,z,(short)70);
        }
        var painting=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
        try(var executor=Executors.newFixedThreadPool(2)){
            var first=executor.submit(()->service.generate(terrain,(field,random)->{
                require(!Thread.holdsLock(service),"Fresh terrain painting must not hold the live anomaly service monitor");
                calls.incrementAndGet();painting.countDown();
                try{if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("Fixture painter release timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}
                AnomalyTerrainPatches.generate(terrain,field,random,service.generationSettings);
            }));
            try {
                require(painting.await(3,TimeUnit.SECONDS),"Actual asynchronous generation reaches the paused holder painter");
                require(executor.submit(()->service.all().size()).get(2,TimeUnit.SECONDS)==1,"Live registry read completes while terrain painting is paused");
                executor.submit(()->service.generate(terrain,(field,random)->{throw new AssertionError("Duplicate or neighboring worker must honor the reserved minimum spacing");})).get(2,TimeUnit.SECONDS);
                require(service.all().size()==1,"Concurrent cell claims and neighboring reservations preserve the48-block exclusion");
            }finally{release.countDown();}
            first.get(5,TimeUnit.SECONDS);
            service.generate(terrain,(field,random)->{throw new AssertionError("A surveyed cell cannot paint twice");});
            require(calls.get()==1&&service.all().size()==1,"The field identity and actual terrain patch are each produced once");
        }
        require(world.getChunkStore().getChunkReference(terrain.chunk.getIndex())==null,"Concurrent generation never publishes or loads the raw fixture column");
        System.out.println("NATIVE_ANOMALY_GENERATION_CONCURRENCY_VERIFICATION_PASSED: paused native fresh-holder painting permits live registry reads; concurrent duplicate cell claims,48-block spacing and one-time painting remain safe; no chunk load/publication.");
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
