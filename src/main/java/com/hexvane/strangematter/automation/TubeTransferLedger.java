package com.hexvane.strangematter.automation;

import com.google.gson.Gson;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.world.World;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import static java.nio.file.StandardOpenOption.*;

/** Intent precedes mutation; each completion marker is serialized in the same holder as its inventory. */
final class TubeTransferLedger implements AutoCloseable {
    private static final Gson GSON=new Gson();
    record Intent(int version,UUID id,TubeEndpoints.Address source,TubeEndpoints.Address destination,short slot,int quantity,
                  String stack,List<String> beforeSource,List<String> afterSource,List<String> beforeDestination,List<String> afterDestination){}
    static final class Pending {
        final Intent intent;CompletableFuture<Void> prepare,delete;boolean recovered,baselineReady,sourceAck,destinationAck;String problem="";
        java.util.function.BooleanSupplier stillValid=()->true;
        long retryPrepare;
        final Map<String,CompletableFuture<Void>> saves=new HashMap<>();
        final boolean loaded;
        Pending(Intent i,boolean recovered){intent=i;this.recovered=recovered;baselineReady=recovered;loaded=recovered;}
    }
    private final Path directory;
    private final Set<UUID> completed=ConcurrentHashMap.newKeySet();
    private final ExecutorService io=Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("SM-tube-receipts").factory());
    private final Map<UUID,Pending> pending=new ConcurrentHashMap<>();
    private record Baseline(List<String> contents,CompletableFuture<Void> saved){}
    private final Map<TubeEndpoints.Address,Baseline> identities=new ConcurrentHashMap<>();
    private final TubeEndpoints endpoints;
    private final Function<TubeEndpoints.Endpoint,CompletableFuture<Void>> checkpoint;
    TubeTransferLedger(Path directory,TubeEndpoints endpoints){this(directory,endpoints,TubeCheckpoints::save);}
    TubeTransferLedger(Path root,TubeEndpoints endpoints,Function<TubeEndpoints.Endpoint,CompletableFuture<Void>> checkpoint){
        directory=root.resolve("tube-transfers");this.endpoints=endpoints;this.checkpoint=checkpoint;
        try{Files.createDirectories(directory);readCompletions();try(var files=Files.list(directory)){for(var file:files.filter(p->p.toString().endsWith(".json")).toList()){
            var intent=GSON.fromJson(Files.readString(file),Intent.class);validate(intent);if(!completed.contains(intent.id))pending.put(intent.id,new Pending(intent,true));
        }}}catch(IOException|RuntimeException ex){throw new IllegalStateException("Cannot read tube transfer receipts; originals preserved",ex);}
    }
    private void readCompletions()throws IOException{
        var file=directory.resolve("completed.log");if(!Files.exists(file))return;var text=Files.readString(file,StandardCharsets.UTF_8);int end=text.lastIndexOf('\n')+1;
        if(end>0)for(var line:text.substring(0,end).split("\n"))if(!line.isBlank())completed.add(UUID.fromString(line));
        // A process may stop while appending the final record. Only newline-terminated records are committed.
        if(end<text.length())try(var channel=FileChannel.open(file,WRITE)){channel.truncate(text.substring(0,end).getBytes(StandardCharsets.UTF_8).length);channel.force(true);}
    }
    private static void validate(Intent i){
        if(i==null||i.version!=1||i.id==null||i.source==null||i.destination==null||i.quantity<1||i.quantity>5||i.stack==null
            ||i.beforeSource==null||i.afterSource==null||i.beforeDestination==null||i.afterDestination==null
            ||i.beforeSource.size()!=i.afterSource.size()||i.beforeDestination.size()!=i.afterDestination.size()
            ||i.slot<0||i.slot>=i.beforeSource.size()||i.source.world()==null||!i.source.world().equals(i.destination.world())||i.source.identity()==null||i.destination.identity()==null
            ||i.source.section()==null||i.destination.section()==null)throw new IllegalArgumentException("Invalid tube transfer");
        TubeStacks.decode(i.stack);for(var list:List.of(i.beforeSource,i.afterSource,i.beforeDestination,i.afterDestination))for(var stack:list)TubeStacks.decode(stack);
        var original=TubeStacks.decode(i.beforeSource.get(i.slot));if(ItemStack.isEmpty(original)||original.getQuantity()<i.quantity||TubeStacks.decode(i.stack).getQuantity()!=i.quantity)throw new IllegalArgumentException("Invalid tube stack quantity");
    }
    static Intent plan(TubeEndpoints.Endpoint source,TubeEndpoints.Endpoint destination,short slot,int quantity){
        if(source.port().inventory()==destination.port().inventory()||quantity<1||quantity>5)return null;
        var original=source.port().inventory().getItemStack(slot);if(ItemStack.isEmpty(original)||original.getQuantity()<quantity||!source.port().extractable()||!source.port().acceptsExtract().test(original)||!destination.port().acceptsInsert().test(original))return null;
        if(!TubeStacks.removable(source.port().inventory(),slot))return null;
        var portion=TubeStacks.quantity(original,quantity);
        // canAdd's first flag is fullStacks: false includes room in occupied stacks.
        // This must match the native move, which merges stacks and enforces filters.
        if(!source.port().inventory().canRemoveItemStack(portion,true,true)||!destination.port().inventory().canAddItemStack(portion,false,true))return null;
        // Native stacking ignores this field. Refuse an incompatible merge rather than erase either item's setting.
        for(short index=0;index<destination.port().inventory().getCapacity();index++){
            var existing=destination.port().inventory().getItemStack(index);
            if(!ItemStack.isEmpty(existing)&&existing.isStackableWith(original)&&existing.getOverrideDroppedItemAnimation()!=original.getOverrideDroppedItemAnimation())return null;
        }
        var from=TubeStacks.copy(source.port().inventory());var to=TubeStacks.copy(destination.port().inventory());
        var transaction=from.moveItemStackFromSlot(slot,quantity,to,true,true);
        if(transaction==null||!transaction.succeeded())return null;
        preserveDropAnimation(from,to,slot,original);
        return new Intent(1,UUID.randomUUID(),source.address(),destination.address(),slot,quantity,TubeStacks.encode(TubeStacks.quantity(original,quantity)),
            TubeStacks.snapshot(source.port().inventory()),TubeStacks.snapshot(from),TubeStacks.snapshot(destination.port().inventory()),TubeStacks.snapshot(to));
    }
    boolean begin(TubeEndpoints.Endpoint source,TubeEndpoints.Endpoint destination,short slot,int quantity){
        return begin(source,destination,slot,quantity,()->true);
    }
    boolean begin(TubeEndpoints.Endpoint source,TubeEndpoints.Endpoint destination,short slot,int quantity,java.util.function.BooleanSupplier stillValid){
        if(blocked(source)||blocked(destination)||!ready(source)||!ready(destination))return false;var intent=plan(source,destination,slot,quantity);if(intent==null)return false;
        var p=new Pending(intent,false);p.stillValid=stillValid;pending.put(intent.id,p);
        // A route's earlier identity checkpoint is advisory. Every arrival gets a fresh acknowledged
        // baseline before writing its intent, even if contents changed away and then back during flight.
        identities.remove(source.address());identities.remove(destination.address());return true;
    }
    /** Persist the current full inventory and its identity before a journal may refer to that baseline. */
    boolean ready(TubeEndpoints.Endpoint endpoint){
        var key=endpoint.address();var current=TubeStacks.snapshot(endpoint.port().inventory());var baseline=identities.get(key);
        if(baseline!=null){
            // Let an older write finish before replacing it; native section flags also prevent concurrent snapshots.
            if(!baseline.saved.isDone())return false;
            if(baseline.saved.isCompletedExceptionally()){identities.remove(key,baseline);return false;}
            if(baseline.contents.equals(current))return true;
        }
        var saved=checkpoint.apply(endpoint);if(saved!=null)identities.put(key,new Baseline(current,saved));return false;
    }
    private void write(Intent intent){
        Path target=directory.resolve(intent.id+".json"),temp=directory.resolve(intent.id+".tmp");
        byte[] bytes=GSON.toJson(intent).getBytes(StandardCharsets.UTF_8);
        try(var channel=FileChannel.open(temp,CREATE,TRUNCATE_EXISTING,WRITE)){var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}
        catch(IOException ex){throw new CompletionException(ex);}
        try{Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        catch(IOException ex){throw new CompletionException(ex);}
    }
    boolean blocked(TubeEndpoints.Endpoint endpoint){
        for(var p:pending.values())if(p.intent.source.world().equals(endpoint.world().getName())&&
            (matchesIdentity(endpoint,p.intent.source)||matchesIdentity(endpoint,p.intent.destination)))return true;return false;
    }
    boolean blocked(World world,TubeEndpoints.Position position){
        var origin=TubeEndpoints.origin(world,position);if(origin==null)return false;
        for(var p:pending.values())if(p.intent.source.world().equals(world.getName())){
            for(var address:List.of(p.intent.source,p.intent.destination)){
                var expected=TubeEndpoints.origin(world,address.position());if(origin.equals(expected)||origin.equals(address.position()))return true;
            }
        }
        for(var endpoint:endpoints.all(world,origin,false))if(blocked(endpoint))return true;return false;
    }
    private static boolean matchesIdentity(TubeEndpoints.Endpoint endpoint,TubeEndpoints.Address address){return endpoint.receipts().owns(address.identity());}
    void tick(World world){
        int budget=8;
        for(var p:List.copyOf(pending.values())){
            if(!p.intent.source.world().equals(world.getName())||budget--<=0)continue;
            if(p.delete!=null){if(p.delete.isDone()){if(!p.delete.isCompletedExceptionally()){pending.remove(p.intent.id);retire(world,p.intent);}else {p.delete=null;p.problem="Retrying transfer completion checkpoint";}}continue;}
            if(!p.baselineReady){
                var from=endpoints.resolve(world,p.intent.source);var to=endpoints.resolve(world,p.intent.destination);
                if(from==null||to==null){p.problem="Waiting to save both original containers";continue;}
                if(!p.stillValid.getAsBoolean()||!TubeStacks.matches(from.port().inventory(),p.intent.beforeSource)||!TubeStacks.matches(to.port().inventory(),p.intent.beforeDestination)){delete(p);continue;}
                boolean fromSaved=save(p,from,"baseline:source"),toSaved=save(p,to,"baseline:destination");
                if(fromSaved&&toSaved){p.baselineReady=true;p.prepare=CompletableFuture.runAsync(()->write(p.intent),io);}
                else p.problem="Saving current container contents";
                continue;
            }
            if(p.prepare!=null){
                if(!p.prepare.isDone())continue;
                if(p.prepare.isCompletedExceptionally()){
                    p.problem="Transfer journal could not be saved";
                    if(System.nanoTime()>=p.retryPrepare){p.retryPrepare=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);p.prepare=CompletableFuture.runAsync(()->write(p.intent),io);}
                    continue;
                }
                p.prepare=null;
            }
            var from=endpoints.resolve(world,p.intent.source);var to=endpoints.resolve(world,p.intent.destination);
            if(p.loaded){if(from!=null)TubeEndpoints.closeWindows(from);if(to!=null)TubeEndpoints.closeWindows(to);}
            if(from==null||to==null){p.problem="Waiting for both original containers";continue;}
            if(!p.recovered){
                if(!p.stillValid.getAsBoolean()||!TubeStacks.matches(from.port().inventory(),p.intent.beforeSource)||!TubeStacks.matches(to.port().inventory(),p.intent.beforeDestination)){
                    // No inventory mutation occurred yet. Persist cancellation before releasing endpoint holds.
                    delete(p);continue;
                }
                // The native rejected REMOVE path returns null internally but sendUpdate dereferences it.
                // Revalidate the exact source slot before invoking that native transaction.
                if(!TubeStacks.removable(from.port().inventory(),p.intent.slot)||!to.port().inventory().canAddItemStack(TubeStacks.decode(p.intent.stack),false,true)){delete(p);continue;}
                var transaction=from.port().inventory().moveItemStackFromSlot(p.intent.slot,p.intent.quantity,to.port().inventory(),true,true);
                if(transaction==null||!transaction.succeeded()){delete(p);continue;}
                preserveDropAnimation(from.port().inventory(),to.port().inventory(),p.intent.slot,TubeStacks.decode(p.intent.stack));
                from.receipts().applied(p.intent.id,from.port().section());to.receipts().applied(p.intent.id,to.port().section());from.dirty();to.dirty();p.recovered=true;
            }
            // Validate BOTH before replaying either missing side. External modifications are never overwritten.
            if(!recoverable(from,p.intent,true)||!recoverable(to,p.intent,false)){p.problem="Recovery paused: container contents changed";continue;}
            if(!recoverSide(from,p.intent,true)||!recoverSide(to,p.intent,false)){p.problem="Recovery paused: native inventory rejected replay";continue;}
            p.problem="Saving transfer receipts";
            if(!p.sourceAck)p.sourceAck=save(p,from,"source");
            if(!p.destinationAck)p.destinationAck=save(p,to,"destination");
            if(p.sourceAck&&p.destinationAck)delete(p);
        }
    }
    static boolean recoverable(TubeEndpoints.Endpoint e,Intent i,boolean source){
        if(e.receipts().contains(i.id,e.port().section()))return true;var inventory=e.port().inventory();
        return TubeStacks.matches(inventory,source?i.beforeSource:i.beforeDestination)||TubeStacks.matches(inventory,source?i.afterSource:i.afterDestination);
    }
    static boolean recoverSide(TubeEndpoints.Endpoint e,Intent i,boolean source){
        if(e.receipts().contains(i.id,e.port().section()))return true;var inventory=e.port().inventory();var after=source?i.afterSource:i.afterDestination;
        if(!TubeStacks.matches(inventory,after)){
            if(!TubeStacks.matches(inventory,source?i.beforeSource:i.beforeDestination))return false;
            if(source){var result=inventory.removeItemStackFromSlot(i.slot,TubeStacks.decode(i.beforeSource.get(i.slot)),i.quantity,true,false);if(!result.succeeded())return false;}
            else {var result=inventory.addItemStack(TubeStacks.decode(i.stack),true,false,false);if(!result.succeeded())return false;}
            if(source){var left=inventory.getItemStack(i.slot);if(!ItemStack.isEmpty(left))left.setOverrideDroppedItemAnimation(TubeStacks.decode(i.stack).getOverrideDroppedItemAnimation());}
            else restoreDropAnimation(inventory,TubeStacks.decode(i.stack));
            if(!TubeStacks.matches(inventory,after))return false;
        }
        e.receipts().applied(i.id,e.port().section());e.dirty();return true;
    }
    // Native withQuantity omits this presentation flag. The full codec snapshots and resulting live stacks must agree.
    private static void preserveDropAnimation(ItemContainer source,ItemContainer destination,short slot,ItemStack original){
        var remaining=source.getItemStack(slot);if(!ItemStack.isEmpty(remaining))remaining.setOverrideDroppedItemAnimation(original.getOverrideDroppedItemAnimation());
        restoreDropAnimation(destination,original);
    }
    private static void restoreDropAnimation(ItemContainer container,ItemStack original){
        if(!original.getOverrideDroppedItemAnimation())return;
        for(short slot=0;slot<container.getCapacity();slot++){var value=container.getItemStack(slot);if(!ItemStack.isEmpty(value)&&value.isStackableWith(original))value.setOverrideDroppedItemAnimation(true);}
    }
    private boolean save(Pending p,TubeEndpoints.Endpoint e,String side){
        var saving=p.saves.get(side);
        if(saving==null){try{saving=checkpoint.apply(e);if(saving!=null)p.saves.put(side,saving);}catch(RuntimeException error){p.problem="Native container save failed";}return false;}
        if(!saving.isDone())return false;
        if(saving.isCompletedExceptionally()){p.saves.remove(side);p.problem="Retrying native container save";return false;}
        return true;
    }
    private void delete(Pending p){p.delete=CompletableFuture.runAsync(()->{
        try{
            if(!completed.contains(p.intent.id)){
                try(var file=FileChannel.open(directory.resolve("completed.log"),CREATE,WRITE,APPEND)){
                    var bytes=ByteBuffer.wrap((p.intent.id+"\n").getBytes(StandardCharsets.UTF_8));while(bytes.hasRemaining())file.write(bytes);file.force(true);
                }
                completed.add(p.intent.id);
            }
            Files.deleteIfExists(directory.resolve(p.intent.id+".json"));
        }catch(IOException ex){throw new CompletionException(ex);}
    },io);}
    private void retire(World world,Intent i){for(var address:List.of(i.source,i.destination)){var e=endpoints.resolve(world,address);if(e!=null){e.receipts().retire(i.id);e.dirty();}}}
    String status(World world,TubeEndpoints.Position p){for(var pending:pending.values())if(pending.intent.source.world().equals(world.getName())&&
        (pending.intent.source.position().equals(p)||pending.intent.destination.position().equals(p)))return pending.problem.isEmpty()?"Preparing transfer":pending.problem;return "";}
    int pendingCount(){return pending.size();}
    Collection<Pending> pending(){return List.copyOf(pending.values());}
    @Override public void close(){io.shutdown();}
}
