package com.hexvane.strangematter.worldgen;

import com.hexvane.strangematter.util.WorldAccess;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.Opacity;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.*;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import java.util.ArrayList;
import java.util.List;

/** Fresh section holders, captured before publication through the current section preload event. */
public final class GenerationColumn {
    public final WorldChunk chunk;
    private final BlockChunk column;
    private final List<Holder<ChunkStore>> holders=new ArrayList<>();
    private final boolean live;
    private int received;
    GenerationColumn(WorldChunk chunk,BlockChunk column){this(chunk,column,false);}
    private GenerationColumn(WorldChunk chunk,BlockChunk column,boolean live){
        this.chunk=chunk;this.column=column;this.live=live;
        for(int i=0;i<ChunkUtil.HEIGHT_SECTIONS;i++)holders.add(null);
    }
    /** Explicitly used by native fixtures on a world thread; ordinary generation never uses a live store. */
    public static GenerationColumn loaded(WorldChunk chunk){return new GenerationColumn(chunk,WorldAccess.column(chunk),true);}
    synchronized boolean add(Holder<ChunkStore> holder){
        var section=holder.getComponent(ChunkSection.getComponentType());int y=section.getY();
        if(y<0||y>=holders.size())return false;
        if(holders.get(y)==null)received++;holders.set(y,holder);
        return received==holders.size();
    }
    private BlockSection section(int y){
        if(y<0||y>=ChunkUtil.HEIGHT)return null;
        if(live)return WorldAccess.section(chunk,y);
        var holder=holders.get(ChunkUtil.indexSection(y));
        return holder==null?null:holder.ensureAndGetComponent(BlockSection.getComponentType());
    }
    public int block(int x,int y,int z){var section=section(y);return section==null?0:section.get(x,y,z);}
    public BlockType type(int x,int y,int z){return BlockType.getAssetMap().getAsset(block(x,y,z));}
    public int fluid(int x,int y,int z){
        if(y<0||y>=ChunkUtil.HEIGHT)return 0;
        if(live)return WorldAccess.fluid(chunk,x,y,z);
        var holder=holders.get(ChunkUtil.indexSection(y));if(holder==null)return 0;
        var fluid=holder.getComponent(FluidSection.getComponentType());return fluid==null?0:fluid.getFluidId(x,y,z);
    }
    public int height(int x,int z){return column.getHeight(x,z);}
    public int environment(int x,int y,int z){return column.getEnvironment(x,y,z);}
    public void updateHeight(int x,int z){
        int y=ChunkUtil.HEIGHT-1;
        while(y>0){var type=type(x,y,z);if(type!=null&&block(x,y,z)!=BlockType.EMPTY_ID&&type.getOpacity()!=Opacity.Transparent)break;y--;}
        column.setHeight(x,z,(short)y);column.markNeedsSaving();
    }
    public void set(int x,int y,int z,String id){set(x,y,z,id,0,0);}
    public void set(int x,int y,int z,String id,int rotation,int filler){
        int index=BlockType.getAssetMap().getIndex(id);if(index<0)throw new IllegalArgumentException("Missing generation block "+id);
        var section=section(y);if(section==null)throw new IllegalStateException("Generation section missing");
        boolean different=section.get(x,y,z)!=index||section.getFiller(x,y,z)!=filler;
        section.set(x,y,z,index,rotation,filler);
        if(!live){
            var holder=holders.get(ChunkUtil.indexSection(y));
            holder.getComponent(ChunkSection.getComponentType()).markNeedsSaving();
            // Native column initialization ran earlier. Furnishings added through the section
            // event need their native component holder installed before section publication.
            var block=BlockType.getAssetMap().getAsset(index);
            var entity=block.getBlockEntity();
            int cell=ChunkUtil.indexBlock(x,y,z);
            var existing=holder.getComponent(BlockComponentSection.getComponentType());
            if(different&&existing!=null)existing.removeBlockHolder(cell);
            if(entity!=null&&filler==0){
                var components=holder.ensureAndGetComponent(BlockComponentSection.getComponentType());
                if(components.getBlockHolder(cell)==null)components.addBlockHolder(cell,entity.clone());
            }
        }
        chunk.markNeedsSaving();column.markNeedsSaving();
    }
}
