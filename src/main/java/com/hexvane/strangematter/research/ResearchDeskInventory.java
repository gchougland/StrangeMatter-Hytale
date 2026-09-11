package com.hexvane.strangematter.research;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterActionType;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockComponentSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import org.bson.BsonDocument;
import org.joml.Vector3i;

/** The research note lives in a native saved block slot, including while no page is open. */
final class ResearchDeskInventory {
    static boolean current(World world,Vector3i pos,ItemContainer expected){
        var component=BlockModule.getComponent(ItemContainerBlock.getComponentType(),world,pos.x,pos.y,pos.z);
        return component!=null&&component.getItemContainer()==expected;
    }
    static ItemContainer open(World world,Vector3i pos,ResearchService research){
        var store=world.getChunkStore().getStore();store.assertThread();
        var section=world.getChunkStore().getChunkSectionReferenceAtBlock(pos.x,pos.y,pos.z);
        if(section==null||!section.isValid())return null;
        var ref=BlockModule.getBlockEntity(world,pos.x,pos.y,pos.z);
        if(ref==null){
            if(store.getComponent(section,BlockComponentSection.getComponentType())==null)return null;
            var holder=ChunkStore.REGISTRY.newHolder();
            holder.addComponent(BlockModule.BlockStateInfo.getComponentType(),new BlockModule.BlockStateInfo(ChunkUtil.indexBlock(pos.x,pos.y,pos.z),section));
            holder.addComponent(ItemContainerBlock.getComponentType(),ItemContainerBlock.CODEC.decode(BsonDocument.parse("{\"Capacity\":1}"),new ExtraInfo()));
            ref=store.addEntity(holder,AddReason.LOAD);
        }
        var component=store.getComponent(ref,ItemContainerBlock.getComponentType());
        if(component==null){component=ItemContainerBlock.CODEC.decode(BsonDocument.parse("{\"Capacity\":1}"),new ExtraInfo());store.addComponent(ref,ItemContainerBlock.getComponentType(),component);}
        var inventory=component.getItemContainer();
        inventory.setSlotFilter(FilterActionType.ADD,(short)0,(a,c,s,stack)->research.noteNode(stack)!=null);
        return inventory;
    }
    private ResearchDeskInventory(){}
}
