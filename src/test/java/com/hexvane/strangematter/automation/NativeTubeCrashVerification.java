package com.hexvane.strangematter.automation;

import com.google.gson.Gson;
import com.hexvane.strangematter.StrangeMatterInteraction;
import com.hexvane.strangematter.block.AnomalousGrassService;
import com.hexvane.strangematter.util.WorldAccess;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.asset.type.blocktick.config.RandomTickProcedure;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.WorldConfig;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockComponentSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.IChunkLoader;
import com.hypixel.hytale.server.core.universe.world.worldgen.provider.FlatWorldGenProvider;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.BsonString;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import static com.hexvane.strangematter.automation.TubeEndpoints.*;
import static java.nio.file.StandardOpenOption.*;

/** Dedicated test plugin. Its launcher force-stops only this process after a verified disk checkpoint. */
public final class NativeTubeCrashVerification extends JavaPlugin {
    private static final String WORLD="sm_tube_crash";
    private static final Position SOURCE=new Position(5,80,5),DESTINATION=new Position(37,80,5);
    private static final Gson GSON=new Gson();
    private final String stage=System.getProperty("sm.tube.stage","invalid"),cut=System.getProperty("sm.tube.cut","invalid");
    private final int round=Integer.getInteger("sm.tube.round",1);
    private final AtomicBoolean finished=new AtomicBoolean();
    private final TubeEndpoints endpoints=new TubeEndpoints(null);
    private final CompletableFuture<Void> withheldSave=new CompletableFuture<>();
    private TubeTransferLedger ledger;
    private World world;
    private boolean committing;
    private Endpoint source,destination;
    private TubeTransferLedger.Intent intent;
    public NativeTubeCrashVerification(JavaPluginInit init){super(init);}
    @Override protected void setup(){
        FactoryService.register(this);
        TubeService.register(getChunkStoreRegistry(),getEntityStoreRegistry());
        getCodecRegistry(Interaction.CODEC).register("SM_Use",StrangeMatterInteraction.class,StrangeMatterInteraction.CODEC);
        getCodecRegistry(RandomTickProcedure.CODEC).register("SM_Anomalous_Grass",AnomalousGrassService.class,AnomalousGrassService.CODEC);
    }
    @Override protected void start(){
        CompletableFuture.delayedExecutor(90,TimeUnit.SECONDS).execute(()->finish(new TimeoutException("Crash fixture exceeded 90 seconds")));
        Universe.get().getUniverseReady().thenCompose(unused->{
            if(stage.equals("prepare")){
                require(List.of("journal","mutated","source","destination","both","complete").contains(cut),"Known crash cut");
                var config=new WorldConfig();config.setWorldGenProvider(new FlatWorldGenProvider());config.setSpawningNPC(false);
                if(System.getProperty("sm.tube.storage","Hytale").equals("RocksDb"))config.setChunkStorageProvider(new com.hypixel.hytale.server.core.universe.world.storage.provider.RocksDbChunkStorageProvider());
                config.setIsSpawnMarkersEnabled(false);config.setBlockTicking(false);config.setCanUnloadChunks(false);
                return Universe.get().makeWorld(WORLD,Universe.get().validateWorldPath(WORLD),config);
            }
            require(stage.equals("recover"),"Explicit prepare or recover stage");
            var loaded=Universe.get().getWorld(WORLD);
            return loaded!=null?CompletableFuture.completedFuture(loaded):Universe.get().loadWorld(WORLD);
        }).thenCompose(w->{world=w;world.lockSaving();return CompletableFuture.allOf(
            WorldAccess.load(w,ChunkUtil.indexChunk(0,0)),WorldAccess.load(w,ChunkUtil.indexChunk(1,0)));
        }).thenComposeAsync(unused->stage.equals("prepare")?prepare():recover(),new java.util.concurrent.Executor(){
            @Override public void execute(Runnable command){world.execute(command);}
        }).whenComplete((unused,error)->{if(error!=null||stage.equals("recover"))finish(error);});
    }
    private Path state(){return Path.of("transfer-state");}
    private Path journal(){return state().resolve("tube-transfers");}
    private CompletableFuture<Void> prepare(){
        for(var p:List.of(SOURCE,DESTINATION)){
            var chunk=WorldAccess.loaded(world,ChunkUtil.indexChunkFromBlock(p.x(),p.z()));
            require(WorldAccess.set(chunk,p.x(),p.y()-1,p.z(),"Rock_Stone"),"Fixture floor placement");
            require(WorldAccess.set(chunk,p.x(),p.y(),p.z(),"SM_Resonite_Chest"),"Fixture chest placement");
        }
        source=Objects.requireNonNull(endpoints.resolve(world,SOURCE,"storage",true));
        destination=Objects.requireNonNull(endpoints.resolve(world,DESTINATION,"storage",true));
        require(source.ref()!=destination.ref(),"Distinct native endpoint holders in distinct columns");
        source.port().inventory().setItemStackForSlot((short)0,marked(10));source.dirty();destination.dirty();
        // Persist whole new columns once: their native first save also registers generated section geometry.
        return retrySave(()->saveColumn(0)).thenComposeAsync(v->retrySave(()->saveColumn(1)),world).thenComposeAsync(v->{
            ledger=new TubeTransferLedger(state(),endpoints,this::checkpoint);
            return until(()->ledger.ready(source)&ledger.ready(destination));
        },world).thenComposeAsync(v->{
            require(ledger.begin(source,destination,(short)0,1),"First native transfer starts");
            return until(()->{ledger.tick(world);return ledger.pendingCount()==0;});
        },world).thenComposeAsync(v->{
            require(quantity(source)==9&&quantity(destination)==1,"First transfer completes before second-transfer regression");
            // A user or producer changes these same endpoints after their original identity was saved.
            // The second intent must not rely on that obsolete cached inventory checkpoint.
            source.port().inventory().setItemStackForSlot((short)0,marked(10));destination.port().inventory().clear();source.dirty();destination.dirty();
            // Even though these quantities match the very first baseline, the first transfer's later save superseded it.
            return until(()->ledger.ready(source)&ledger.ready(destination));
        },world).thenComposeAsync(v->{
            require(ledger.begin(source,destination,(short)0,3),"Native transfer intent begins after real identity save acknowledgements");
            var p=ledger.pending().iterator().next();intent=p.intent;
            return until(()->{
                if(p.prepare==null)ledger.tick(world);
                return p.prepare!=null;
            }).thenComposeAsync(unused->p.prepare,world).thenRunAsync(()->writeForced(Path.of("expected-intent.json"),GSON.toJson(intent)),world);
        },world).thenComposeAsync(v->{
            if(cut.equals("journal"))return verifyDisk(false,false).thenRunAsync(this::markCut,world);
            committing=true;ledger.tick(world);
            require(TubeStacks.matches(source.port().inventory(),intent.afterSource())&&TubeStacks.matches(destination.port().inventory(),intent.afterDestination()),"Actual native filtered transfer performed before save cut");
            var p=ledger.pending().iterator().next();
            if(cut.equals("complete"))return until(()->{ledger.tick(world);return ledger.pendingCount()==0;})
                .thenComposeAsync(unused->retrySave(()->actualCheckpoint(source)),world)
                .thenComposeAsync(unused->retrySave(()->actualCheckpoint(destination)),world)
                .thenRunAsync(()->{
                    require(!source.receipts().contains(intent.id())&&!destination.receipts().contains(intent.id()),"Completion has pruned both in-memory receipts");
                    // Deliberate stale replay after the durable completion record and receipt pruning.
                    writeForced(journal().resolve(intent.id()+".json"),GSON.toJson(intent));
                },world).thenComposeAsync(unused->verifyDisk(true,true),world).thenRunAsync(this::markCut,world);
            boolean saveSource=cut.equals("source")||cut.equals("both"),saveDestination=cut.equals("destination")||cut.equals("both");
            return until(()->(!saveSource||successful(p.saves.get("source")))&&(!saveDestination||successful(p.saves.get("destination"))))
                .thenComposeAsync(unused->verifyDisk(saveSource,saveDestination),world).thenRunAsync(this::markCut,world);
        },world);
    }
    private CompletableFuture<Void> recover(){
        try{intent=GSON.fromJson(Files.readString(Path.of("expected-intent.json")),TubeTransferLedger.Intent.class);}
        catch(Exception error){return CompletableFuture.failedFuture(error);}
        source=Objects.requireNonNull(endpoints.resolve(world,intent.source()),"Saved source holder identity must load");
        destination=Objects.requireNonNull(endpoints.resolve(world,intent.destination()),"Saved destination holder identity must load");
        boolean sourceAfter=round>1||List.of("source","both","complete").contains(cut);
        boolean destinationAfter=round>1||List.of("destination","both","complete").contains(cut);
        require(TubeStacks.matches(source.port().inventory(),sourceAfter?intent.afterSource():intent.beforeSource()),"Source actual restart snapshot at cut "+cut+" round "+round+" is "+TubeStacks.snapshot(source.port().inventory()));
        require(TubeStacks.matches(destination.port().inventory(),destinationAfter?intent.afterDestination():intent.beforeDestination()),"Destination actual restart snapshot at cut "+cut+" round "+round+" is "+TubeStacks.snapshot(destination.port().inventory()));
        System.out.println("NATIVE_TUBE_CRASH_LOADED "+cut+" round="+round+" source="+quantity(source)+" destination="+quantity(destination));
        ledger=new TubeTransferLedger(state(),endpoints,this::actualCheckpoint);
        if(cut.equals("complete")||round>1)require(ledger.pendingCount()==0,"Durable completion rejects stale or already retired intent");
        else require(ledger.pendingCount()==1,"Unfinished intent survives actual process termination");
        return until(()->{ledger.tick(world);return ledger.pendingCount()==0;}).thenComposeAsync(v->{
            require(TubeStacks.matches(source.port().inventory(),intent.afterSource())&&TubeStacks.matches(destination.port().inventory(),intent.afterDestination()),"Recovery conserves all ten items with exact nested Int64 metadata");
            require(quantity(source)==7&&quantity(destination)==3,"Exactly one transfer after process restart");
            // Persist pruning too; the second restart must rely on the durable completion history.
            return retrySave(()->actualCheckpoint(source));
        },world).thenComposeAsync(v->retrySave(()->actualCheckpoint(destination)),world);
    }
    private CompletableFuture<Void> checkpoint(Endpoint endpoint){
        if(committing){
            boolean from=endpoint.position().equals(SOURCE);
            boolean allowed=cut.equals("both")||cut.equals("complete")||(from?cut.equals("source"):cut.equals("destination"));
            if(!allowed)return withheldSave;
        }
        return actualCheckpoint(endpoint);
    }
    private CompletableFuture<Void> actualCheckpoint(Endpoint endpoint){
        world.unlockSaving();try{return TubeCheckpoints.save(endpoint);}finally{world.lockSaving();}
    }
    private CompletableFuture<Void> saveColumn(int x){
        var chunk=WorldAccess.loaded(world,ChunkUtil.indexChunk(x,0));var store=world.getChunkStore().getStore();
        var queue=store.getResource(ChunkStore.SAVE_RESOURCE);
        if(chunk.isSaving()||!queue.tryReserveInFlight())return null;
        chunk.setSaving(true);boolean dispatched=false;
        try{
            var saved=world.getChunkStore().getSaver().saveChunkColumn(x,0,store,chunk.getReference(),world,queue::releaseInFlight);
            dispatched=true;queue.pushSavingFuture(saved);
            return saved.whenCompleteAsync((v,error)->chunk.setSaving(false),world);
        }finally{if(!dispatched){chunk.setSaving(false);queue.releaseInFlight();}}
    }
    private CompletableFuture<Void> verifyDisk(boolean sourceAfter,boolean destinationAfter){
        return diskSection(0).thenCombine(diskSection(1),(a,b)->{
            var from=a.getComponent(BlockComponentSection.getComponentType()).getBlockHolder(ChunkUtil.indexBlock(SOURCE.x(),SOURCE.y(),SOURCE.z()));
            var to=b.getComponent(BlockComponentSection.getComponentType()).getBlockHolder(ChunkUtil.indexBlock(DESTINATION.x(),DESTINATION.y(),DESTINATION.z()));
            require(from!=null&&to!=null,"Native disk rows hold both endpoints");
            require(TubeStacks.matches(from.getComponent(ItemContainerBlock.getComponentType()).getItemContainer(),sourceAfter?intent.afterSource():intent.beforeSource()),"Source disk stage exactly matches selected cut "+cut);
            require(TubeStacks.matches(to.getComponent(ItemContainerBlock.getComponentType()).getItemContainer(),destinationAfter?intent.afterDestination():intent.beforeDestination()),"Destination disk stage exactly matches selected cut "+cut);
            if(!cut.equals("complete")){
                require(from.getComponent(TubeEndpointReceipts.getComponentType()).contains(intent.id(),"storage")==sourceAfter,"Source receipt is co-saved with source inventory");
                require(to.getComponent(TubeEndpointReceipts.getComponentType()).contains(intent.id(),"storage")==destinationAfter,"Destination receipt is co-saved with destination inventory");
            }
            return null;
        });
    }
    private CompletableFuture<com.hypixel.hytale.component.Holder<ChunkStore>> diskSection(int x){
        var loader=world.getChunkStore().getLoader();
        if(loader instanceof IChunkLoader.Cubic cubic)return cubic.loadSectionHolder(x,2,0);
        return loader.loadHolder(x,0).thenApply(holder->{
            require(holder!=null,"Native column is present on disk");
            // Inspect the persisted format through the current holder codec instead of the deprecated ChunkColumn API.
            var data=ChunkStore.REGISTRY.serialize(holder).getDocument("Components").getDocument("ChunkColumn");
            return Objects.requireNonNull(ChunkStore.REGISTRY.deserialize(data.getArray("Sections").get(2).asDocument()),"Saved endpoint section");
        });
    }
    private CompletableFuture<Void> retrySave(Supplier<CompletableFuture<Void>> save){
        var next=save.get();return next!=null?next:delay().thenComposeAsync(v->retrySave(save),world);
    }
    private CompletableFuture<Void> until(BooleanSupplier done){
        if(done.getAsBoolean())return CompletableFuture.completedFuture(null);
        return delay().thenComposeAsync(v->until(done),world);
    }
    private static CompletableFuture<Void> delay(){return CompletableFuture.runAsync(()->{},CompletableFuture.delayedExecutor(20,TimeUnit.MILLISECONDS));}
    private static boolean successful(CompletableFuture<Void> future){if(future==null||!future.isDone())return false;future.join();return true;}
    private void markCut(){
        require(world.isSavingLocked(),"Automatic saves stay locked at the crash cut");
        var marker=Path.of("cut-marker.json");var temporary=Path.of("cut-marker.tmp");
        writeForced(temporary,GSON.toJson(java.util.Map.of("pid",ProcessHandle.current().pid(),"cut",cut,"stage",stage)));
        try{Files.move(temporary,marker,java.nio.file.StandardCopyOption.ATOMIC_MOVE);}
        catch(Exception error){throw new IllegalStateException(error);}
        System.out.println("NATIVE_TUBE_CRASH_READY "+cut+" pid="+ProcessHandle.current().pid());
    }
    private static int quantity(Endpoint endpoint){int total=0;var c=endpoint.port().inventory();for(short s=0;s<c.getCapacity();s++){var stack=c.getItemStack(s);if(!ItemStack.isEmpty(stack))total+=stack.getQuantity();}return total;}
    private static ItemStack marked(int count){return new ItemStack("SM_Resonite_Ingot",count,new BsonDocument("TubeCrash",new BsonDocument("rank",new BsonInt64(9223372036854775806L)).append("label",new BsonString("cyan and purple"))));}
    private static void writeForced(Path path,String content){
        try(var channel=FileChannel.open(path,CREATE,TRUNCATE_EXISTING,WRITE)){
            var bytes=ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8));while(bytes.hasRemaining())channel.write(bytes);channel.force(true);
        }catch(Exception error){throw new IllegalStateException(error);}
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private void finish(Throwable error){
        if(!finished.compareAndSet(false,true))return;
        String message=error==null?"NATIVE_TUBE_CRASH_PASS "+cut+" round="+round:"NATIVE_TUBE_CRASH_FAIL "+cut+" "+error;
        System.out.println(message);if(error!=null)error.printStackTrace();
        writeForced(Path.of("result-"+stage+"-"+round+".txt"),message+"\n");
        CompletableFuture.runAsync(()->HytaleServer.get().shutdownServer());
    }
    @Override protected void shutdown(){if(ledger!=null)ledger.close();if(world!=null&&world.isSavingLocked())world.unlockSaving();}
}
