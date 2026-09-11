package com.hexvane.strangematter.machine;

import com.hexvane.strangematter.automation.FactoryPickup;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.component.system.WorldEventSystem;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.*;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3i;
import java.util.ArrayList;
import java.util.List;

public final class MachineEvents {
    public static final class Place extends EntityEventSystem<EntityStore,PlaceBlockEvent> {
        private final MachineService service;
        public Place(MachineService service){super(PlaceBlockEvent.class);this.service=service;}
        @Override public Query<EntityStore> getQuery(){return Player.getComponentType();}
        @Override public void handle(int index,ArchetypeChunk<EntityStore> chunk,Store<EntityStore> store,CommandBuffer<EntityStore> buffer,PlaceBlockEvent event){
            if(event.isCancelled()||event.getItemInHand()==null)return;
            var id=event.getItemInHand().getItemId();if(!MachineService.IDS.contains(id))return;
            var world=store.getExternalData().getWorld();var pos=new Vector3i(event.getTargetBlock());
            var player=chunk.getComponent(index,PlayerRef.getComponentType());var owner=player==null?null:player.getUuid();
            var placedItem=event.getItemInHand();
            world.execute(()->{if(event.isCancelled())return;var type=world.getBlockType(pos.x,pos.y,pos.z);if(type!=null&&id.equals(MachineService.baseId(type))){var state=service.register(world,pos,id);FactoryPickup.placed(service,world,state,placedItem,owner);service.save();}});
        }
    }
    public static final class Break extends EntityEventSystem<EntityStore,BreakBlockEvent> {
        private final MachineService service;
        public Break(MachineService service){super(BreakBlockEvent.class);this.service=service;}
        @Override public Query<EntityStore> getQuery(){return Player.getComponentType();}
        @Override public void handle(int index,ArchetypeChunk<EntityStore> chunk,Store<EntityStore> store,CommandBuffer<EntityStore> buffer,BreakBlockEvent event){
            if(event.isCancelled()||!MachineService.IDS.contains(MachineService.baseId(event.getBlockType())))return;
            var world=store.getExternalData().getWorld();var pos=MachineService.origin(world,event.getTargetBlock());var state=service.get(world,pos);
            if(state!=null&&service.hasContents(world,state)) {
                event.setCancelled(true);var player=chunk.getComponent(index,PlayerRef.getComponentType());
                if(player!=null)player.sendMessage(Message.raw("Empty this machine and finish or stop its current job before picking it up."));
            }else {var component=FactoryPickup.component(world,pos);world.execute(()->FactoryPickup.completeRemoval(service,world,pos,state,component,event));}
        }
    }
    /** Environmental breaks have no actor and cannot be cancelled by the native event. */
    public static final class EnvironmentBreak extends WorldEventSystem<EntityStore,EnvironmentBreakBlockEvent> {
        private final MachineService service;
        public EnvironmentBreak(MachineService service){super(EnvironmentBreakBlockEvent.class);this.service=service;}
        @Override public void handle(Store<EntityStore> store,CommandBuffer<EntityStore> buffer,EnvironmentBreakBlockEvent event){
            if(!MachineService.IDS.contains(MachineService.baseId(event.getBlockType())))return;
            var world=store.getExternalData().getWorld();var pos=MachineService.origin(world,event.getTargetBlock());var state=service.get(world,pos);
            if(state==null)return;
            List<ItemStack> refunds=new ArrayList<>();
            if(service.factory()!=null)for(var stack:service.factory().remove(world,state))add(refunds,stack);
            if(state.outputQuantity>0&&!state.output.isEmpty())add(refunds,new ItemStack(state.output,state.outputQuantity));
            if(!state.recipe.isEmpty()){
                if(state.reservedInputs!=null&&!state.reservedInputs.isEmpty()){
                    for(var input:state.reservedInputs)add(refunds,input.toItemStack());
                }else{
                    // A filled capsule cannot be reconstructed without its unique anomaly token.
                    HytaleLogger.getLogger().atWarning().log("Legacy machine reservation has no input ledger at %s; refusing to invent replacement capsule identities",state.key());
                }
            }
            if(state.fuelQueue!=null)for(var charge:state.fuelQueue)add(refunds,charge.toItemStack());
            if(state.recoveredFuel!=null)for(var charge:state.recoveredFuel)add(refunds,charge.toItemStack());
            // Native gathering still drops the ordinary machine block; only contents are added here.
            // Remove its state immediately so a repeated event cannot refund the same reservation twice.
            var holders=ItemComponent.generateItemDrops(buffer,refunds,state.center(),Rotation3f.IDENTITY);
            service.removed(world,pos);
            buffer.addEntities(holders,AddReason.SPAWN);
            world.execute(service::save);
        }
        private static void add(List<ItemStack> drops,ItemStack item){
            int left=item.getQuantity(),max=Math.max(1,item.getItem().getMaxStack());
            while(left>0){int amount=Math.min(left,max);drops.add(item.withQuantity(amount));left-=amount;}
        }
    }
}
