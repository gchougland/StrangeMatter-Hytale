package com.hexvane.strangematter.anomaly;

import com.hexvane.strangematter.util.WorldAccess;

import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.events.ChunkPreLoadProcessEvent;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import org.joml.Vector3d;
import java.nio.file.Files;
import java.util.*;

public final class NativeTerrainHostVerification {
    public static void verify(World world)throws Exception{
        for(String id:List.of("Soil_Sand","Soil_Sand_Red","Soil_Clay","Soil_Mud_Dry","Soil_Gravel","Soil_Snow","Soil_Dirt_Wet"))require(AnomalyTerrain.soil(id),"Natural soil host "+id);
        for(String id:List.of("Rock_Sandstone","Rock_Stone","Rock_Lime","Rock_Basalt","Rock_Stone_Mossy","Rock_Sandstone_Cracked"))require(AnomalyTerrain.rock(id),"Natural rock host "+id);
        for(String id:List.of("Soil_Dirt_Tilled","Soil_Sand_White_Path_Half","Soil_Snow_Brick","Soil_Clay_Smooth_Red","Soil_Dirt_Stairs"))require(!AnomalyTerrain.soil(id),"Shaped soil excluded "+id);
        for(String id:List.of("Rock_Bedrock","Rock_Stone_Brick","Rock_Sandstone_Stairs","Rock_Basalt_Cobble","Ore_Iron_Stone","SM_Resonite_Ore","Wood_Oak_Planks"))require(!AnomalyTerrain.rock(id),"Protected block excluded "+id);
        var chunk=WorldAccess.loaded(world,ChunkUtil.indexChunk(0,0));require(chunk!=null,"Native terrain fixture loaded");
        String[] soils={"Soil_Sand","Soil_Clay","Soil_Mud","Soil_Gravel","Soil_Sand_Red","Soil_Dirt"};
        for(int x=20;x<=28;x++)for(int z=20;z<=28;z++){
            for(int i=0;i<soils.length;i++)set(chunk,x,140-i,z,soils[i]);
            for(int y=130;y<=134;y++)set(chunk,x,y,z,"Rock_Sandstone");
            set(chunk,x,129,z,"Rock_Sandstone_Brick");set(chunk,x,128,z,"Rock_Bedrock");set(chunk,x,127,z,"Ore_Iron_Stone");
            WorldAccess.column(chunk).updateHeight(x,z);
        }
        var effects=new HytaleAnomalyEffects();var settings=new AnomalyGenerationSettings();settings.resoniteColumnChance=1;settings.shardColumnChance=1;
        var field=new AnomalyRecord(UUID.randomUUID(),AnomalyType.GRAVITY,world.getName(),new Vector3d(24.5,142,24.5),true);
        effects.terrainGenerated(chunk,field,new AlwaysFirst(),settings);
        require(id(chunk,24,140,24).equals("SM_Anomalous_Grass"),"Desert sand surface becomes anomalous grass");
        for(int y=135;y<140;y++)require(id(chunk,24,y,24).equals("SM_Anomalous_Dirt"),"All buried natural soil strata become anomalous dirt, y="+y);
        require(id(chunk,24,134,24).equals("SM_Resonite_Ore")&&id(chunk,24,133,24).equals("SM_Gravitic_Shard_Ore"),"Desert sandstone supports both resonite and matching anomaly shard deposit");
        require(id(chunk,24,132,24).equals("Rock_Sandstone"),"Source vein density leaves remaining natural geology intact");
        require(id(chunk,24,129,24).equals("Rock_Sandstone_Brick")&&id(chunk,24,128,24).equals("Rock_Bedrock")&&id(chunk,24,127,24).equals("Ore_Iron_Stone"),"Shaped rock, bedrock and existing ore survive terrain pass");
        // Existing chunk pre-load must return before reading holders or changing natural-looking builds.
        set(chunk,24,140,24,"Soil_Sand");var service=new AnomalyService(Files.createTempDirectory("sm-existing-terrain-"));service.rarity=1;
        new com.hexvane.strangematter.worldgen.GenerationCoordinator(service,null).column(new ChunkPreLoadProcessEvent(ChunkStore.REGISTRY.newHolder(),chunk,false,System.nanoTime()));
        require(id(chunk,24,140,24).equals("Soil_Sand"),"Existing chunks and player-placed natural blocks never receive terrain retrogen");
        // A raw chunk indexes local positions with masks. Crossing its edge would corrupt the
        // opposite edge rather than a safe adjacent holder; exercise that boundary explicitly.
        for(int x=28;x<32;x++){set(chunk,x,140,24,"Soil_Sand");WorldAccess.column(chunk).updateHeight(x,24);}
        set(chunk,0,140,24,"Soil_Sand");WorldAccess.column(chunk).updateHeight(0,24);
        field=new AnomalyRecord(UUID.randomUUID(),AnomalyType.GRAVITY,world.getName(),new Vector3d(31.5,142,24.5),true);
        effects.terrainGenerated(chunk,field,new AlwaysFirst(),settings);
        require(id(chunk,31,140,24).equals("SM_Anomalous_Grass")&&id(chunk,0,140,24).equals("Soil_Sand"),"Fresh chunk terrain never wraps or crosses into a neighboring chunk");
        NativeAnomalyGenerationBalanceVerification.verify(chunk);
        System.out.println("NATIVE_TERRAIN_HOST_VERIFICATION_PASSED: native desert sandstone mixed ores, six soil strata grass/dirt conversion, original ore density, shaped/bedrock/existing ore preservation, existing-chunk guard and chunk-edge clipping.");
    }
    private static String id(WorldChunk chunk,int x,int y,int z){return WorldAccess.blockType(chunk,x,y,z).getId();}
    private static void set(WorldChunk chunk,int x,int y,int z,String id){int value=BlockType.getAssetMap().getIndex(id);require(value>=0,"Native terrain asset exists "+id);WorldAccess.section(chunk,y).set(x,y,z,value,0,0);}
    public static final class AlwaysFirst extends Random { @Override public double nextDouble(){return 0;} @Override public int nextInt(int bound){return 0;} }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
