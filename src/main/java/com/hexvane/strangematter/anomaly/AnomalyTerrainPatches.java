package com.hexvane.strangematter.anomaly;

import com.hexvane.strangematter.worldgen.GenerationColumn;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import it.unimi.dsi.fastutil.ints.Int2ByteOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Random;

/** One bounded geology scan per fresh patch column; never loads terrain or reads a live ECS. */
final class AnomalyTerrainPatches {
    record Work(int columns,int blockReads,int classifications,int resonite,int shards) {}
    private static final byte SOIL=1,ROCK=2,UNKNOWN=4;
    private static final class Column {
        final int x,z;
        final IntArrayList hosts=new IntArrayList();
        int cursor;
        Column(int x,int z){this.x=x;this.z=z;}
    }
    static Work generate(GenerationColumn terrain,AnomalyRecord field,Random random,AnomalyGenerationSettings settings){
        if(!settings.terrainPatches)return new Work(0,0,0,0,0);
        var assets=BlockType.getAssetMap();
        int grass=assets.getIndex("SM_Anomalous_Grass"),dirt=assets.getIndex("SM_Anomalous_Dirt");
        int resonite=assets.getIndex("SM_Resonite_Ore"),shard=assets.getIndex(HytaleAnomalyEffects.shardOre(field.type));
        int radius=field.type==AnomalyType.WARP_GATE?5:4,reads=0,resonitePlaced=0,shardsPlaced=0;
        var columns=new ArrayList<Column>();var classes=new Int2ByteOpenHashMap();classes.defaultReturnValue((byte)-1);
        for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++){
            if(dx*dx+dz*dz>radius*radius)continue;
            int x=(int)Math.floor(field.x)+dx,z=(int)Math.floor(field.z)+dz;
            // Raw holders mask coordinates; crossing an edge would modify the opposite edge.
            if(ChunkUtil.chunkCoordinate(x)!=terrain.chunk.getX()||ChunkUtil.chunkCoordinate(z)!=terrain.chunk.getZ())continue;
            int ground=terrain.height(x,z);if(Math.abs(ground-field.terrainReferenceY())>8)continue;
            var column=new Column(x,z);columns.add(column);int run=0;
            for(int y=Math.min(ground,ChunkUtil.HEIGHT-1);y>0;y--){
                int id=terrain.block(x,y,z);reads++;byte kind=classes.get(id);
                if(kind==-1){var type=assets.getAsset(id);String name=type==null?null:type.getId();kind=type==null?UNKNOWN:AnomalyTerrain.soil(name)?SOIL:AnomalyTerrain.rock(name)?ROCK:0;classes.put(id,kind);}
                if(kind==SOIL){int replacement=y==ground?grass:dirt;if(replacement>=0)terrain.set(x,y,z,replacement,0,0);}
                if(y>=ground)continue;
                if(kind==ROCK)column.hosts.add((run<<9)|y);
                else if(kind!=UNKNOWN)run++;
            }
            if(random.nextDouble()<settings.resoniteColumnChance)resonitePlaced+=vein(terrain,column,resonite,random);
            if(random.nextDouble()<settings.shardColumnChance)shardsPlaced+=vein(terrain,column,shard,random);
        }
        // Preserve the existing random stream, vein lengths and guarantee after density rolls.
        Collections.shuffle(columns,random);
        for(var column:columns){
            if(resonitePlaced==0&&settings.resoniteColumnChance>0)resonitePlaced+=vein(terrain,column,resonite,random);
            if(shardsPlaced==0&&settings.shardColumnChance>0)shardsPlaced+=vein(terrain,column,shard,random);
            if((resonitePlaced>0||settings.resoniteColumnChance<=0)&&(shardsPlaced>0||settings.shardColumnChance<=0))break;
        }
        return new Work(columns.size(),reads,classes.size(),resonitePlaced,shardsPlaced);
    }
    private static int vein(GenerationColumn terrain,Column column,int id,Random random){
        if(id<0)return 0;
        int length=1+random.nextInt(3),placed=0;
        if(column.cursor==column.hosts.size())return 0;
        int run=column.hosts.getInt(column.cursor)>>>9;
        while(column.cursor<column.hosts.size()&&placed<length){
            int host=column.hosts.getInt(column.cursor);if((host>>>9)!=run)break;
            terrain.set(column.x,host&511,column.z,id,0,0);column.cursor++;placed++;
        }
        return placed;
    }
    private AnomalyTerrainPatches(){}
}
