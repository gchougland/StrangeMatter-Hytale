package com.hexvane.strangematter.anomaly;

import com.hexvane.strangematter.util.WorldAccess;
import com.hexvane.strangematter.worldgen.GenerationColumn;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import org.joml.Vector3d;
import org.joml.Vector3i;
import java.util.*;

/** Compares full native blocks and subsequent RNG state with the former repeated-scan terrain pass. */
final class NativeAnomalyGenerationBalanceVerification {
    private static final int MIN=4,MAX=12,GROUND=80;
    static void verify(WorldChunk chunk){
        var terrain=GenerationColumn.loaded(chunk);int[] original=snapshot(chunk);short[] heights=new short[81];int cell=0;
        for(int x=MIN;x<=MAX;x++)for(int z=MIN;z<=MAX;z++)heights[cell++]=(short)terrain.height(x,z);
        var field=new AnomalyRecord(UUID.randomUUID(),AnomalyType.GRAVITY,chunk.getWorld().getName(),new Vector3d(8.5,82,8.5),true);
        long oldReads=0,newReads=0;int oldResonite=0,newResonite=0,oldShards=0,newShards=0;
        try {
            var settings=new AnomalyGenerationSettings();settings.resoniteColumnChance=.25;
            for(int seed=0;seed<16;seed++){
                prepare(chunk,false);var oldRandom=new Random(seed);reference(terrain,field,oldRandom,settings);int[] expected=snapshot(chunk);long next=oldRandom.nextLong();
                prepare(chunk,false);var random=new Random(seed);var work=AnomalyTerrainPatches.generate(terrain,field,random,settings);
                require(Arrays.equals(expected,snapshot(chunk))&&random.nextLong()==next,"Optimized native terrain and RNG match former algorithm, seed="+seed);
                require(work.blockReads()==work.columns()*GROUND&&work.classifications()<=8,"Each original geology block is read once and distinct native IDs are classified once");
            }
            settings.resoniteColumnChance=1;settings.shardColumnChance=1;
            prepare(chunk,true);oldReads=reference(terrain,field,new Random(7),settings);
            prepare(chunk,true);var bounded=AnomalyTerrainPatches.generate(terrain,field,new Random(7),settings);newReads=bounded.blockReads();
            require(oldReads>newReads*4&&bounded.resonite()==0&&bounded.shards()==0,"Unsuitable geology cannot trigger repeated full-depth density/guarantee rescans");
            var previous=new AnomalyGenerationSettings();previous.resoniteColumnChance=.25;var current=new AnomalyGenerationSettings();
            for(int seed=0;seed<128;seed++){
                prepare(chunk,false);var before=AnomalyTerrainPatches.generate(terrain,field,new Random(seed*91871L+19),previous);
                prepare(chunk,false);var after=AnomalyTerrainPatches.generate(terrain,field,new Random(seed*91871L+19),current);
                require(before.resonite()>0&&before.shards()>0&&after.resonite()>0&&after.shards()>0,"Each density keeps both advertised resources in suitable geology");
                oldResonite+=before.resonite();newResonite+=after.resonite();oldShards+=before.shards();newShards+=after.shards();
            }
            double ratio=(double)newResonite/oldResonite,shardRatio=(double)newShards/oldShards;
            require(ratio>.63&&ratio<.77,"Fixed-seed resonite output is approximately thirty percent lower: "+ratio);
            require(shardRatio>.88&&shardRatio<1.12,"Shard density remains unchanged within deterministic sampling variance: "+shardRatio);
        }finally{
            restore(chunk,original);cell=0;for(int x=MIN;x<=MAX;x++)for(int z=MIN;z<=MAX;z++)WorldAccess.column(chunk).setHeight(x,z,heights[cell++]);
        }
        System.out.println("NATIVE_ANOMALY_GENERATION_BALANCE_VERIFICATION_PASSED: 16 exact native block/RNG comparisons; unsuitable-geology reads "+oldReads+"→"+newReads+"; 128-field resonite "+oldResonite+"→"+newResonite+", shard "+oldShards+"→"+newShards+"; protected geology and both-resource guarantees.");
    }
    private static void prepare(WorldChunk chunk,boolean noRock){
        int air=index("Empty"),soil=index("Soil_Sand"),rock=index("Rock_Sandstone"),brick=index("Rock_Sandstone_Brick"),ore=index("Ore_Iron_Stone"),bedrock=index("Rock_Bedrock");
        for(int x=MIN;x<=MAX;x++)for(int z=MIN;z<=MAX;z++){
            for(int y=1;y<=GROUND;y++){
                int id=noRock?soil:y>=79?soil:y>=61?air:y==55?brick:y==54?ore:y==53?bedrock:y==40?soil:y==30?air:rock;
                WorldAccess.section(chunk,y).set(x,y,z,id,0,0);
            }
            WorldAccess.column(chunk).setHeight(x,z,(short)GROUND);
        }
    }
    private static int[] snapshot(WorldChunk chunk){var out=new int[81*GROUND*3];int i=0;for(int x=MIN;x<=MAX;x++)for(int z=MIN;z<=MAX;z++)for(int y=1;y<=GROUND;y++){out[i++]=WorldAccess.block(chunk,x,y,z);out[i++]=WorldAccess.rotation(chunk,x,y,z);out[i++]=WorldAccess.filler(chunk,x,y,z);}return out;}
    private static void restore(WorldChunk chunk,int[] blocks){int i=0;for(int x=MIN;x<=MAX;x++)for(int z=MIN;z<=MAX;z++)for(int y=1;y<=GROUND;y++)WorldAccess.section(chunk,y).set(x,y,z,blocks[i++],blocks[i++],blocks[i++]);}
    private static int index(String id){int value=BlockType.getAssetMap().getIndex(id);require(value>=0,"Native fixture asset "+id);return value;}
    private static long reference(GenerationColumn terrain,AnomalyRecord field,Random random,AnomalyGenerationSettings settings){
        long[] reads={0};var columns=new ArrayList<Vector3i>();int resonite=0,shards=0;
        for(int dx=-4;dx<=4;dx++)for(int dz=-4;dz<=4;dz++){
            if(dx*dx+dz*dz>16)continue;int x=8+dx,z=8+dz,ground=terrain.height(x,z);columns.add(new Vector3i(x,ground-1,z));
            for(int y=ground;y>0;y--){reads[0]++;var old=terrain.type(x,y,z);if(old!=null&&AnomalyTerrain.soil(old.getId()))terrain.set(x,y,z,y==ground?"SM_Anomalous_Grass":"SM_Anomalous_Dirt");}
            if(random.nextDouble()<settings.resoniteColumnChance)resonite+=referenceVein(terrain,x,ground-1,z,"SM_Resonite_Ore",random,reads);
            if(random.nextDouble()<settings.shardColumnChance)shards+=referenceVein(terrain,x,ground-1,z,HytaleAnomalyEffects.shardOre(field.type),random,reads);
        }
        Collections.shuffle(columns,random);
        for(var column:columns){
            if(resonite==0&&settings.resoniteColumnChance>0)resonite+=referenceVein(terrain,column.x,column.y,column.z,"SM_Resonite_Ore",random,reads);
            if(shards==0&&settings.shardColumnChance>0)shards+=referenceVein(terrain,column.x,column.y,column.z,HytaleAnomalyEffects.shardOre(field.type),random,reads);
            if((resonite>0||settings.resoniteColumnChance<=0)&&(shards>0||settings.shardColumnChance<=0))break;
        }
        return reads[0];
    }
    private static int referenceVein(GenerationColumn terrain,int x,int y,int z,String id,Random random,long[] reads){
        int length=1+random.nextInt(3),placed=0;
        for(int blockY=y;blockY>0&&placed<length;blockY--){reads[0]++;var old=terrain.type(x,blockY,z);if(old==null)continue;if(!AnomalyTerrain.rock(old.getId())){if(placed>0)break;continue;}terrain.set(x,blockY,z,id);placed++;}
        return placed;
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
