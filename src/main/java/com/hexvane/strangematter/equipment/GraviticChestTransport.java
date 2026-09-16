package com.hexvane.strangematter.equipment;

import com.hexvane.strangematter.util.WorldAccess;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blockhitbox.BlockBoundingBoxes;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.entities.player.windows.WindowManager;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterType;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockComponentSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.util.FillerBlockUtil;
import org.bson.BsonDocument;
import org.joml.Vector3i;
import java.util.*;

/** Native container snapshots and intact detach/attach; no item drops or lossy stack conversion. */
final class GraviticChestTransport {
    static boolean chest(BlockType type){return type!=null&&type.getId().toLowerCase(Locale.ROOT).contains("chest")&&type.getBlockEntity()!=null
            &&type.getBlockEntity().getComponent(ItemContainerBlock.getComponentType())!=null&&type.getBench()==null;}
    static Holder<ChunkStore> snapshot(World world,Vector3i pos){
        var store=world.getChunkStore().getStore();var ref=BlockModule.getBlockEntity(world,pos.x,pos.y,pos.z);
        if(ref!=null&&ref.isValid())return store.copyEntity(ref);
        var section=section(world,pos);var holder=section==null?null:section.getBlockHolder(ChunkUtil.indexBlock(pos.x,pos.y,pos.z));return holder==null?null:holder.clone();
    }
    static ItemContainerBlock container(World world,Vector3i pos){
        var ref=BlockModule.getBlockEntity(world,pos.x,pos.y,pos.z);if(ref!=null&&ref.isValid())return ref.getStore().getComponent(ref,ItemContainerBlock.getComponentType());
        var section=section(world,pos);var holder=section==null?null:section.getBlockHolder(ChunkUtil.indexBlock(pos.x,pos.y,pos.z));return holder==null?null:holder.getComponent(ItemContainerBlock.getComponentType());
    }
    static GraviticChestMarker marker(World world,Vector3i pos){
        if(GraviticChestMarker.getComponentType()==null)return null;
        var ref=BlockModule.getBlockEntity(world,pos.x,pos.y,pos.z);if(ref!=null&&ref.isValid())return ref.getStore().getComponent(ref,GraviticChestMarker.getComponentType());
        var section=section(world,pos);var holder=section==null?null:section.getBlockHolder(ChunkUtil.indexBlock(pos.x,pos.y,pos.z));return holder==null?null:holder.getComponent(GraviticChestMarker.getComponentType());
    }
    static String encode(Holder<ChunkStore> holder){return ChunkStore.REGISTRY.serialize(holder).toJson(org.bson.json.JsonWriterSettings.builder().outputMode(org.bson.json.JsonMode.EXTENDED).build());}
    static Holder<ChunkStore> decode(String payload){return ChunkStore.REGISTRY.deserialize(BsonDocument.parse(payload));}
    static void close(World world,Vector3i pos){var container=container(world,pos);if(container!=null)WindowManager.closeAndRemoveAll(container.getWindows());}
    static boolean detach(World world,Vector3i pos){
        var section=section(world,pos);if(section==null)return false;var container=container(world,pos);close(world,pos);
        var taken=com.hypixel.hytale.server.core.modules.block.BlockEntity.takeBlockEntity(world.getChunkStore().getStore(),section,pos.x,pos.y,pos.z);
        if(taken==null)return false;
        // A stale closed window or cached port must never retain a second usable inventory.
        if(container!=null){container.getItemContainer().clear();container.getItemContainer().setGlobalFilter(FilterType.DENY_ALL);}
        return true;
    }
    static boolean attach(World world,Vector3i pos,BlockType type,int rotation,String payload,UUID journal,UUID receipt){
        var store=world.getChunkStore().getStore();var sectionRef=world.getChunkStore().getChunkSectionReferenceAtBlock(pos.x,pos.y,pos.z);var section=section(world,pos);
        if(sectionRef==null||section==null)return false;var holder=decode(payload);if(holder==null)return false;
        var container=holder.getComponent(ItemContainerBlock.getComponentType());
        if(container==null||container.getItemContainer().getCapacity()!=type.getBlockEntity().getComponent(ItemContainerBlock.getComponentType()).getCapacity())return false;
        container.getItemContainer().setGlobalFilter(FilterType.DENY_ALL);holder.putComponent(GraviticChestMarker.getComponentType(),new GraviticChestMarker(journal,receipt));
        // Remove the fresh, empty native block holder intact before installing the saved holder.
        // This also prevents native connected-container replacement from merging any inventories.
        if(container(world,pos)!=null&&!detach(world,pos))return false;
        com.hypixel.hytale.server.core.modules.block.BlockEntity.setBlockEntity(store,sectionRef,section,pos.x,pos.y,pos.z,type,rotation,holder);
        var ref=BlockModule.getBlockEntity(world,pos.x,pos.y,pos.z);if(ref!=null){var info=store.getComponent(ref,BlockModule.BlockStateInfo.getComponentType());if(info!=null)info.markNeedsSaving();}
        return marker(world,pos)!=null;
    }
    static List<GraviticBlockJournal.Cell> offsets(BlockType type,int rotation){
        var boxes=BlockBoundingBoxes.getAssetMap().getAsset(type.getHitboxTypeIndex());if(boxes==null)return List.of();var result=new ArrayList<GraviticBlockJournal.Cell>();
        FillerBlockUtil.forEachFillerBlock(boxes.get(rotation),(x,y,z)->result.add(new GraviticBlockJournal.Cell(x,y,z)));return List.copyOf(result);
    }
    static Box collider(BlockType type,int rotation){
        var boxes=BlockBoundingBoxes.getAssetMap().getAsset(type.getHitboxTypeIndex());var box=boxes.get(rotation).getBoundingBox();
        return new Box(box.min.x-.49,box.min.y-.49,box.min.z-.49,box.max.x-.51,box.max.y-.51,box.max.z-.51);
    }
    private static BlockComponentSection section(World world,Vector3i pos){var ref=world.getChunkStore().getChunkSectionReferenceAtBlock(pos.x,pos.y,pos.z);return ref==null?null:ref.getStore().getComponent(ref,BlockComponentSection.getComponentType());}
}
