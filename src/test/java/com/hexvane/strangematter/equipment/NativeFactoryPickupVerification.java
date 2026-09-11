package com.hexvane.strangematter.equipment;

import com.hexvane.strangematter.StrangeMatterConfig;
import com.hexvane.strangematter.anomaly.AnomalyService;
import com.hexvane.strangematter.automation.*;
import com.hexvane.strangematter.machine.*;
import com.hexvane.strangematter.research.ResearchService;
import com.hexvane.strangematter.util.WorldAccess;
import com.hexvane.strangematter.util.StackData;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.interaction.BlockHarvestUtils;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.nio.file.Files;
import java.util.*;
import org.bson.BsonDocument;
import org.joml.Vector3d;
import org.joml.Vector3i;
import static com.hypixel.hytale.server.core.universe.world.SetBlockSettings.*;

/** Native gathering and successful/cancelled removal, without custom duplicate drop spawning. */
public final class NativeFactoryPickupVerification {
    private static Context active;
    private static final class Context {
        final World world; final MachineService machines; final Ref<EntityStore> player;
        BreakBlockEvent event; boolean deny;
        Context(World world, MachineService machines, Ref<EntityStore> player) {
            this.world=world;this.machines=machines;this.player=player;
        }
    }
    /** Harness-only bridge invokes the exact production listener, then models a later protection listener. */
    public static final class BreakBridge extends EntityEventSystem<EntityStore,BreakBlockEvent> {
        public BreakBridge(){super(BreakBlockEvent.class);}
        @Override public Query<EntityStore> getQuery(){return Player.getComponentType();}
        @Override public void handle(int index,ArchetypeChunk<EntityStore> chunk,Store<EntityStore> store,
                                     CommandBuffer<EntityStore> buffer,BreakBlockEvent event){
            var context=active;
            if(context==null||store.getExternalData().getWorld()!=context.world||!chunk.getReferenceTo(index).equals(context.player))return;
            context.event=event;
            new MachineEvents.Break(context.machines).handle(index,chunk,store,buffer,event);
            if(context.deny)event.setCancelled(true);
        }
    }
    private record Cell(int x,int y,int z,int id,int rotation,int filler){}
    public static void verify(World world)throws Exception {
        world.debugAssertInTickingThread();
        verifyDropAssets();
        var directory=Files.createTempDirectory("sm-native-factory-pickup-");
        var cells=new ArrayList<Cell>();var chunk=WorldAccess.inMemory(world,ChunkUtil.indexChunk(0,0));
        require(chunk!=null,"Pickup fixture uses an already loaded native chunk");
        var anomalies=new AnomalyService(directory);
        try(var research=new ResearchService(directory);
            var machines=new MachineService(directory,new StrangeMatterConfig(),research,anomalies);
            var factories=new FactoryService(machines,research);
            var first=NativePlayerFixture.create(world,"PickupOriginalOwner",new Vector3d(23.5,241,20.5));
            var second=NativePlayerFixture.create(world,"PickupNewOwner",new Vector3d(27.5,241,20.5))) {
            machines.setFactory(factories);
            for(int x=22;x<=29;x++)for(int y=239;y<=242;y++)for(int z=20;z<=24;z++){
                cells.add(new Cell(x,y,z,WorldAccess.block(chunk,x,y,z),WorldAccess.rotation(chunk,x,y,z),WorldAccess.filler(chunk,x,y,z)));
                WorldAccess.set(chunk,x,y,z,y==239?"Rock_Stone":"Empty",NO_UPDATE_STATE|NO_SEND_PARTICLES|NO_UPDATE_NEIGHBOR_CONNECTIONS|FORCE_CHANGED);
            }
            active=new Context(world,machines,first.ref());
            for(String id:List.of("SM_Flux_Furnace","SM_Pattern_Assembler")){
                var origin=new Vector3i(23,240,22);int tier=id.equals("SM_Flux_Furnace")?2:3;
                require(WorldAccess.set(chunk,origin.x,origin.y,origin.z,id),"Place native factory for dismantling");
                var state=machines.register(world,origin,id);factories.initializeTier(world,state,tier);factories.claim(world,state,first.owner().getUuid());
                var component=factories.component(world,state);component.data.pattern="must not travel";state.energy=725;
                MachineWorkEffects.sync(world,state);
                require(world.getBlockType(origin.x,origin.y,origin.z).getCurrentInteractionState().equals("Tier"+tier),"Restored tier selects its native metadata drop state immediately");
                component.input.setItemStackForSlot((short)0,new ItemStack("Ore_Copper",1),false);
                breakBlock(world,first,origin,0);
                require(active.event!=null&&active.event.isCancelled()&&id.equals(MachineService.baseId(world.getBlockType(origin.x,origin.y,origin.z)))&&drops(world).isEmpty(),
                        "A machine with contents is protected and emits no upgraded or base item");
                FactoryInventory.clear(component.input);active.deny=true;
                breakBlock(world,first,origin,0);
                FactoryPickup.completeRemoval(machines,world,origin,state,component,active.event);
                require(active.event.isCancelled()&&machines.get(world,origin)==state&&drops(world).isEmpty(),"A later protection listener cancels native drops and deferred cleanup");
                active.deny=false;breakBlock(world,first,origin,0);
                require(!active.event.isCancelled()&&world.getBlockType(origin.x,origin.y,origin.z)==BlockType.EMPTY,"Actual native successful break removes the upgraded block");
                var spawned=drops(world);require(spawned.size()==1,"Native break emits exactly one machine entity, without an additional base drop");
                var dropped=first.store().getComponent(spawned.getFirst(),ItemComponent.getComponentType()).getItemStack();
                require(dropped.getItemId().equals(id)&&dropped.getQuantity()==1&&FactoryPickup.tier(dropped)==tier,"Actual spawned item retains the upgraded machine tier");
                require(StackData.metadata(dropped).size()==1,"No owner, stored power or recipe is copied into the dropped item");
                var roundTrip=ItemStack.CODEC.decode(ItemStack.CODEC.encode(dropped,new ExtraInfo()),new ExtraInfo());
                require(roundTrip.equals(dropped),"Native saved item codec preserves the tier through inventory persistence");
                first.store().removeEntity(spawned.getFirst(),RemoveReason.REMOVE);
                // Reproduce a rapid same-ID replacement before either deferred event callback.
                require(WorldAccess.set(chunk,origin.x,origin.y,origin.z,id),"Re-place the normal base item block");
                require(FactoryPickup.component(world,origin)!=component,"Replacement creates a distinct native block component");
                FactoryPickup.completeRemoval(machines,world,origin,state,component,active.event);
                require(machines.get(world,origin)==null,"Successful removal retires old ownership even with a same-ID replacement already present");
                var replacement=machines.register(world,origin,id);
                FactoryPickup.placed(machines,world,replacement,roundTrip,second.owner().getUuid());
                var restored=factories.component(world,replacement);
                require(replacement!=state&&restored!=component&&restored.tier()==tier&&replacement.factoryTier==tier,"Replacement creates a fresh factory while retaining only tier");
                require(restored.data.owner.equals(second.owner().getUuid().toString())&&restored.data.pattern.isEmpty()&&replacement.energy==0,
                        "Another player owns the re-placed machine with no old pattern or stored power");
                require(!id.equals("SM_Flux_Furnace")||restored.input.getCapacity()==10,"Re-placed upgraded furnace has the real enlarged native input container");
                FactoryPickup.completeRemoval(machines,world,origin,state,component,active.event);
                require(machines.get(world,origin)==replacement,"A stale original removal callback cannot remove its replacement");
                breakBlock(world,first,origin,0);
                for(var ref:drops(world))first.store().removeEntity(ref,RemoveReason.REMOVE);
                var delayed=active.event;
                require(WorldAccess.set(chunk,origin.x,origin.y,origin.z,id),"Place another same-ID native replacement before cleanup");
                require(machines.register(world,origin,id)==replacement,"Reproduce a same-frame discovery reusing the registry state");
                var alreadyRegistered=factories.component(world,replacement);
                FactoryPickup.placed(machines,world,replacement,roundTrip,first.owner().getUuid());
                FactoryPickup.completeRemoval(machines,world,origin,replacement,restored,delayed);
                require(machines.get(world,origin)==replacement&&factories.registeredComponent(world,replacement)==alreadyRegistered
                        &&alreadyRegistered.data.owner.equals(first.owner().getUuid().toString()),
                        "Deferred old cleanup preserves an already registered replacement and its fresh owner");
                // The native no-drop flag must also suppress the tier item, without a custom refund.
                breakBlock(world,first,origin,NO_DROP_ITEMS);
                require(drops(world).isEmpty(),"Native no-drop removal never creates an upgraded item via a second path");
                FactoryPickup.completeRemoval(machines,world,origin,replacement,alreadyRegistered,active.event);
            }
            staleLegacyPage(world,machines,first);
        }finally{
            active=null;
            for(var ref:drops(world))if(ref.isValid())world.getEntityStore().getStore().removeEntity(ref,RemoveReason.REMOVE);
            for(var cell:cells)WorldAccess.set(chunk,cell.x,cell.y,cell.z,cell.id,BlockType.getAssetMap().getAsset(cell.id),cell.rotation,cell.filler,
                    NO_UPDATE_STATE|NO_SEND_PARTICLES|NO_UPDATE_NEIGHBOR_CONNECTIONS|FORCE_CHANGED);
        }
        System.out.println("NATIVE_FACTORY_PICKUP_VERIFICATION_PASSED: native tier drops, one actual item, blocked and later cancelled breaks, no-drop behavior, metadata persistence, fresh owner and stale callback safety.");
    }
    private static void verifyDropAssets(){
        for(String id:List.of("SM_Flux_Furnace","SM_Pattern_Assembler")){
            var base=BlockType.getAssetMap().getAsset(id);require(base!=null,"Factory base asset is loaded");
            int maximum=id.equals("SM_Flux_Furnace")?2:3;
            for(int tier=2;tier<=maximum;tier++)for(String suffix:List.of("","Working")){
                var type=base.getBlockForState("Tier"+tier+suffix);require(type!=null,"Tier state exists");
                var gathering=type.getGathering();var breaking=gathering.getBreaking();
                var stacks=BlockHarvestUtils.getDrops(type,breaking.getQuantity(),breaking.getItemId(),breaking.getDropListId());
                require(stacks.size()==1&&stacks.getFirst().getQuantity()==1&&stacks.getFirst().getItemId().equals(id)&&FactoryPickup.tier(stacks.getFirst())==tier,
                        "Native drop list returns exactly one base item with matching tier for "+type.getId());
                var physics=gathering.getPhysics();
                var physical=BlockHarvestUtils.getDrops(type,1,physics.getItemId(),physics.getDropListId());
                require(physical.equals(stacks),"Environmental gathering retains the same tier");
            }
        }
        require(FactoryPickup.tier(new ItemStack("SM_Flux_Furnace",1,BsonDocument.parse("{SMFactoryTier:999}")))==2,"Excessive tier is clamped to the machine maximum");
        require(FactoryPickup.tier(new ItemStack("SM_Pattern_Assembler",1,BsonDocument.parse("{SMFactoryTier:'invalid'}")))==1,"Invalid tier metadata falls back safely");
        require(FactoryPickup.tier(new ItemStack("SM_Resonant_Separator",1,BsonDocument.parse("{SMFactoryTier:3}")))==1,"A non-upgradeable machine cannot import tiers");
    }
    private static void staleLegacyPage(World world,MachineService machines,NativePlayerFixture player)throws Exception{
        var pos=new Vector3i(23,240,22);world.setBlock(pos.x,pos.y,pos.z,"SM_Resonant_Burner");
        var state=machines.register(world,pos,"SM_Resonant_Burner");var factory=machines.factory();
        factory.claim(world,state,player.owner().getUuid());var component=factory.component(world,state);
        var original=component.input;original.setItemStackForSlot((short)0,new ItemStack("SM_Resonite_Ingot",1),false);
        MachinePage.open(player.owner(),machines,state,player.store());
        var page=(MachinePage)player.player().getPageManager().getCustomPage();
        var field=MachinePage.class.getDeclaredField("inventoryPanel");field.setAccessible(true);
        var panel=(com.hexvane.strangematter.ui.MachineInventoryPanel)field.get(page);int id=panel.windows()[0].getId();
        component.input=original.clone();
        com.hypixel.hytale.server.core.inventory.InventoryUtils.moveItem(player.ref(),id,0,1,
                com.hypixel.hytale.server.core.inventory.InventoryComponent.STORAGE_SECTION_ID,34,player.store());
        require(original.getItemStack((short)0).getQuantity()==1&&component.input.getItemStack((short)0).getQuantity()==1
                &&player.player().getWindowManager().getWindow(id)==null,
                "Legacy machine page rejects a captured input after its real container is replaced");
        player.player().getPageManager().setPage(player.ref(),player.store(),com.hypixel.hytale.protocol.packets.interface_.Page.None,false);
        FactoryInventory.clear(component.input);factory.remove(world,state);world.setBlock(pos.x,pos.y,pos.z,"Empty");machines.removed(world,pos);
    }
    private static void breakBlock(World world,NativePlayerFixture player,Vector3i origin,int settings){
        active.event=null;
        var type=world.getBlockType(origin.x,origin.y,origin.z);var breaking=type.getGathering().getBreaking();
        var section=world.getChunkStore().getChunkSectionReferenceAtBlock(origin.x,origin.y,origin.z);
        BlockHarvestUtils.performBlockBreak(new Vector3i(origin),type,null,breaking.getQuantity(),breaking.getItemId(),breaking.getDropListId(),
                settings|NO_SEND_AUDIO|NO_SEND_PARTICLES,player.ref(),section,player.store(),world.getChunkStore().getStore());
        require(active.event!=null,"Native break event reached the registered production-listener bridge");
    }
    private static List<Ref<EntityStore>> drops(World world){
        var result=new ArrayList<Ref<EntityStore>>();
        world.getEntityStore().getStore().forEachChunk(Query.and(ItemComponent.getComponentType(),TransformComponent.getComponentType()),(chunk,buffer)->{
            for(int i=0;i<chunk.size();i++){
                var p=chunk.getComponent(i,TransformComponent.getComponentType()).getPosition();
                var item=chunk.getComponent(i,ItemComponent.getComponentType()).getItemStack();
                if(p.x>=22&&p.x<=29&&p.y>=239&&p.y<=243&&p.z>=20&&p.z<=25&&item!=null
                        &&(item.getItemId().equals("SM_Flux_Furnace")||item.getItemId().equals("SM_Pattern_Assembler")))result.add(chunk.getReferenceTo(i));
            }
        });return result;
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
