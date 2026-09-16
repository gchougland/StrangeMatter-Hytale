package com.hexvane.strangematter.automation;

import com.hexvane.strangematter.StrangeMatterConfig;
import com.hexvane.strangematter.anomaly.AnomalyService;
import com.hexvane.strangematter.equipment.GadgetEnergy;
import com.hexvane.strangematter.equipment.NativePlayerFixture;
import com.hexvane.strangematter.machine.*;
import com.hexvane.strangematter.research.ResearchService;
import com.hexvane.strangematter.util.StackData;
import com.hexvane.strangematter.util.WorldAccess;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.joml.Vector3d;
import org.joml.Vector3i;
import static com.hypixel.hytale.server.core.universe.world.SetBlockSettings.*;

/** Physical intact-machine handoff with actual native section saves and disk player acknowledgements. */
public final class NativeFactoryPackingWorldVerification {
    private static Context active;
    public static final class BreakBridge extends EntityEventSystem<EntityStore,BreakBlockEvent> {
        public BreakBridge(){super(BreakBlockEvent.class);}
        @Override public Query<EntityStore> getQuery(){return Player.getComponentType();}
        @Override public void handle(int index,ArchetypeChunk<EntityStore> chunk,Store<EntityStore> store,CommandBuffer<EntityStore> buffer,BreakBlockEvent event){
            var c=active;if(c==null||c.world!=store.getExternalData().getWorld()||!chunk.getReferenceTo(index).equals(c.player.ref()))return;
            c.breakChecks++;new MachineEvents.Break(c.machines).handle(index,chunk,store,buffer,event);if(c.deny)event.setCancelled(true);
        }
    }
    private record Cell(int x,int y,int z,int id,int rotation,int filler){}
    private static final class Context {
        final String machineId;final boolean storage;final World world;final ResearchService research;final Path directory;final MachineService machines;final FactoryService factory;final NativePlayerFixture player;final ItemStack gadget;
        final List<Cell> cells=new ArrayList<>();final CompletableFuture<Void> result=new CompletableFuture<>();final long deadline=System.nanoTime()+20_000_000_000L;
        MachineState original,placed;ItemStack parcel;String token;boolean deny;int breakChecks,phase;
        Context(World world,ResearchService research,String machineId)throws Exception{
            this.machineId=machineId;this.storage=EnergyStoragePorts.storage(machineId);this.world=world;this.research=research;directory=Files.createTempDirectory("sm-native-packing-world-");
            machines=new MachineService(directory,new StrangeMatterConfig(),research,new AnomalyService(directory));factory=new FactoryService(machines,research);machines.setFactory(factory);
            player=NativePlayerFixture.create(world,"ChargingParcelOwner",new Vector3d(25.5,241,18.5));
            gadget=GadgetEnergy.withCharge(new ItemStack("SM_Field_Scanner",1).withMetadata("PackingFixture",com.hypixel.hytale.codec.Codec.STRING,"exact-metadata"),234);
        }
        void start(){
            var chunk=WorldAccess.loaded(world,ChunkUtil.indexChunk(0,0));require(chunk!=null,"Packing fixture uses a loaded chunk");
            for(int x=23;x<=28;x++)for(int y=239;y<=243;y++)for(int z=15;z<=20;z++){
                cells.add(new Cell(x,y,z,WorldAccess.block(chunk,x,y,z),WorldAccess.rotation(chunk,x,y,z),WorldAccess.filler(chunk,x,y,z)));
                WorldAccess.set(chunk,x,y,z,y==239?"Rock_Stone":"Empty",NO_UPDATE_STATE|NO_SEND_PARTICLES|NO_UPDATE_NEIGHBOR_CONNECTIONS|FORCE_CHANGED);
            }
            WorldAccess.set(chunk,24,240,16,machineId);original=machines.register(world,new Vector3i(24,240,16),machineId);factory.claim(world,original,player.owner().getUuid());
            var c=factory.component(world,original);original.enabled=false;original.energy=321;original.fuelTicks=storage?0:73;
            if(storage){EnergyStoragePorts.set(original,EnergyStoragePorts.Face.FRONT,EnergyStoragePorts.Mode.DISABLED);EnergyStoragePorts.set(original,EnergyStoragePorts.Face.LEFT,EnergyStoragePorts.Mode.OUTPUT);}
            else{c.charging.setItemStackForSlot((short)0,gadget,false);c.input.setItemStackForSlot((short)0,new ItemStack("Ingredient_Charcoal",3),false);}
            deny=true;require(factory.pack(world,original,player.owner()).contains("cannot be picked up"),"A later native protection listener vetoes Pack Up without transferring contents");
            require(!factory.blocked(world,original)&&(storage?original.energy==321:c.charging.getItemStack((short)0).equals(gadget)),"Denied Pack Up leaves its dock intact");
            deny=false;require(factory.pack(world,original,player.owner()).startsWith("Packing"),"Authorized Pack Up begins its durable handoff");
            require(breakChecks==2&&factory.blocked(world,original),"Pack Up honors native permission events then freezes the source inventory");
            schedule();
        }
        void schedule(){CompletableFuture.delayedExecutor(20,TimeUnit.MILLISECONDS).execute(()->{try{world.execute(this::step);}catch(Throwable failure){result.completeExceptionally(failure);}});}
        void step(){
            if(result.isDone())return;
            try{
                require(System.nanoTime()<deadline,"Physical parcel handoff completes without blocking a world tick");
                // During placement, observe the restored snapshot before the ordinary burner
                // loader is allowed to move its preserved input fuel into the burn queue.
                if(phase==0)machines.tick(world,.05);else factory.recoverParcels(world);
                if(phase==0){
                    var r=new FactoryParcelLedger(directory).all().stream().filter(v->v.phase==FactoryParcelLedger.Phase.AVAILABLE).findFirst().orElse(null);
                    if(r==null){schedule();return;}token=r.token;
                    short found=-1;for(short i=0;i<player.inventory().getCapacity();i++)if(token.equals(FactoryParcelLedger.token(player.inventory().getItemStack(i)))){found=i;break;}
                    require(found>=0&&"Empty".equals(world.getBlockType(24,240,16).getId())&&machines.get(world,original.block())==null,"Acknowledged parcel exists only after source geometry and registry are removed");
                    parcel=player.inventory().getItemStack(found);require(factory.canPlaceParcel(parcel),"Only the acknowledged parcel can be placed");
                    require(factory.reserveParcelPlacement(parcel)&&!factory.reserveParcelPlacement(parcel),"Concurrent native placement requests cannot claim the same parcel");factory.releaseParcelPlacement(parcel);
                    player.inventory().setItemStackForSlot(found,ItemStack.EMPTY,false);
                    var chunk=WorldAccess.loaded(world,ChunkUtil.indexChunk(0,0));WorldAccess.set(chunk,26,240,16,machineId);placed=machines.register(world,new Vector3i(26,240,16),machineId);
                    FactoryPickup.placed(machines,world,placed,parcel,player.owner().getUuid());
                    require(factory.blocked(world,placed)&&!factory.canPlaceParcel(parcel),"A placed parcel cannot be reused while its native holder is being saved");phase=1;
                }else{
                    var r=new FactoryParcelLedger(directory).get(token);if(r.phase!=FactoryParcelLedger.Phase.PLACED){schedule();return;}
                    var c=factory.component(world,placed);
                    require(placed.energy==321&&!placed.enabled,"Physical Pack Up and placement retain reserve and disabled state");
                    if(storage)require(EnergyStoragePorts.mode(placed,EnergyStoragePorts.Face.FRONT)==EnergyStoragePorts.Mode.DISABLED&&EnergyStoragePorts.mode(placed,EnergyStoragePorts.Face.LEFT)==EnergyStoragePorts.Mode.OUTPUT,"Physical storage packing retains all local face settings");
                    else{
                        require(placed.fuelTicks==73&&c.input.getItemStack((short)0).getQuantity()==3,"Physical burner packing retains burning fuel and native fuel inventory");
                        require(StackData.encode(c.charging.getItemStack((short)0)).equals(StackData.encode(gadget)),"Physical Pack Up and placement preserve the exact inserted gadget metadata and charge");
                    }
                    require(!factory.blocked(world,placed)&&!factory.canPlaceParcel(parcel),"Native placement checkpoint unlocks the machine and permanently retires the parcel");
                    try(var restarted=new MachineService(directory,new StrangeMatterConfig(),research,new AnomalyService(directory));var restoredFactory=new FactoryService(restarted,research)){
                        restarted.setFactory(restoredFactory);var restoredState=restarted.get(world,placed.block());require(restoredState!=null&&!restoredFactory.canPlaceParcel(parcel),"Restart retains the placed machine and refuses stale parcel replay");
                        var restoredComponent=restoredFactory.component(world,restoredState);
                        if(storage)require(restoredState.energy==321&&EnergyStoragePorts.mode(restoredState,EnergyStoragePorts.Face.FRONT)==EnergyStoragePorts.Mode.DISABLED&&EnergyStoragePorts.mode(restoredState,EnergyStoragePorts.Face.LEFT)==EnergyStoragePorts.Mode.OUTPUT,"Restart reattaches the storage reserve and native face configuration");
                        else require(restoredComponent.charging.getItemStack((short)0).equals(gadget),"Restart reattaches the exact saved dock holder");
                    }
                    require(factory.pack(world,placed,player.owner()).startsWith("Packing"),"A placed machine can start a later independent pickup");
                    factory.interruptParcelForEnvironmentBreak(world,placed);
                    var refunds=factory.remove(world,placed,c);
                    require((storage?refunds.isEmpty():refunds.size()==2&&refunds.stream().filter(s->s.getItemId().equals(gadget.getItemId())).count()==1),"Environmental ownership transition refunds the dock only once through normal native contents");
                    var interrupted=new FactoryParcelLedger(directory);require(interrupted.all().stream().anyMatch(r2->r2.phase==FactoryParcelLedger.Phase.BROKEN)&&interrupted.pending().isEmpty(),"Environmentally destroyed pickup is permanently retired and cannot return a second machine parcel after restart");
                    machines.removed(world,placed.block());factory.recoverParcels(world);
                    System.out.println((storage?"NATIVE_ENERGY_STORAGE_PACKING_WORLD_VERIFICATION_PASSED: ":"NATIVE_FACTORY_PACKING_WORLD_VERIFICATION_PASSED: ")+" native protection veto, real section checkpoint/removal, acknowledged inventory parcel, exact occupied placement, retained fuel/reserve/control and stale-token restart rejection.");finish(null);return;
                }
                schedule();
            }catch(Throwable failure){finish(failure);}
        }
        void finish(Throwable failure){
            try{var chunk=WorldAccess.loaded(world,ChunkUtil.indexChunk(0,0));for(var cell:cells)WorldAccess.set(chunk,cell.x,cell.y,cell.z,cell.id,com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType.getAssetMap().getAsset(cell.id),cell.rotation,cell.filler,NO_UPDATE_STATE|NO_SEND_PARTICLES|NO_UPDATE_NEIGHBOR_CONNECTIONS|FORCE_CHANGED);player.close();factory.close();machines.close();}
            catch(Throwable cleanup){if(failure==null)failure=cleanup;else failure.addSuppressed(cleanup);}finally{active=null;}
            if(failure==null)result.complete(null);else result.completeExceptionally(failure);
        }
    }
    public static CompletableFuture<Void> verifyAsync(World world,ResearchService research){
        return verifyAsync(world,research,"SM_Resonant_Burner").thenComposeAsync(unused->verifyAsync(world,research,EnergyStoragePorts.ID),world);
    }
    private static CompletableFuture<Void> verifyAsync(World world,ResearchService research,String machineId){
        Context context=null;try{context=new Context(world,research,machineId);active=context;context.start();return context.result;}
        catch(Throwable failure){if(context!=null){context.finish(failure);return context.result;}return CompletableFuture.failedFuture(failure);}
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
