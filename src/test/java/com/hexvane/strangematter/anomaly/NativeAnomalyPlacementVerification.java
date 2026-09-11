package com.hexvane.strangematter.anomaly;

import com.hexvane.strangematter.util.WorldAccess;

import com.google.gson.JsonParser;
import com.hexvane.strangematter.equipment.NativePlayerFixture;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.events.ChunkPreLoadProcessEvent;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import org.joml.Vector3d;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** One-time ground placement, exact-position exceptions and unchanged native terrain reach. */
public final class NativeAnomalyPlacementVerification {
    public static void verify(World world)throws Exception {
        var directory=Files.createTempDirectory("sm-placement-");var service=new AnomalyService(directory);service.naturalGeneration=false;
        var expected=new HashMap<UUID,Vector3d>();
        try {
            for(var type:AnomalyType.values()){
                var previous=new Vector3d(40.5+type.ordinal(),210,12.5);
                var field=service.spawnRaised(type,world,previous,true);
                require(previous.y==210&&field.y==211&&field.terrainReferenceY()==210&&field.placementLift==1,"New "+type+" raises the logical field once and retains its terrain anchor");
                require(coreAt(world,field),"Native "+type+" visual core shares its raised field center");
                var token=service.capture(field.id).orElseThrow();
                var landing=new Vector3d(previous.x,215.5,previous.z);
                var released=service.releaseRaised(token.token(),world,landing).orElseThrow();
                require(released.id.equals(field.id)&&released.y==216.5&&released.placementLift==1&&released.released,"Capsule relocation preserves identity and raises its new landing center exactly once");
                require(coreAt(world,released),"Released core uses its new logical height");
                require(service.releaseRaised(token.token(),world,landing).isEmpty()&&released.y==216.5,"Replaying a spent capsule cannot move or lift the field again");
                expected.put(field.id,field.position());
            }
            var impact=new Vector3d(43.5,220.75,16.5);
            var gate=service.spawn(AnomalyType.WARP_GATE,world,new Vector3d(impact).add(0,1,0),false);
            require(gate.y==221.75&&gate.placementLift==0&&coreAt(world,gate),"Warp gun exact impact plus one remains unchanged and is not raised twice");
            expected.put(gate.id,gate.position());
            var ceiling=service.spawnRaised(AnomalyType.GRAVITY,world,new Vector3d(44.5,319.5,16.5),false);
            require(ceiling.y>=319.5&&ceiling.y<ChunkUtil.HEIGHT&&Double.isFinite(ceiling.y),"Ceiling placement stays in build height instead of failing after consuming a capsule");
            expected.put(ceiling.id,ceiling.position());
            service.save();
            for(int i=0;i<3;i++){
                var loaded=new AnomalyService(directory);
                for(var entry:expected.entrySet())require(loaded.get(entry.getKey()).orElseThrow().position().equals(entry.getValue()),"Repeated save load preserves each field coordinate without migration drift");
            }
            // A real old save omits the new optional field. Loading it must not move anything.
            var legacy=Files.createTempDirectory("sm-placement-legacy-");var json=JsonParser.parseString(Files.readString(directory.resolve("anomalies.json"))).getAsJsonObject();
            for(var entry:json.getAsJsonArray("anomalies"))entry.getAsJsonObject().remove("placementLift");
            Files.writeString(legacy.resolve("anomalies.json"),json.toString());var old=new AnomalyService(legacy);
            for(var entry:expected.entrySet())require(old.get(entry.getKey()).orElseThrow().position().equals(entry.getValue())&&old.get(entry.getKey()).orElseThrow().placementLift==0,"Old saved anomalies keep their exact height and legacy ground anchor");
        }finally{service.stopWorld(world);}
        verifyGenerated(world);
        System.out.println("NATIVE_ANOMALY_PLACEMENT_VERIFICATION_PASSED: six raised native cores and field centers, capsule identity/replay, exact warp impact height, ceiling bounds, unchanged repeated/legacy save loads, six fresh generation placements with mixed ores and preserved terrain search reach.");
    }
    private static boolean coreAt(World world,AnomalyRecord field){
        return world.getEntityStore().getStore().forEachChunk(Query.and(ModelComponent.getComponentType(),TransformComponent.getComponentType()),(chunk,commands)->{
            for(int i=0;i<chunk.size();i++){
                var model=chunk.getComponent(i,ModelComponent.getComponentType()).getModel();
                var position=chunk.getComponent(i,TransformComponent.getComponentType()).getPosition();
                if((field.particleId()+"_Core").equals(model.getModelAssetId())&&position.distanceSquared(field.position())<1e-12)return true;
            }
            return false;
        });
    }
    @SuppressWarnings("unchecked")
    private static void verifyGenerated(World world)throws Exception {
        var chunk=WorldAccess.loaded(world,ChunkUtil.indexChunk(0,0));require(chunk!=null,"Placement geology fixture chunk loaded");
        for(var type:AnomalyType.values()){
            geology(chunk);
            var service=new AnomalyService(Files.createTempDirectory("sm-placement-generation-"));service.rarity=1;
            for(var other:AnomalyType.values())service.generationSettings.typeRarity.put(other.name(),other==type?1:0);
            service.generationSettings.resoniteColumnChance=1;service.generationSettings.shardColumnChance=1;
            service.generate(com.hexvane.strangematter.worldgen.GenerationColumn.loaded(chunk));
            require(!service.all().isEmpty(),"Fresh preload creates a real "+type+" field");
            for(var field:service.all())require(field.type==type&&field.y==(type==AnomalyType.WARP_GATE?203.5:203)&&field.placementLift==1,
                    "Fresh "+type+" field is exactly one block above its previous surface-relative height");
            int resonite=0,shards=0;
            for(int x=0;x<32;x++)for(int z=0;z<32;z++)for(int y=194;y<=199;y++){
                String id=WorldAccess.blockType(chunk,x,y,z).getId();if(id.equals("SM_Resonite_Ore"))resonite++;if(id.equals(HytaleAnomalyEffects.shardOre(type)))shards++;
            }
            require(resonite>0&&shards>0,"Raised "+type+" generation preserves resonite and matching shard geology");
        }
        geology(chunk);var service=new AnomalyService(Files.createTempDirectory("sm-placement-reach-"));service.naturalGeneration=false;
        var boundary=service.spawnRaised(AnomalyType.GRAVITY,world,new Vector3d(16.5,208,16.5),true);
        try {
            var gate=service.spawnRaised(AnomalyType.WARP_GATE,world,new Vector3d(16,202.5,16),true);
            try(var player=NativePlayerFixture.create(world,"RaisedWarpEntry",new Vector3d(16,201,16))){
                player.store().tick(.05f);var effects=new HytaleAnomalyEffects();
                var exact=new AnomalyRecord(UUID.randomUUID(),AnomalyType.WARP_GATE,world.getName(),gate.position(),false);
                require(!effects.entities(world,exact).contains(player.ref()),"Native foot-only sphere demonstrates why a raised gate would miss a standing player");
                require(effects.entities(world,gate).contains(player.ref()),"Raised gate retains ground activation for the real native player spatial entry");
            }
            new HytaleAnomalyEffects().terrainGenerated(chunk,boundary,new Random(31),new AnomalyGenerationSettings());
            require("SM_Anomalous_Grass".equals(WorldAccess.blockType(chunk,16,200,16).getId()),"One-block lift retains the previous eight-block terrain conversion boundary");
            // The old downward search included y=200 from center207. Raising the real center
            // to208 must retain that last row while leaving collider/restoration behavior intact.
            var floating=service.spawnRaised(AnomalyType.GRAVITY,world,new Vector3d(16,207,16),true);
            var terrain=new GravityTerrain(Files.createTempDirectory("sm-placement-floating-"),w->CompletableFuture.completedFuture(null));
            try {terrain.tick(world,List.of(floating),.05);require(terrain.receipts().size()==8,"Raised gravity field still finds all eight native terrain pieces at the prior search limit");}
            finally {terrain.stopWorld(world);terrain.tick(world,List.of(),.05);}
        }finally {service.stopWorld(world);}
    }
    private static void geology(WorldChunk chunk){
        for(int x=0;x<32;x++)for(int z=0;z<32;z++){
            for(int y=201;y<ChunkUtil.HEIGHT;y++)set(chunk,x,y,z,"Empty");
            set(chunk,x,200,z,"Soil_Dirt");for(int y=194;y<=199;y++)set(chunk,x,y,z,"Rock_Stone");
            WorldAccess.column(chunk).updateHeight(x,z);
        }
    }
    private static void set(WorldChunk chunk,int x,int y,int z,String id){int block=BlockType.getAssetMap().getIndex(id);require(block>=0,"Native placement asset "+id);WorldAccess.section(chunk,y).set(x,y,z,block,0,0);}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
