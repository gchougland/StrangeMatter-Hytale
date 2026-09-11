package com.hexvane.strangematter.automation;

import com.hexvane.strangematter.StrangeMatterConfig;
import com.hexvane.strangematter.anomaly.AnomalyService;
import com.hexvane.strangematter.equipment.NativePlayerFixture;
import com.hexvane.strangematter.machine.MachineService;
import com.hexvane.strangematter.research.ResearchService;
import com.hexvane.strangematter.util.WorldAccess;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.codec.*;
import com.hypixel.hytale.protocol.ItemUpdate;
import com.hypixel.hytale.protocol.packets.entities.EntityUpdates;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.*;
import com.hypixel.hytale.server.core.modules.entity.item.*;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.world.World;
import org.bson.*;
import org.joml.Vector3d;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static com.hexvane.strangematter.automation.TubeEndpoints.*;
import static com.hypixel.hytale.server.core.universe.world.SetBlockSettings.*;

/** Stock asset containers plus the actual production FactoryService permission adapter. */
public final class NativeTubeBackendVerification {
    private static final Position SOURCE=new Position(5,206,20),LARGE=new Position(15,206,20),FURNACE=new Position(10,206,24);
    private static final Position START=new Position(6,206,20),END=new Position(13,206,20),FEED=new Position(10,206,23);
    private record Cell(Position p,int block,int rotation,int filler){}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void verify(World world)throws Exception{
        var store=world.getEntityStore().getStore();store.assertThread();verifyMigration();
        var chunk=Objects.requireNonNull(WorldAccess.loaded(world,0));var saved=new ArrayList<Cell>();
        for(int x=4;x<=27;x++)for(int y=205;y<=209;y++)for(int z=18;z<=25;z++){
            saved.add(new Cell(new Position(x,y,z),WorldAccess.block(chunk,x,y,z),WorldAccess.rotation(chunk,x,y,z),WorldAccess.filler(chunk,x,y,z)));
            WorldAccess.set(chunk,x,y,z,y==205?"Rock_Stone":"Empty",NO_UPDATE_STATE|NO_SEND_PARTICLES|NO_UPDATE_NEIGHBOR_CONNECTIONS|FORCE_CHANGED);
        }
        var directory=Files.createTempDirectory("sm-native-tube-backend-");var anomalies=new AnomalyService(directory);anomalies.naturalGeneration=false;
        try(var research=new ResearchService(directory);var machines=new MachineService(directory,new StrangeMatterConfig(),research,anomalies);
            var factory=new FactoryService(machines,research);var tubes=new TubeService(directory,factory,e->CompletableFuture.completedFuture(null));
            var viewer=NativePlayerFixture.create(world,"NativeTubeBackend",new Vector3d(10.5,207,18.5))){
            machines.setFactory(factory);var endpoints=new TubeEndpoints(factory);
            try{
                for(var entry:Map.of(SOURCE,"Furniture_Crude_Chest_Small",LARGE,"Furniture_Crude_Chest_Large",FURNACE,"Bench_Furnace").entrySet()){
                    var p=entry.getKey();String id=entry.getValue();
                    require(DefaultAssetMap.DEFAULT_PACK_KEY.equals(BlockType.getAssetMap().getAssetPack(id)),"Actual installed vanilla asset pack is trusted: "+id+" = "+BlockType.getAssetMap().getAssetPack(id));
                    require(WorldAccess.set(chunk,p.x(),p.y(),p.z(),id),"Place actual stock asset "+id);
                }
                for(int x=6;x<=13;x++)placeTube(world,tubes,viewer,new Position(x,206,20));
                for(int z=21;z<=23;z++)placeTube(world,tubes,viewer,new Position(10,206,z));
                var source=Objects.requireNonNull(endpoints.resolve(world,SOURCE,"storage",true));
                var large=Objects.requireNonNull(endpoints.resolve(world,new Position(14,206,20),"storage",true));
                var input=Objects.requireNonNull(endpoints.resolve(world,FURNACE,"input",true));
                var fuel=Objects.requireNonNull(endpoints.resolve(world,FURNACE,"fuel",true));
                var output=Objects.requireNonNull(endpoints.resolve(world,FURNACE,"output",true));
                require(source.port().inventory().getCapacity()==18&&large.port().inventory().getCapacity()==36,"Stock small and large chest capacities are actual native holders");
                require(endpoints.resolve(world,LARGE,"storage",false).ref()==large.ref(),"Actual stock large chest filler and origin share the exact native inventory identity");
                require(!factory.mayAccess(world,SOURCE.vector(),viewer.owner().getUuid()),"Factory access deliberately does not claim vanilla containers");
                for(var endpoint:List.of(source,large,input,fuel,output))require(!endpoint.suppliedByProvider()&&endpoints.mayAccess(endpoint,viewer.owner().getUuid()),"Native endpoint access bypasses only unrelated factory ownership: "+endpoint.port().section());
                require(tubes.current(world,START).component().face(1).mode==TubeConfiguration.Mode.INSERT&&!tubes.current(world,START).component().configured(1),"Unedited chest face automatically sends into storage");
                require(tubes.current(world,FEED).component().face(4).mode==TubeConfiguration.Mode.INSERT&&tubes.current(world,FEED).component().face(4).section.equals("input"),"Unedited furnace face automatically selects Ingredients");

                var ore=new ItemStack("Ore_Copper",5).withMetadata("TubeNative",Codec.STRING,"real vanilla route");
                set(tubes,viewer,START,1,TubeConfiguration.Mode.EXTRACT,"storage",0,0);
                set(tubes,viewer,END,0,TubeConfiguration.Mode.INSERT,"storage",9,5);
                source.port().inventory().setItemStackForSlot((short)0,ore,false);
                advance(world,tubes,()->source.port().inventory().isEmpty()&&total(large)==5,"Vanilla source reaches longer high priority destination");
                require(total(input)==0&&TubeStacks.encode(large.port().inventory().getItemStack((short)0)).equals(TubeStacks.encode(ore)),"Priority wins over nearer native furnace and preserves the full item stack");
                source.port().inventory().setItemStackForSlot((short)0,ore,false);
                advance(world,tubes,()->source.port().inventory().isEmpty()&&total(input)==5,"Full higher priority destination routes next batch into default furnace Ingredients");
                require(total(large)==5,"Default SEND still respects stock limits and destination priority");

                set(tubes,viewer,END,0,TubeConfiguration.Mode.INSERT,"storage",9,0);
                set(tubes,viewer,FEED,4,TubeConfiguration.Mode.EXTRACT,"output",0,0);
                output.port().inventory().setItemStackForSlot((short)0,new ItemStack("Ingredient_Bar_Copper",3),false);
                advance(world,tubes,()->output.port().inventory().isEmpty()&&total(large)==8,"Native furnace output can actually be extracted into vanilla large chest");
                set(tubes,viewer,END,0,TubeConfiguration.Mode.OFF,"storage",9,0);
                set(tubes,viewer,FEED,4,TubeConfiguration.Mode.INSERT,"fuel",0,0);
                require(fuel.port().inventory().getCapacity()==1,"Stock furnace has a single fuel slot for repeated deliveries");
                source.port().inventory().setItemStackForSlot((short)0,new ItemStack("Ingredient_Charcoal",15),false);
                advance(world,tubes,()->source.port().inventory().isEmpty()&&total(fuel)==15,"Three consecutive charcoal batches merge into the occupied native furnace fuel slot");
                require(fuel.port().inventory().removeItemStackFromSlot((short)0,5).succeeded(),"Consume part of the fuel while leaving its slot occupied");
                source.port().inventory().setItemStackForSlot((short)0,new ItemStack("Ingredient_Charcoal",10),false);
                advance(world,tubes,()->source.port().inventory().isEmpty()&&total(fuel)==20,"Additional deliveries replenish partially consumed fuel without emptying the slot or reopening the tube");
                set(tubes,viewer,START,1,TubeConfiguration.Mode.INSERT,"storage",0,0);
                set(tubes,viewer,FEED,4,TubeConfiguration.Mode.EXTRACT,"input",0,0);
                advance(world,tubes,()->input.port().inventory().isEmpty()&&total(source)==5,"Native furnace ingredient section can be explicitly extracted into vanilla small chest");
                require(tubes.current(world,END).component().face(0).mode==TubeConfiguration.Mode.OFF&&tubes.current(world,FEED).component().face(4).mode==TubeConfiguration.Mode.EXTRACT,"Repeated discovery never overwrites explicit OFF or TAKE");

                var factoryPosition=new Position(24,206,22);WorldAccess.set(chunk,24,206,22,"SM_Flux_Furnace");
                var state=machines.register(world,factoryPosition.vector(),"SM_Flux_Furnace");factory.claim(world,state,viewer.owner().getUuid());
                var owned=Objects.requireNonNull(endpoints.resolve(world,factoryPosition,"input",true));
                require(owned.suppliedByProvider()&&endpoints.mayAccess(owned,viewer.owner().getUuid())&&!endpoints.mayAccess(owned,UUID.randomUUID()),"Provider-supplied factory endpoint still enforces its real owner permission");
                verifyCarrier(viewer);
                System.out.println("NATIVE_TUBE_BACKEND PASS: official stock pack identities, real small/large chests and all furnace sections with FactoryService provider, prioritized actual transfers, metadata, default input/storage and explicit migration, native centered item packet");
            }finally{tubes.stopWorld(world);for(var p:List.of(SOURCE,LARGE,FURNACE))for(var e:endpoints.all(world,p,false))e.port().inventory().clear();}
        }finally{for(var c:saved){var p=c.p;WorldAccess.set(chunk,p.x(),p.y(),p.z(),c.block,BlockType.getAssetMap().getAsset(c.block),c.rotation,c.filler,NO_UPDATE_STATE|NO_SEND_PARTICLES|NO_UPDATE_NEIGHBOR_CONNECTIONS|FORCE_CHANGED);}}
    }
    private static void placeTube(World world,TubeService service,NativePlayerFixture owner,Position p){var chunk=WorldAccess.loaded(world,0);WorldAccess.set(chunk,p.x(),p.y(),p.z(),TubeService.ID);service.placed(world,p.vector(),owner.owner().getUuid());}
    private static void set(TubeService service,NativePlayerFixture owner,Position p,int face,TubeConfiguration.Mode mode,String section,int priority,int limit){var c=new TubeConfiguration();c.mode=mode;c.section=section;c.priority=priority;c.fillUpTo=limit;require(service.configure(owner.owner(),owner.store(),owner.world(),service.current(owner.world(),p).component().id(),p,face,c),"Apply in-range native tube endpoint settings "+p);}
    private static int total(Endpoint e){int count=0;for(short i=0;i<e.port().inventory().getCapacity();i++){var s=e.port().inventory().getItemStack(i);if(!ItemStack.isEmpty(s))count+=s.getQuantity();}return count;}
    private static void advance(World world,TubeService service,BooleanSupplier condition,String reason)throws Exception{
        for(int i=0;i<400;i++){service.tick(world,.2);if(condition.getAsBoolean()&&service.activeFlights(world)==0&&service.ledger().pendingCount()==0)return;Thread.sleep(2);}
        throw new AssertionError(reason+": "+service.status(world,START)+" / "+service.status(world,FEED)+", pending="+service.ledger().pendingCount()+", flights="+service.activeFlights(world));
    }
    private static TubeComponent legacy(TubeComponent component){var bson=TubeComponent.CODEC.encode(component,new ExtraInfo()).asDocument();var json=com.google.gson.JsonParser.parseString(bson.getString("Settings").getValue()).getAsJsonObject();json.remove("configuredFaces");return TubeComponent.CODEC.decode(new BsonDocument("Settings",new BsonString(json.toString())),new ExtraInfo());}
    private static void verifyMigration(){
        var unedited=new TubeComponent();unedited.owner(UUID.randomUUID());var old=legacy(unedited);
        require(!old.configured(0)&&old.defaultInsert(0,"input"),"Only provably unedited legacy tube can receive new defaults");
        var explicit=new TubeComponent();explicit.owner(UUID.randomUUID());explicit.configure(0,new TubeConfiguration());
        var oldExplicit=legacy(explicit);require(oldExplicit.configured(0)&&!oldExplicit.defaultInsert(0,"input")&&oldExplicit.face(0).mode==TubeConfiguration.Mode.OFF,"Legacy explicit OFF is protected even though old saves lacked per-face markers");
        var take=new TubeConfiguration();take.mode=TubeConfiguration.Mode.EXTRACT;take.priority=7;take.samples[0]=TubeStacks.encode(new ItemStack("Ore_Copper",1));explicit.configure(1,take);
        var saved=TubeComponent.CODEC.decode(TubeComponent.CODEC.encode(explicit,new ExtraInfo()),new ExtraInfo());
        require(!saved.defaultInsert(0,"input")&&!saved.defaultInsert(1,"input")&&saved.face(1).priority==7&&saved.face(1).samples[0]!=null,"Native component roundtrip preserves explicit mode, priority and filters");
        require(saved.defaultInsert(2,"storage")&&saved.defaultInsert(2,"input")&&!saved.configured(2),"Only untouched face can adapt its default from chest storage to furnace ingredients");
    }
    private static void verifyCarrier(NativePlayerFixture viewer)throws Exception{
        var world=viewer.world();var store=viewer.store();store.tick(.01f);
        for(String item:List.of("Ingredient_Bar_Copper","Ore_Copper","SM_Graviton_Hammer")){
            var stack=new ItemStack(item,7).withMetadata("CarrierProof",Codec.STRING,"original remains exact");var original=TubeStacks.encode(stack);
            var layout=TubeCarrier.prepare(stack).get(10,TimeUnit.SECONDS);var center=new Vector3d(10.5,207.5,20.5);var ref=TubeCarrier.spawn(world,stack,center);require(ref!=null,"Native display layout prepared "+item);
            try{
                int id=store.getComponent(ref,NetworkId.getComponentType()).getId();viewer.packets().packets.clear();store.tick(.05f);
                var display=store.getComponent(ref,ItemComponent.getComponentType()).getItemStack();
                var expected=TubeStacks.quantity(stack,1);expected.setOverrideDroppedItemAnimation(true);
                require(display.getOverrideDroppedItemAnimation()&&TubeStacks.encode(stack).equals(original)&&TubeStacks.encode(display).equals(TubeStacks.encode(expected)),"Only display copy disables floating drop animation "+item);
                require(layout.center(store.getComponent(ref,TransformComponent.getComponentType()).getPosition()).distance(center)<1e-8,"Authored static model centre starts on tube route "+item);
                var next=new Vector3d(center).add(.5,.25,-.25);TubeCarrier.move(ref,next);
                require(layout.center(store.getComponent(ref,TransformComponent.getComponentType()).getPosition()).distance(next)<1e-8,"Moving display retains the same model-centre correction "+item);
                var packet=viewer.packets().ofType(EntityUpdates.class).stream().filter(p->p.updates!=null).flatMap(p->Arrays.stream(p.updates)).filter(u->u.networkId==id&&u.updates!=null).flatMap(u->Arrays.stream(u.updates)).filter(ItemUpdate.class::isInstance).map(ItemUpdate.class::cast).findFirst().orElseThrow(()->new AssertionError("Native tracker did not send centered item "+item));
                require(packet.item.overrideDroppedItemAnimation&&Math.abs(packet.entityScale-layout.scale())<1e-7,"Actual native item tracker transmits static display override and bounded scale "+item);
                require(store.getComponent(ref,PreventPickup.getComponentType())!=null&&store.getComponent(ref,PreventItemMerging.getComponentType())!=null,"Centered carrier stays presentation-only "+item);
            }finally{TubeCarrier.remove(ref);}
        }
    }
}
