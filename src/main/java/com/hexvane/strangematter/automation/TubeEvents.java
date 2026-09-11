package com.hexvane.strangematter.automation;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.*;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.component.dependency.*;
import com.hypixel.hytale.builtin.crafting.component.BenchBlock;
import com.hypixel.hytale.builtin.crafting.component.ProcessingBenchBlock;
import com.hypixel.hytale.builtin.crafting.system.BenchSystems;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.event.events.ecs.*;
import com.hypixel.hytale.server.core.modules.block.BlockReplaceEvent;
import com.hypixel.hytale.server.core.universe.world.storage.*;

/** Block native access while a paired recovery is unresolved; preserve identities through native chest merges. */
public final class TubeEvents {
    static void register(TubeService service,IComponentRegistry<ChunkStore> chunks,IComponentRegistry<EntityStore> entities){
        chunks.registerSystem(new Replace());chunks.registerSystem(new ReplaceHolder());
        chunks.registerSystem(new FurnaceRecovery(service));
        entities.registerSystem(new Use(service));entities.registerSystem(new Break(service));entities.registerSystem(new Damage(service));
        entities.registerSystem(new Place(service));entities.registerSystem(new EnvironmentBreak(service));
    }
    public static final class Place extends EntityEventSystem<EntityStore,PlaceBlockEvent>{
        private final TubeService service;public Place(TubeService service){super(PlaceBlockEvent.class);this.service=service;}
        @Override public Query<EntityStore> getQuery(){return com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType();}
        @Override public void handle(int i,ArchetypeChunk<EntityStore> chunk,Store<EntityStore> store,CommandBuffer<EntityStore> commands,PlaceBlockEvent event){
            if(event.isCancelled())return;var world=store.getExternalData().getWorld();var p=new org.joml.Vector3i(event.getTargetBlock());
            if(service.isRecoveryBlocked(world,p)){event.setCancelled(true);return;}
            var stack=event.getItemInHand();if(stack==null||!TubeService.ID.equals(stack.getItemId()))return;
            var owner=chunk.getComponent(i,com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType()).getUuid();
            world.execute(()->{if(TubeService.tube(world,new TubeEndpoints.Position(p)))service.placed(world,p,owner);});
        }
    }
    public static final class Use extends EntityEventSystem<EntityStore,UseBlockEvent.Pre>{
        private final TubeService service;public Use(TubeService service){super(UseBlockEvent.Pre.class);this.service=service;}
        @Override public Query<EntityStore> getQuery(){return Query.any();}
        @Override public void handle(int i,ArchetypeChunk<EntityStore> chunk,Store<EntityStore> store,CommandBuffer<EntityStore> commands,UseBlockEvent.Pre event){if(service.isRecoveryBlocked(store.getExternalData().getWorld(),event.getTargetBlock()))event.setCancelled(true);}
    }
    public static final class Break extends EntityEventSystem<EntityStore,BreakBlockEvent>{
        private final TubeService service;public Break(TubeService service){super(BreakBlockEvent.class);this.service=service;}
        @Override public Query<EntityStore> getQuery(){return Query.any();}
        @Override public void handle(int i,ArchetypeChunk<EntityStore> chunk,Store<EntityStore> store,CommandBuffer<EntityStore> commands,BreakBlockEvent event){
            var world=store.getExternalData().getWorld();var p=new org.joml.Vector3i(event.getTargetBlock());
            if(service.isRecoveryBlocked(world,p)){event.setCancelled(true);return;}if(event.isCancelled())return;
            var origin=TubeEndpoints.origin(world,new TubeEndpoints.Position(p));if(origin==null)return;
            var old=TubeEndpoints.block(world,origin);
            world.execute(()->{var current=TubeEndpoints.block(world,origin);if(old==null?!TubeService.tube(world,origin):current!=old)service.removed(world,origin.vector());});
        }
    }
    public static final class Damage extends EntityEventSystem<EntityStore,DamageBlockEvent>{
        private final TubeService service;public Damage(TubeService service){super(DamageBlockEvent.class);this.service=service;}
        @Override public Query<EntityStore> getQuery(){return Query.any();}
        @Override public void handle(int i,ArchetypeChunk<EntityStore> chunk,Store<EntityStore> store,CommandBuffer<EntityStore> commands,DamageBlockEvent event){if(service.isRecoveryBlocked(store.getExternalData().getWorld(),event.getTargetBlock()))event.setCancelled(true);}
    }
    public static final class EnvironmentBreak extends WorldEventSystem<EntityStore,EnvironmentBreakBlockEvent>{
        private final TubeService service;public EnvironmentBreak(TubeService service){super(EnvironmentBreakBlockEvent.class);this.service=service;}
        @Override public void handle(Store<EntityStore> store,CommandBuffer<EntityStore> commands,EnvironmentBreakBlockEvent event){
            service.removed(store.getExternalData().getWorld(),new org.joml.Vector3i(event.getTargetBlock()));
        }
    }
    static void inherit(TubeEndpointReceipts old,Holder<ChunkStore> next){if(old==null)return;var target=next.getComponent(TubeEndpointReceipts.type);
        if(target==null)next.addComponent(TubeEndpointReceipts.type,old.clone());else target.merge(old);}
    public static final class Replace extends EntityEventSystem<ChunkStore,BlockReplaceEvent>{
        public Replace(){super(BlockReplaceEvent.class);}
        @Override public Query<ChunkStore> getQuery(){return TubeEndpointReceipts.type;}
        @Override public void handle(int i,ArchetypeChunk<ChunkStore> chunk,Store<ChunkStore> store,CommandBuffer<ChunkStore> commands,BlockReplaceEvent event){inherit(chunk.getComponent(i,TubeEndpointReceipts.type),event.getNewEntity());}
    }
    public static final class ReplaceHolder extends EntityHolderEventSystem<ChunkStore,BlockReplaceEvent>{
        public ReplaceHolder(){super(BlockReplaceEvent.class);}
        @Override public Query<ChunkStore> getQuery(){return TubeEndpointReceipts.type;}
        @Override public void handle(Holder<ChunkStore> holder,Store<ChunkStore> store,CommandBuffer<ChunkStore> commands,BlockReplaceEvent event){inherit(holder.getComponent(TubeEndpointReceipts.type),event.getNewEntity());}
    }
    /** Runs before native furnace consumption, including the first tick after loading a pending endpoint. */
    public static final class FurnaceRecovery extends EntityTickingSystem<ChunkStore>{
        private final TubeService service;public FurnaceRecovery(TubeService service){this.service=service;}
        @Override public Query<ChunkStore> getQuery(){return Query.and(TubeEndpointReceipts.type,ProcessingBenchBlock.getComponentType(),BenchBlock.getComponentType(),BlockModule.BlockStateInfo.getComponentType());}
        @Override public java.util.Set<Dependency<ChunkStore>> getDependencies(){return java.util.Set.of(new SystemDependency<>(Order.BEFORE,BenchSystems.ProcessingBenchTick.class));}
        @Override public void tick(float dt,int index,ArchetypeChunk<ChunkStore> chunk,Store<ChunkStore> store,CommandBuffer<ChunkStore> commands){
            var info=chunk.getComponent(index,BlockModule.BlockStateInfo.getComponentType());var p=new org.joml.Vector3i();if(!info.fillWorldPos(p))return;
            var receipt=chunk.getComponent(index,TubeEndpointReceipts.type);var processor=chunk.getComponent(index,ProcessingBenchBlock.getComponentType());var bench=chunk.getComponent(index,BenchBlock.getComponentType());
            if(processor.getProcessingBench()==null)return;
            if(service.isRecoveryBlocked(store.getExternalData().getWorld(),p)){
                if(receipt.processingActive()==null){receipt.processingActive(processor.isActive());info.markNeedsSaving();}
                processor.setActive(false,bench,info);
            }else if(receipt.processingActive()!=null){boolean resume=receipt.processingActive();receipt.processingActive(null);processor.setActive(resume,bench,info);info.markNeedsSaving();}
        }
    }
    private TubeEvents(){}
}
