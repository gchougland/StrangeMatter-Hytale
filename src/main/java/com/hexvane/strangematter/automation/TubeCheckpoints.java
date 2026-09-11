package com.hexvane.strangematter.automation;

import com.hexvane.strangematter.util.WorldAccess;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockComponentSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.ChunkSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.IChunkSaver;
import java.util.concurrent.CompletableFuture;

/** Bounded targeted snapshots. Never calls the native blocking whole-world flush. */
final class TubeCheckpoints {
    static CompletableFuture<Void> save(TubeEndpoints.Endpoint endpoint){
        var world=endpoint.world();var store=world.getChunkStore().getStore();store.assertThread();
        var info=store.getComponent(endpoint.ref(),BlockModule.BlockStateInfo.getComponentType());
        if(info==null||!info.getSectionRef().isValid())return null;
        if(world.isSavingLocked()||!world.getWorldConfig().canSaveChunks())return null;
        var queue=store.getResource(ChunkStore.SAVE_RESOURCE);
        var saver=world.getChunkStore().getSaver();
        if(saver instanceof IChunkSaver.Cubic cubic){
            var section=store.getComponent(info.getSectionRef(),BlockComponentSection.getComponentType());
            var geometry=store.getComponent(info.getSectionRef(),ChunkSection.getComponentType());
            if(section==null||geometry==null||section.isSaving()||geometry.isSaving()||!queue.tryReserveInFlight())return null;
            if(!queue.tryReserveInFlight()){queue.releaseInFlight();return null;}
            section.setSaving(true);geometry.setSaving(true);info.markNeedsSaving();var p=endpoint.position();
            int x=Math.floorDiv(p.x(),32),y=Math.floorDiv(p.y(),32),z=Math.floorDiv(p.z(),32);
            boolean blockDispatched=false,geometryDispatched=false;
            try{
                var blocks=cubic.saveBlockComponentSection(x,y,z,store,section,world,queue::releaseInFlight);blockDispatched=true;
                section.consumeNeedsSaving();queue.pushSavingFuture(blocks);
                var savedBlocks=blocks.whenCompleteAsync((v,error)->{if(error!=null)section.markNeedsSaving();section.setSaving(false);},world);
                var terrain=cubic.saveSection(x,y,z,store,info.getSectionRef(),world,queue::releaseInFlight);geometryDispatched=true;
                geometry.consumeNeedsSaving();queue.pushSavingFuture(terrain);
                var savedGeometry=terrain.whenCompleteAsync((v,error)->{if(error!=null)geometry.markNeedsSaving();geometry.setSaving(false);},world);
                return CompletableFuture.allOf(savedBlocks,savedGeometry);
            }finally{
                if(!blockDispatched){section.setSaving(false);section.markNeedsSaving();queue.releaseInFlight();}
                if(!geometryDispatched){geometry.setSaving(false);geometry.markNeedsSaving();queue.releaseInFlight();}
            }
        }
        var p=endpoint.position();var column=WorldAccess.loaded(world,ChunkUtil.indexChunkFromBlock(p.x(),p.z()));
        if(column==null||column.isSaving()||!queue.tryReserveInFlight())return null;
        // Legacy savers serialize the entire column holder; use the same exclusion flag as native saves.
        column.setSaving(true);info.markNeedsSaving();
        boolean dispatched=false;
        try{var saved=saver.saveChunkColumn(column.getX(),column.getZ(),store,column.getReference(),world,queue::releaseInFlight);dispatched=true;queue.pushSavingFuture(saved);
            return saved.whenCompleteAsync((v,error)->{if(error!=null)info.markNeedsSaving();column.setSaving(false);},world);}
        finally{if(!dispatched){column.setSaving(false);info.markNeedsSaving();queue.releaseInFlight();}}
    }
    private TubeCheckpoints(){}
}
