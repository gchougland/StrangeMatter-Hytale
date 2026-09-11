package com.hexvane.strangematter.anomaly;

import com.hexvane.strangematter.util.WorldAccess;

import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.modules.entity.component.Invulnerable;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.npc.NPCPlugin;
import org.joml.Vector3d;
import java.util.*;

/** Runs only with native assets, damage systems and a loaded world. No connected client is simulated. */
public final class NativeAnomalyRevisionVerification {
    public static void verify(World world) {
        var effects=new HytaleAnomalyEffects();var store=world.getEntityStore().getStore();
        require(DamageCause.getAssetMap().getAsset("SM_Energetic_Rift")!=null,"Declared native elemental rift damage cause loaded");
        var spawned=NPCPlugin.get().spawnNPC(store,"Cow",null,new Vector3d(26,30,26),new Rotation3f());
        require(spawned!=null,"Native living rift damage subject spawned");var victim=spawned.first();
        var stats=store.getComponent(victim,EntityStatMap.getComponentType());int health=DefaultEntityStatTypes.getHealth();
        require(stats!=null&&stats.get(health)!=null,"Native NPC health stat initialized");
        var field=new AnomalyRecord(UUID.randomUUID(),AnomalyType.ENERGETIC_RIFT,world.getName(),new Vector3d(26,30,25),true);
        float before=stats.get(health).get();require(before>5,"Native NPC has sufficient health for damage regression");
        effects.zapTarget(world,field,victim);
        require(Math.abs(stats.get(health).get()-(before-5))<.001,"Ungrounded rift subtracts exactly five native health");
        store.putComponent(victim,Invulnerable.getComponentType(),Invulnerable.INSTANCE);before=stats.get(health).get();
        effects.zapTarget(world,field,victim);
        require(stats.get(health).get()==before,"Native invulnerability still cancels energetic rift damage");
        store.removeEntity(victim,RemoveReason.REMOVE);
        world.setBlock(28,40,28,"SM_Rift_Stabilizer");
        require(!effects.hasRod(world,new Vector3d(28,40,28),1),"A stabilizer block alone cannot bypass enabled grounding hook");
        world.setBlock(28,40,28,"Empty");
        var chunk=WorldAccess.loaded(world,ChunkUtil.indexChunk(0,0));require(chunk!=null,"Loaded terrain fixture chunk exists");
        var settings=new AnomalyGenerationSettings();
        for(var type:AnomalyType.values()) {
            geology(chunk);
            var record=new AnomalyRecord(UUID.randomUUID(),type,world.getName(),new Vector3d(7.5,101,7.5),true);
            // Force every probability roll to miss. Each enabled ore must still have its
            // one-block minimum below 30 blocks of unsupported sediment/cave ceiling.
            effects.terrainGenerated(chunk,record,new MissEveryRoll(),settings);
            int resonite=0,shard=0;
            for(int x=2;x<=12;x++)for(int z=2;z<=12;z++)for(int y=68;y<=69;y++) {
                String id=WorldAccess.blockType(chunk,x,y,z).getId();
                if(id.equals("SM_Resonite_Ore"))resonite++;
                if(id.equals(HytaleAnomalyEffects.shardOre(type)))shard++;
            }
            require(resonite>0&&shard>0,type+" fresh field includes resonite and its own shard ore");
            require("Rock_Stone_Brick".equals(WorldAccess.blockType(chunk,7,67,7).getId())&&"Rock_Bedrock".equals(WorldAccess.blockType(chunk,7,66,7).getId()),"Ore generation preserves shaped blocks and bedrock");
        }
        geology(chunk);settings.resoniteColumnChance=0;settings.shardColumnChance=0;
        effects.terrainGenerated(chunk,new AnomalyRecord(UUID.randomUUID(),AnomalyType.GRAVITY,world.getName(),new Vector3d(7.5,101,7.5),true),new MissEveryRoll(),settings);
        for(int x=2;x<=12;x++)for(int z=2;z<=12;z++)require("Rock_Lime".equals(WorldAccess.blockType(chunk,x,69,z).getId())&&"Rock_Lime".equals(WorldAccess.blockType(chunk,x,68,z).getId()),"Explicit zero ore probabilities disable guaranteed deposit");
        System.out.println("NATIVE_ANOMALY_REVISION_PASSED: actual rift health delta and invulnerability; disabled stabilizer cannot ground; six mixed ore deposits below deep strata, safe hosts, disabled generation.");
    }
    private static void geology(WorldChunk chunk) {
        for(int x=2;x<=12;x++)for(int z=2;z<=12;z++) {
            for(int y=70;y<=100;y++)set(chunk,x,y,z,y==100?"Soil_Dirt":"Empty");
            set(chunk,x,69,z,"Rock_Lime");set(chunk,x,68,z,"Rock_Lime");set(chunk,x,67,z,"Rock_Stone_Brick");set(chunk,x,66,z,"Rock_Bedrock");
            WorldAccess.column(chunk).updateHeight(x,z);
        }
    }
    private static void set(WorldChunk chunk,int x,int y,int z,String id) {
        int block=BlockType.getAssetMap().getIndex(id);require(block>=0,"Native terrain asset "+id+" loaded");
        WorldAccess.section(chunk,y).set(x,y,z,block,0,0);
    }
    public static final class MissEveryRoll extends Random {
        @Override public double nextDouble(){return 1;}
        @Override public int nextInt(int bound){return 0;}
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
