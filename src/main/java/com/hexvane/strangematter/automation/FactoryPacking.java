package com.hexvane.strangematter.automation;

import com.hexvane.strangematter.machine.MachineService;
import com.hexvane.strangematter.machine.MachineState;
import com.hexvane.strangematter.util.PlayerInventoryPersistence;
import com.hexvane.strangematter.util.WorldAccess;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static com.hypixel.hytale.server.core.universe.world.SetBlockSettings.*;

/** Pack-up handoff: journal, native block checkpoint, removal checkpoint, acknowledged player save. */
final class FactoryPacking {
    private static final System.Logger LOG=System.getLogger(FactoryPacking.class.getName());
    private final FactoryService factory;
    private final FactoryParcelLedger ledger;
    private final Map<String,Pending> waiting=new java.util.concurrent.ConcurrentHashMap<>();
    private final ThreadLocal<MachineState> validating=new ThreadLocal<>();
    private final Set<String> placementRequests=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final class Pending {String stage;CompletableFuture<Void> save;long retry;Pending(String stage){this.stage=stage;}}
    FactoryPacking(FactoryService factory){this.factory=factory;ledger=new FactoryParcelLedger(factory.machines.dataDirectory());}
    boolean validating(MachineState state){return state!=null&&validating.get()==state;}
    boolean blocked(World world,MachineState state){return ledger.pending().stream().anyMatch(r->r.world.equals(world.getName())&&r.position().equals(state.block())&&(r.phase==FactoryParcelLedger.Phase.PACKING||r.phase==FactoryParcelLedger.Phase.REMOVING||r.phase==FactoryParcelLedger.Phase.PLACING));}
    boolean available(ItemStack stack){String token=FactoryParcelLedger.token(stack);if(token==null)return true;var r=ledger.get(token);return r!=null&&r.phase==FactoryParcelLedger.Phase.AVAILABLE&&r.itemId.equals(stack.getItemId())&&stack.getQuantity()==1;}
    boolean reservePlacement(ItemStack stack){String token=FactoryParcelLedger.token(stack);return token==null||available(stack)&&placementRequests.add(token);}
    void releasePlacement(ItemStack stack){String token=FactoryParcelLedger.token(stack);if(token!=null)placementRequests.remove(token);}
    void environmentBreak(World world,MachineState state){
        var c=FactoryPickup.component(world,state.block());if(c==null)return;
        for(var r:ledger.pending()){
            if(!r.world.equals(world.getName())||!r.position().equals(state.block()))continue;
            String identity=r.phase==FactoryParcelLedger.Phase.PLACING?r.targetIdentity:r.sourceIdentity;
            if(!Objects.equals(identity,c.data.identity))continue;
            if(r.phase==FactoryParcelLedger.Phase.PLACING&&!r.token.equals(c.data.parcel))factory.restoreParcel(world,state,r.payload,r.token,r.targetIdentity,r.owner);
            // Native environmental gathering cannot be cancelled and owns the ordinary shell.
            // Retire the parcel before its actual native contents are refunded by that event.
            ledger.phase(r,FactoryParcelLedger.Phase.BROKEN);waiting.remove(r.token);
        }
    }
    String pack(World world,MachineState state,PlayerRef owner){
        if(!FactoryService.packableMachine(state.id)||!factory.machines.canUse(world.getEntityStore().getStore(),owner,state))return "Machine unavailable.";
        var component=factory.component(world,state);
        if(component==null||!factory.access(component,owner.getUuid())||factory.blocked(world,state))return "Machine access or item transfer is busy.";
        if(component.busy())return "Finish the active job first.";
        if(world.isSavingLocked()||!world.getWorldConfig().canSaveChunks())return "Enable world saving before packing this machine.";
        var event=new BreakBlockEvent(null,state.block(),world.getBlockType(state.x,state.y,state.z));
        validating.set(state);try{world.getEntityStore().getStore().invoke(owner.getReference(),event);}finally{validating.remove();}
        if(event.isCancelled())return "This machine cannot be picked up here.";
        factory.changed(world,state);component.data.energy=state.energy;
        var receipt=new FactoryParcelLedger.Receipt();receipt.itemId=state.id;receipt.owner=owner.getUuid();receipt.world=world.getName();receipt.x=state.x;receipt.y=state.y;receipt.z=state.z;receipt.enabled=state.enabled;receipt.sourceIdentity=component.data.identity;
        receipt.payload=FactoryComponent.CODEC.encode(component,new ExtraInfo()).asDocument().toJson();
        ledger.create(receipt);
        return "Packing machine and contents safely. Keep an inventory slot free.";
    }
    boolean place(World world,MachineState state,ItemStack item,UUID owner){
        String token=FactoryParcelLedger.token(item);if(token==null)return false;
        var r=ledger.get(token);if(r==null||r.phase!=FactoryParcelLedger.Phase.AVAILABLE||!r.itemId.equals(state.id))return true;
        var c=factory.component(world,state);if(c==null)return true;
        var newOwner=owner==null?r.owner:owner;
        if(!ledger.placing(r,world.getName(),state.block(),c.data.identity,newOwner))return true;
        factory.restoreParcel(world,state,r.payload,r.token,r.targetIdentity,newOwner);
        state.enabled=r.enabled;factory.machines.markDirty();return true;
    }
    void tick(World world){
        world.debugAssertInTickingThread();
        for(var r:ledger.pending()){
            if(!r.world.equals(world.getName()))continue;
            try{
                if(r.phase==FactoryParcelLedger.Phase.PACKING||r.phase==FactoryParcelLedger.Phase.REMOVING)remove(world,r);
                else if(r.phase==FactoryParcelLedger.Phase.PLACING)finishPlacement(world,r);
            }catch(RuntimeException failure){LOG.log(System.Logger.Level.ERROR,"Factory parcel remains recoverable after failed block handoff",failure);}
        }
        for(var owner:world.getPlayerRefs())for(var r:ledger.pending())if(r.phase==FactoryParcelLedger.Phase.RETURNING&&r.owner.equals(owner.getUuid())){
            try{returnItem(world,owner,r);}catch(RuntimeException failure){LOG.log(System.Logger.Level.ERROR,"Factory parcel remains recoverable after failed item return",failure);}
        }
    }
    private void remove(World world,FactoryParcelLedger.Receipt r){
        var chunk=WorldAccess.loaded(world,ChunkUtil.indexChunkFromBlock(r.x,r.z));if(chunk==null)return;
        var state=factory.machines.get(world,r.position());var c=FactoryPickup.component(world,r.position());
        boolean original=c!=null&&r.sourceIdentity.equals(c.data.identity)&&r.itemId.equals(MachineService.baseId(world.getBlockType(r.x,r.y,r.z)));
        if(r.phase==FactoryParcelLedger.Phase.PACKING){
            if(original&&!ack(r,"baseline",()->TubeCheckpoints.saveAt(world,r.position())))return;
            ledger.phase(r,FactoryParcelLedger.Phase.REMOVING);
        }
        if(original){
            // The journal owns every contained stack now. No ordinary machine/content drop is emitted.
            if(!WorldAccess.set(chunk,r.x,r.y,r.z,"Empty",NO_DROP_ITEMS|NO_SEND_PARTICLES|PERFORM_BLOCK_UPDATE))return;
            if(state!=null){factory.remove(world,state,c);factory.machines.removed(world,r.position());factory.machines.save();}
        }
        // A later replacement is never removed. Wait for the original site's durable geometry.
        if(!ack(r,"removed",()->TubeCheckpoints.saveAt(world,r.position())))return;
        ledger.phase(r,FactoryParcelLedger.Phase.RETURNING);
    }
    private void finishPlacement(World world,FactoryParcelLedger.Receipt r){
        if(WorldAccess.loaded(world,ChunkUtil.indexChunkFromBlock(r.x,r.z))==null)return;
        var c=FactoryPickup.component(world,r.position());
        if(c==null||!r.targetIdentity.equals(c.data.identity)||!r.itemId.equals(MachineService.baseId(world.getBlockType(r.x,r.y,r.z)))){
            // Placement geometry was not saved before shutdown, or another block took its place.
            // Retain the one payload and return its parcel rather than overwriting that cell.
            ledger.phase(r,FactoryParcelLedger.Phase.RETURNING);waiting.remove(r.token);return;
        }
        var state=factory.machines.register(world,r.position(),r.itemId);
        if(!r.token.equals(c.data.parcel))factory.restoreParcel(world,state,r.payload,r.token,r.targetIdentity,r.owner);
        state.enabled=r.enabled;
        if(!ack(r,"placed",()->TubeCheckpoints.saveAt(world,r.position())))return;
        ledger.phase(r,FactoryParcelLedger.Phase.PLACED);factory.machines.markDirty();factory.machines.save();
    }
    private void returnItem(World world,PlayerRef owner,FactoryParcelLedger.Receipt r){
        var ref=owner.getReference();if(ref==null||!ref.isValid()||ref.getStore()!=world.getEntityStore().getStore())return;
        var p=waiting.get(r.token);
        if(p!=null&&p.stage.equals("inventory")&&p.save!=null&&p.save.isDone()&&!p.save.isCompletedExceptionally()){
            ledger.phase(r,FactoryParcelLedger.Phase.AVAILABLE);waiting.remove(r.token);owner.sendMessage(Message.raw("Packed machine and its contents returned to your inventory."));return;
        }
        if(p!=null&&p.save!=null&&!p.save.isDone())return;
        var inventory=InventoryComponent.getCombined(ref.getStore(),ref,InventoryComponent.HOTBAR_STORAGE_BACKPACK);if(inventory==null)return;
        boolean found=false;for(short slot=0;slot<inventory.getCapacity();slot++)if(r.token.equals(FactoryParcelLedger.token(inventory.getItemStack(slot)))){found=true;break;}
        if(!found&&(!inventory.canAddItemStack(r.item(),true,false)||!inventory.addItemStack(r.item(),true,false,false).succeeded()))return;
        ack(r,"inventory",()->PlayerInventoryPersistence.save(world,owner));
    }
    private boolean ack(FactoryParcelLedger.Receipt r,String stage,java.util.function.Supplier<CompletableFuture<Void>> save){
        var pending=waiting.computeIfAbsent(r.token,key->new Pending(stage));
        if(!pending.stage.equals(stage)){waiting.remove(r.token);pending=new Pending(stage);waiting.put(r.token,pending);}
        if(pending.save==null){if(System.nanoTime()<pending.retry)return false;pending.save=save.get();return false;}
        if(!pending.save.isDone())return false;
        if(pending.save.isCompletedExceptionally()){pending.save=null;pending.retry=System.nanoTime()+5_000_000_000L;return false;}
        waiting.remove(r.token);return true;
    }
}
