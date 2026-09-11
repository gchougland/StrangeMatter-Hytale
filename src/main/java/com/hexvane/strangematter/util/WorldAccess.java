package com.hexvane.strangematter.util;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockOperations;
import com.hypixel.hytale.server.core.universe.world.chunk.ChunkFlag;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.GetChunkFlags;
import org.joml.Vector3ic;
import java.util.concurrent.CompletableFuture;

/** Current component access. Loaded reads never start a load or block another thread. */
public final class WorldAccess {
    private WorldAccess() {}
    public static WorldChunk inMemory(World world,long index) {
        world.getChunkStore().getStore().assertThread();
        var ref=world.getChunkStore().getChunkReference(index);
        return ref==null||!ref.isValid()?null:world.getChunkStore().getStore().getComponent(ref,WorldChunk.getComponentType());
    }
    public static WorldChunk loaded(World world,long index) {
        var chunk=inMemory(world,index);
        return chunk!=null&&chunk.is(ChunkFlag.TICKING)?chunk:null;
    }
    public static CompletableFuture<WorldChunk> load(World world,long index) {
        return world.getChunkStore().getChunkReferenceAsync(index,GetChunkFlags.SET_TICKING)
                .thenApplyAsync(ref->ref==null||!ref.isValid()?null:world.getChunkStore().getStore().getComponent(ref,WorldChunk.getComponentType()),world);
    }
    public static BlockChunk column(WorldChunk chunk) {
        var ref=chunk.getReference();
        if(ref==null||!ref.isValid())throw new IllegalStateException("Use the generation event's BlockChunk component before publication");
        return ref.getStore().getComponent(ref,BlockChunk.getComponentType());
    }
    private static Ref<ChunkStore> sectionRef(WorldChunk chunk,int y) {
        return chunk.getWorld().getChunkStore().getChunkSectionReference(chunk.getX(),Math.floorDiv(y,ChunkUtil.SIZE),chunk.getZ());
    }
    public static BlockSection section(WorldChunk chunk,int y) {
        var ref=sectionRef(chunk,y);
        return ref==null||!ref.isValid()?null:ref.getStore().getComponent(ref,BlockSection.getComponentType());
    }
    public static int block(WorldChunk chunk,int x,int y,int z) {
        var section=section(chunk,y);return section==null?0:section.get(x,y,z);
    }
    public static BlockType blockType(WorldChunk chunk,int x,int y,int z){return BlockType.getAssetMap().getAsset(block(chunk,x,y,z));}
    public static BlockType blockType(WorldChunk chunk,Vector3ic p){return blockType(chunk,p.x(),p.y(),p.z());}
    public static int rotation(WorldChunk chunk,int x,int y,int z){var section=section(chunk,y);return section==null?0:section.getRotationIndex(x,y,z);}
    public static int filler(WorldChunk chunk,int x,int y,int z){var section=section(chunk,y);return section==null?0:section.getFiller(x,y,z);}
    public static int fluid(WorldChunk chunk,int x,int y,int z) {
        var ref=sectionRef(chunk,y);
        if(ref==null||!ref.isValid())return 0;
        var fluid=ref.getStore().getComponent(ref,FluidSection.getComponentType());
        return fluid==null?0:fluid.getFluidId(x,y,z);
    }
    public static boolean set(WorldChunk chunk,int x,int y,int z,String id){return set(chunk,x,y,z,id,0);}
    public static boolean set(WorldChunk chunk,int x,int y,int z,String id,int settings){
        int index=BlockType.getAssetMap().getIndex(id);if(index<0)throw new IllegalArgumentException("Unknown block: "+id);
        return set(chunk,x,y,z,index,BlockType.getAssetMap().getAsset(index),0,0,settings);
    }
    public static boolean set(WorldChunk chunk,int x,int y,int z,int id,BlockType type,int rotation,int filler,int settings){
        var ref=sectionRef(chunk,y);if(ref==null||!ref.isValid())return false;
        int wx=(chunk.getX()<<ChunkUtil.BITS)+(x&ChunkUtil.SIZE_MASK),wz=(chunk.getZ()<<ChunkUtil.BITS)+(z&ChunkUtil.SIZE_MASK);
        return BlockOperations.setBlock(chunk.getWorld().getChunkStore(),ref,wx,y,wz,id,type,rotation,filler,settings);
    }
    public static void state(WorldChunk chunk,Vector3ic p,BlockType type,String state){
        var ref=sectionRef(chunk,p.y());if(ref!=null&&ref.isValid())
            BlockOperations.setBlockInteractionState(chunk.getWorld().getChunkStore(),ref,p.x(),p.y(),p.z(),type,state,false);
    }
}
