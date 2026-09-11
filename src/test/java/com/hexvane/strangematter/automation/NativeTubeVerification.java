package com.hexvane.strangematter.automation;

import com.hexvane.strangematter.util.WorldAccess;
import com.hexvane.strangematter.equipment.NativePlayerFixture;
import com.hypixel.hytale.builtin.crafting.component.ProcessingBenchBlock;
import com.hypixel.hytale.codec.*;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.inventory.*;
import com.hypixel.hytale.server.core.inventory.container.*;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.modules.entity.component.*;
import com.hypixel.hytale.server.core.modules.entity.item.*;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockComponentSection;
import com.hypixel.hytale.server.core.universe.world.storage.*;
import org.bson.*;
import org.joml.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static com.hexvane.strangematter.automation.TubeEndpoints.*;
import static com.hypixel.hytale.server.core.universe.world.SetBlockSettings.*;

/** Native container components, filters, holder serialization and the real storage backend. */
public final class NativeTubeVerification {
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private record Cell(Position position,int id,int rotation,int filler){}
    private static final Position SOURCE=new Position(3,232,10),DESTINATION=new Position(12,232,10),FURNACE=new Position(22,232,10);
    private static final class Fixture implements AutoCloseable {
        final World world;final List<Cell> saved=new ArrayList<>();final TubeEndpoints endpoints=new TubeEndpoints(null);
        Fixture(World world){this.world=world;var chunk=WorldAccess.loaded(world,ChunkUtil.indexChunk(0,0));require(chunk!=null,"Tube fixture chunk is loaded");
            for(int x=2;x<=25;x++)for(int y=231;y<=236;y++)for(int z=9;z<=13;z++){
                var p=new Position(x,y,z);saved.add(new Cell(p,WorldAccess.block(chunk,x,y,z),WorldAccess.rotation(chunk,x,y,z),WorldAccess.filler(chunk,x,y,z)));
                WorldAccess.set(chunk,x,y,z,y==231?"Rock_Stone":"Empty",NO_UPDATE_STATE|NO_SEND_PARTICLES|NO_UPDATE_NEIGHBOR_CONNECTIONS|FORCE_CHANGED);
            }
            place(SOURCE,"SM_Resonite_Chest");place(DESTINATION,"SM_Resonite_Chest_Large");place(FURNACE,"Bench_Furnace");
            for(int x=4;x<=10;x++)place(new Position(x,232,10),TubeService.ID);
        }
        void place(Position p,String id){var chunk=WorldAccess.loaded(world,ChunkUtil.indexChunkFromBlock(p.x(),p.z()));require(WorldAccess.set(chunk,p.x(),p.y(),p.z(),id),"Native tube fixture placement "+id);}
        Endpoint from(){return Objects.requireNonNull(endpoints.resolve(world,SOURCE,"storage",true));}
        Endpoint to(){return Objects.requireNonNull(endpoints.resolve(world,new Position(11,232,10),"storage",true));}
        @Override public void close(){
            for(var p:List.of(SOURCE,DESTINATION,FURNACE))for(var e:endpoints.all(world,p,false))e.port().inventory().clear();
            var chunk=WorldAccess.loaded(world,ChunkUtil.indexChunk(0,0));
            for(var c:saved){var p=c.position;WorldAccess.set(chunk,p.x(),p.y(),p.z(),c.id,BlockType.getAssetMap().getAsset(c.id),c.rotation,c.filler,NO_UPDATE_STATE|NO_SEND_PARTICLES|NO_UPDATE_NEIGHBOR_CONNECTIONS|FORCE_CHANGED);}
        }
    }
    public static void verify(World world)throws Exception{
        world.getEntityStore().getStore().assertThread();
        try(var fixture=new Fixture(world)){
            var source=fixture.from();var target=fixture.to();
            require(source.port().inventory().getCapacity()==18&&target.port().inventory().getCapacity()==36,"Normal and large chest capacities use actual native containers");
            var fromOtherHalf=fixture.endpoints.resolve(world,DESTINATION,"storage",true);
            require(fromOtherHalf.ref()==target.ref()&&fromOtherHalf.receipts()==target.receipts()&&fromOtherHalf.port().inventory()==target.port().inventory(),"Both large chest cells resolve the exact same origin, inventory and receipt identity");
            verifyFurnace(fixture);verifyCombinedPlanning(fixture);verifyFilters();verifyCarrier(world);verifyReceiptPermutations(fixture);verifyJournalRestarts(fixture);verifyRouting(fixture);verifyBounds(fixture);
            System.out.println("NATIVE_TUBES PASS chest origins, furnace sections and filters, ghost metadata, no pickup carrier, source retention, route cancellation and asymmetric native holder receipts");
        }
    }
    private static ItemStack marked(int count){return new ItemStack("SM_Resonite_Ingot",count,new BsonDocument("TubeCheck",new BsonDocument("rank",new BsonInt64(9223372036854775806L)).append("labels",new BsonArray(List.of(new BsonString("cyan"),new BsonString("purple"))))));}
    private static void verifyCombinedPlanning(Fixture fixture){
        var first=new SimpleItemContainer((short)2);var second=new SimpleItemContainer((short)3);
        var destinationFirst=new SimpleItemContainer((short)2);var destinationSecond=new SimpleItemContainer((short)3);
        destinationFirst.setSlotFilter(com.hypixel.hytale.server.core.inventory.container.filter.FilterActionType.ADD,(short)0,com.hypixel.hytale.server.core.inventory.container.filter.SlotFilter.DENY);
        var combined=new CombinedItemContainer(first,second);var target=new CombinedItemContainer(destinationFirst,destinationSecond);
        second.setItemStackForSlot((short)1,marked(7));var from=fixture.from();var to=fixture.to();
        var sourceEndpoint=new Endpoint(fixture.world,from.position(),from.ref(),from.receipts(),new TubePort("storage","Combined",combined,s->true,true));
        var targetEndpoint=new Endpoint(fixture.world,to.position(),to.ref(),to.receipts(),new TubePort("storage","Combined",target,s->true,true));
        var intent=TubeTransferLedger.plan(sourceEndpoint,targetEndpoint,(short)3,3);
        require(intent!=null&&intent.beforeSource().size()==5&&TubeStacks.decode(intent.afterSource().get(3)).getQuantity()==4,"Native combined inventories plan through public slots without unsupported clone");
        require(intent.afterDestination().get(0)==null&&TubeStacks.decode(intent.afterDestination().get(1)).getQuantity()==3,"Combined planning preserves native per-slot ADD rejection and chooses the allowed slot");
        require(second.getItemStack((short)1).getQuantity()==7&&target.isEmpty(),"Combined planning never mutates either live native delegate");
        var moved=combined.moveItemStackFromSlot((short)3,3,target,true,true);
        require(moved.succeeded()&&TubeStacks.matches(combined,intent.afterSource())&&TubeStacks.matches(target,intent.afterDestination()),"Actual native combined filtered transfer matches planned metadata and quantities");
        first.setItemStackForSlot((short)0,marked(7));
        second.setSlotFilter(com.hypixel.hytale.server.core.inventory.container.filter.FilterActionType.REMOVE,(short)1,com.hypixel.hytale.server.core.inventory.container.filter.SlotFilter.DENY);
        require(TubeTransferLedger.plan(sourceEndpoint,targetEndpoint,(short)3,1)==null,"Native per-slot REMOVE rejection is retained even when a different source slot is extractable");
    }
    private static void verifyFilters(){
        var config=new TubeConfiguration();config.samples[0]=TubeStacks.encode(TubeStacks.quantity(marked(3),1));
        config.validate();require(config.accepts(new ItemStack("SM_Resonite_Ingot",5))&&!config.accepts(new ItemStack("Ore_Copper",1)),"Ghost same-item allow filter");
        config.match=TubeConfiguration.Match.EXACT;require(config.accepts(marked(1))&&!config.accepts(new ItemStack("SM_Resonite_Ingot",1)),"Exact filter preserves arbitrary nested BSON metadata");
        config.exclude=true;require(!config.accepts(marked(1))&&config.accepts(new ItemStack("Ore_Copper",1)),"Exclude filter");
        config.match=TubeConfiguration.Match.RESOURCE;config.exclude=false;config.resource="Wood_Planks";
        require(config.accepts(new ItemStack("Wood_Softwood_Planks",1))&&config.accepts(new ItemStack("Wood_Hardwood_Planks",1)),"Resource family supports multiple native wood variants");
        var stack=marked(7);stack.setOverrideDroppedItemAnimation(true);require(TubeStacks.decode(TubeStacks.encode(stack)).getOverrideDroppedItemAnimation(),"Full native codec retains dropped-animation override");
        require(TubeStacks.quantity(stack,2).getOverrideDroppedItemAnimation()&&TubeStacks.quantity(stack,2).getQuantity()==2,"Quantity copies retain every native field");
        var tube=new TubeComponent();tube.configure(3,config);var restored=TubeComponent.CODEC.decode(TubeComponent.CODEC.encode(tube,new ExtraInfo()),new ExtraInfo());
        require(restored.id().equals(tube.id())&&restored.face(3).accepts(new ItemStack("Wood_Hardwood_Planks",1)),"All six face settings and identity survive native component serialization");
    }
    private static void verifyFurnace(Fixture fixture){
        var ports=fixture.endpoints.all(fixture.world,FURNACE,true);require(ports.size()==3,"Native furnace exposes input, fuel and output separately");
        var input=ports.stream().filter(e->e.port().section().equals("input")).findFirst().orElseThrow();var fuel=ports.stream().filter(e->e.port().section().equals("fuel")).findFirst().orElseThrow();var output=ports.stream().filter(e->e.port().section().equals("output")).findFirst().orElseThrow();
        var source=fixture.from();source.port().inventory().setItemStackForSlot((short)0,new ItemStack("Ore_Copper",5),false);
        require(TubeTransferLedger.plan(source,input,(short)0,2)!=null,"Native furnace input filter accepts a real smelting ingredient");
        require(TubeTransferLedger.plan(source,fuel,(short)0,2)==null,"Native fuel filter rejects ore");
        require(TubeTransferLedger.plan(source,output,(short)0,2)==null,"Furnace output cannot receive items");
        source.port().inventory().setItemStackForSlot((short)0,new ItemStack("Ingredient_Charcoal",5),false);
        require(TubeTransferLedger.plan(source,fuel,(short)0,2)!=null,"Native fuel container accepts actual charcoal");
        output.port().inventory().setItemStackForSlot((short)0,new ItemStack("Ingredient_Bar_Copper",2),false);
        require(TubeTransferLedger.plan(output,fixture.to(),(short)0,1)!=null,"Native output can be extracted without confusing its combined index");
        input.port().inventory().setItemStackForSlot((short)0,new ItemStack("Ingredient_Charcoal",3),false);
        var internal=TubeTransferLedger.plan(input,fuel,(short)0,1);require(internal!=null,"Distinct sections may share one native holder");
        require(TubeTransferLedger.recoverSide(input,internal,true)&&!fuel.receipts().contains(internal.id(),"fuel"),"Applying one section does not mark a different section of the same holder complete");
        require(TubeTransferLedger.recoverSide(fuel,internal,false)&&total(input)==2&&total(fuel)==1,"Same-holder recovery replays each inventory section exactly once");
        input.receipts().retire(internal.id());input.port().inventory().clear();fuel.port().inventory().clear();
        source.port().inventory().clear();output.port().inventory().clear();
    }
    private static void verifyCarrier(World world)throws Exception{
        TubeCarrier.prepare(marked(3)).get(10,TimeUnit.SECONDS);
        var ref=TubeCarrier.spawn(world,marked(3),new Vector3d(8.5,232.5,10.5));var store=ref.getStore();
        try{
            require(store.getComponent(ref,PreventPickup.getComponentType())!=null&&store.getComponent(ref,PreventItemMerging.getComponentType())!=null,"Tube carriers cannot enter native pickup or merging systems");
            require(store.getComponent(ref,Velocity.getComponentType())==null&&store.getComponent(ref,EntityStore.REGISTRY.getNonSerializedComponentType())!=null,"Tube carriers have no free item physics and cannot be saved");
            var display=TubeStacks.quantity(marked(3),1);display.setOverrideDroppedItemAnimation(true);
            require(TubeStacks.same(store.getComponent(ref,ItemComponent.getComponentType()).getItemStack(),display),"Visual item preserves metadata without owning the batch and disables native dropped animation only on its display copy");
            world.getEntityStore().getStore().tick(.05f);
            require(ref.isValid()&&store.getComponent(ref,Velocity.getComponentType())==null,"Native full ECS tick leaves the carrier in presentation rather than dropped-item physics");
        }finally{TubeCarrier.remove(ref);}
    }
    private static BsonDocument snapshot(Endpoint e){return ChunkStore.REGISTRY.serialize(e.ref().getStore().copySerializableEntity(e.ref()));}
    private static void restore(Endpoint e,BsonDocument document){
        var holder=ChunkStore.REGISTRY.deserialize(document);var store=e.ref().getStore();
        store.putComponent(e.ref(),ItemContainerBlock.getComponentType(),holder.getComponent(ItemContainerBlock.getComponentType()));
        store.putComponent(e.ref(),TubeEndpointReceipts.type,holder.getComponent(TubeEndpointReceipts.type));
    }
    private static void verifyReceiptPermutations(Fixture fixture){
        var source=fixture.from();var destination=fixture.to();source.port().inventory().clear();destination.port().inventory().clear();source.port().inventory().setItemStackForSlot((short)0,marked(10),false);
        var intent=TubeTransferLedger.plan(source,destination,(short)0,3);require(intent!=null,"Native transfer staging succeeds");
        var beforeSource=snapshot(source);var beforeDestination=snapshot(destination);
        require(source.port().inventory().moveItemStackFromSlot((short)0,3,destination.port().inventory(),true,true).succeeded(),"Actual native filtered move succeeds");
        source.receipts().applied(intent.id(),"storage");destination.receipts().applied(intent.id(),"storage");var afterSource=snapshot(source);var afterDestination=snapshot(destination);
        for(int mask=0;mask<4;mask++){
            restore(fixture.from(),(mask&1)==0?beforeSource:afterSource);restore(fixture.to(),(mask&2)==0?beforeDestination:afterDestination);
            var from=fixture.from();var to=fixture.to();
            require(TubeTransferLedger.recoverable(from,intent,true)&&TubeTransferLedger.recoverable(to,intent,false),"Both persisted holder sides are validated before any replay "+mask);
            require(TubeTransferLedger.recoverSide(from,intent,true)&&TubeTransferLedger.recoverSide(to,intent,false),"Asymmetric save replay "+mask);
            require(total(from)==7&&total(to)==3&&TubeStacks.same(to.port().inventory().getItemStack((short)0),marked(3)),"Asymmetric saves conserve quantity and full metadata "+mask);
            require(TubeTransferLedger.recoverSide(from,intent,true)&&TubeTransferLedger.recoverSide(to,intent,false)&&total(from)+total(to)==10,"Receipt replay is idempotent "+mask);
        }
        restore(fixture.from(),beforeSource);restore(fixture.to(),afterDestination);fixture.to().port().inventory().clear();
        require(TubeTransferLedger.recoverSide(fixture.from(),intent,true)&&TubeTransferLedger.recoverSide(fixture.to(),intent,false)&&total(fixture.to())==0,"An applied destination receipt prevents replenishing items taken by a player after saving");
        restore(fixture.from(),beforeSource);restore(fixture.to(),beforeDestination);fixture.to().port().inventory().setItemStackForSlot((short)0,new ItemStack("Ore_Copper",1),false);
        require(!TubeTransferLedger.recoverable(fixture.to(),intent,false)&&total(fixture.from())==10,"Unrelated missing-marker changes pause recovery without taking source items");
        restore(fixture.from(),beforeSource);restore(fixture.to(),beforeDestination);
        var inherited=new TubeEndpointReceipts();inherited.merge(fixture.from().receipts());require(inherited.owns(intent.source().identity()),"Native chest merge receipts preserve prior canonical identities");
        fixture.from().port().inventory().clear();fixture.to().port().inventory().clear();
    }
    private static int total(Endpoint e){int total=0;for(short i=0;i<e.port().inventory().getCapacity();i++){var s=e.port().inventory().getItemStack(i);if(!ItemStack.isEmpty(s))total+=s.getQuantity();}return total;}
    private static void verifyJournalRestarts(Fixture fixture)throws Exception{
        var source=fixture.from();var destination=fixture.to();source.port().inventory().clear();destination.port().inventory().clear();source.port().inventory().setItemStackForSlot((short)0,marked(10),false);
        var intent=TubeTransferLedger.plan(source,destination,(short)0,3);var beforeSource=snapshot(source);var beforeDestination=snapshot(destination);
        TubeTransferLedger.recoverSide(source,intent,true);TubeTransferLedger.recoverSide(destination,intent,false);
        var afterSource=snapshot(source);var afterDestination=snapshot(destination);
        for(int mask=0;mask<4;mask++){
            restore(fixture.from(),(mask&1)==0?beforeSource:afterSource);restore(fixture.to(),(mask&2)==0?beforeDestination:afterDestination);
            var root=Files.createTempDirectory("sm-tube-crash-");var journal=root.resolve("tube-transfers");Files.createDirectories(journal);
            String encoded=new com.google.gson.Gson().toJson(intent);Files.writeString(journal.resolve(intent.id()+".json"),encoded);
            var attempts=new java.util.concurrent.atomic.AtomicInteger();
            try(var ledger=new TubeTransferLedger(root,fixture.endpoints,e->{snapshot(e);return attempts.getAndIncrement()==0?CompletableFuture.failedFuture(new java.io.IOException("intentional save failure")):CompletableFuture.completedFuture(null);})){
                require(ledger.blocked(fixture.from())&&ledger.blocked(fixture.to()),"Restart blocks both canonical endpoints before transfer mutation");
                ledger.tick(fixture.world);require(total(fixture.from())==7&&total(fixture.to())==3&&ledger.pendingCount()==1,"Failure to save retains the receipt without undoing or repeating the move");
                for(int i=0;i<100&&ledger.pendingCount()>0;i++){ledger.tick(fixture.world);Thread.sleep(2);}
                require(ledger.pendingCount()==0&&total(fixture.from())==7&&total(fixture.to())==3,"Loaded journal recovers both sides and retires only after actual checkpoint futures: "+mask);
            }
            // Reappearing older intent data cannot replay after its durable completion record.
            Files.writeString(journal.resolve(intent.id()+".json"),encoded);
            try(var again=new TubeTransferLedger(root,fixture.endpoints,e->CompletableFuture.completedFuture(null))){
                again.tick(fixture.world);require(again.pendingCount()==0&&total(fixture.from())+total(fixture.to())==10,"Durable completion tombstone rejects a stale journal replay");
            }
        }
        restore(fixture.from(),beforeSource);restore(fixture.to(),beforeDestination);
        var stale=Files.createTempDirectory("sm-tube-identity-");Files.createDirectories(stale.resolve("tube-transfers"));Files.writeString(stale.resolve("tube-transfers").resolve(intent.id()+".json"),new com.google.gson.Gson().toJson(intent));
        fixture.to().ref().getStore().putComponent(fixture.to().ref(),TubeEndpointReceipts.type,new TubeEndpointReceipts());
        try(var ledger=new TubeTransferLedger(stale,fixture.endpoints,e->CompletableFuture.completedFuture(null))){
            ledger.tick(fixture.world);require(ledger.pendingCount()==1&&total(fixture.from())==10&&ledger.blocked(fixture.world,DESTINATION),"A replaced destination generation is quarantined across every face without taking the source");
        }
        restore(fixture.to(),beforeDestination);fixture.from().port().inventory().clear();fixture.to().port().inventory().clear();
    }
    private static void verifyRouting(Fixture fixture)throws Exception{
        var directory=Files.createTempDirectory("sm-tube-routing-");
        try(var service=new TubeService(directory,null,e->{snapshot(e);return CompletableFuture.completedFuture(null);});var player=NativePlayerFixture.create(fixture.world,"TubeOwner",new Vector3d(6,233,10))){
            for(int x=4;x<=10;x++)service.placed(fixture.world,new Vector3i(x,232,10),player.owner().getUuid());
            var from=service.current(fixture.world,new Position(4,232,10));var to=service.current(fixture.world,new Position(10,232,10));
            var extract=new TubeConfiguration();extract.mode=TubeConfiguration.Mode.EXTRACT;extract.batch=3;extract.leaveBehind=2;
            var insert=new TubeConfiguration();insert.mode=TubeConfiguration.Mode.INSERT;insert.fillUpTo=5;
            from.component().configure(1,extract);to.component().configure(0,insert);fixture.from().port().inventory().setItemStackForSlot((short)0,marked(10),false);
            for(int i=0;i<100&&service.activeFlights(fixture.world)==0;i++)service.tick(fixture.world,.05);
            require(service.activeFlights(fixture.world)>0&&total(fixture.from())==10&&total(fixture.to())==0,"Visible flight retains the actual stack in its original container");
            fixture.place(new Position(7,232,10),"Empty");service.removed(fixture.world,new Vector3i(7,232,10));service.tick(fixture.world,.05);
            require(service.activeFlights(fixture.world)==0&&total(fixture.from())==10&&total(fixture.to())==0,"Breaking a travelling route cancels reservation without a refund or item loss");
            fixture.place(new Position(7,232,10),TubeService.ID);service.placed(fixture.world,new Vector3i(7,232,10),player.owner().getUuid());
            for(int i=0;i<260;i++){service.tick(fixture.world,.05);Thread.sleep(2);}
            require(total(fixture.to())==5&&total(fixture.from())==5,"Fill limit includes travelling batches and actual native transfers preserve the rest: source="+total(fixture.from())+" target="+total(fixture.to()));
            service.stopWorld(fixture.world);require(service.activeFlights(fixture.world)==0,"World cleanup releases all presentation reservations");
        }
    }
    private static void verifyBounds(Fixture fixture)throws Exception{
        var source=new Position(3,234,10);var destination=new Position(22,234,12);var sourceTube=source.offset(2);var destinationTube=destination.offset(2);
        fixture.place(source.offset(3),"Rock_Stone");fixture.place(destination.offset(3),"Rock_Stone");fixture.place(source,"SM_Resonite_Chest");fixture.place(destination,"SM_Resonite_Chest");
        try(var service=new TubeService(Files.createTempDirectory("sm-tube-bounds-"),null,e->CompletableFuture.completedFuture(null))){
            UUID owner=UUID.randomUUID();int count=0;
            for(int x=2;x<=25;x++)for(int z=9;z<=13;z++){var p=new Position(x,235,z);fixture.place(p,TubeService.ID);service.placed(fixture.world,p.vector(),owner);count++;}
            for(int x=2;x<=10;x++){var p=new Position(x,234,9);fixture.place(p,TubeService.ID);service.placed(fixture.world,p.vector(),owner);count++;}
            require(count==129,"Workload fixture creates 129 connected native tube blocks");
            var extract=new TubeConfiguration();extract.mode=TubeConfiguration.Mode.EXTRACT;
            var insert=new TubeConfiguration();insert.mode=TubeConfiguration.Mode.INSERT;
            service.current(fixture.world,sourceTube).component().configure(3,extract);service.current(fixture.world,destinationTube).component().configure(3,insert);
            var from=fixture.endpoints.resolve(fixture.world,source,"storage",true);var to=fixture.endpoints.resolve(fixture.world,destination,"storage",true);from.port().inventory().setItemStackForSlot((short)0,marked(10),false);
            for(int i=0;i<100;i++)service.tick(fixture.world,.05);
            require(service.activeFlights(fixture.world)==0&&total(from)==10&&service.status(fixture.world,sourceTube).contains("128"),"Oversized network is bounded and refuses transfer without consuming a stack");
            var excess=new Position(10,234,9);fixture.place(excess,"Empty");service.removed(fixture.world,excess.vector());
            for(int i=0;i<100&&service.activeFlights(fixture.world)==0;i++)service.tick(fixture.world,.05);
            require(service.activeFlights(fixture.world)>0&&service.activeFlights(fixture.world)<=32&&total(from)==10,"Removing the 129th tube invalidates the bounded cache and restores valid routing");
            service.stopWorld(fixture.world);require(service.activeFlights(fixture.world)==0&&total(from)+total(to)==10,"Loaded world shutdown cancels bounded flights while actual inventory is untouched");
            from.port().inventory().clear();to.port().inventory().clear();
        }
    }
    public static CompletableFuture<Void> verifyAsync(World world){
        final Fixture fixture;try{fixture=new Fixture(world);}catch(Exception ex){return CompletableFuture.failedFuture(ex);}
        var source=fixture.from();var destination=fixture.to();source.port().inventory().setItemStackForSlot((short)0,marked(6),false);
        var intent=TubeTransferLedger.plan(source,destination,(short)0,2);
        source.port().inventory().moveItemStackFromSlot((short)0,2,destination.port().inventory(),true,true);
        source.receipts().applied(intent.id(),"storage");destination.receipts().applied(intent.id(),"storage");source.dirty();destination.dirty();
        var loader=world.getChunkStore().getLoader();
        return awaitCheckpoint(source).thenCompose(v->{
            if(loader instanceof IChunkLoader.Cubic cubic)return cubic.loadSectionHolder(0,7,0);
            return loader.loadHolder(0,0).thenApply(holder->{
                require(holder!=null,"Actual native column exists on disk");
                var data=ChunkStore.REGISTRY.serialize(holder).getDocument("Components").getDocument("ChunkColumn");
                return Objects.requireNonNull(ChunkStore.REGISTRY.deserialize(data.getArray("Sections").get(7).asDocument()),"Saved endpoint section");
            });
        }).thenAcceptAsync(holder->{
            var blockSection=holder.getComponent(BlockComponentSection.getComponentType());require(blockSection!=null,"Actual native section load returns block component holders");
            for(var e:List.of(source,destination)){
                var p=e.position();var saved=blockSection.getBlockHolder(ChunkUtil.indexBlock(p.x(),p.y(),p.z()));require(saved!=null,"Native backend persists the exact endpoint row");
                var receipts=saved.getComponent(TubeEndpointReceipts.type);var chest=saved.getComponent(ItemContainerBlock.getComponentType());
                require(receipts!=null&&receipts.contains(intent.id())&&chest!=null,"Inventory and receipt are co-saved in one native block holder");
                require(TubeStacks.matches(chest.getItemContainer(),e==source?intent.afterSource():intent.afterDestination()),"Actual disk inventory snapshot matches its applied transfer marker");
            }
            System.out.println("NATIVE_TUBE_STORAGE PASS real native block component save acknowledgement and disk reload co-locate full inventories and transfer receipts");
        },world).whenCompleteAsync((v,error)->fixture.close(),world);
    }
    private static CompletableFuture<Void> awaitCheckpoint(Endpoint e){
        var result=TubeCheckpoints.save(e);if(result!=null)return result;
        return CompletableFuture.runAsync(()->{},CompletableFuture.delayedExecutor(20,TimeUnit.MILLISECONDS)).thenComposeAsync(v->awaitCheckpoint(e),e.world());
    }
    private NativeTubeVerification(){}
}
