package com.hexvane.strangematter.block;

import com.hexvane.strangematter.util.WorldAccess;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.BlockMaterial;
import com.hypixel.hytale.protocol.BlockNeighbor;
import com.hypixel.hytale.protocol.Opacity;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.ChunkLightDataBuilder;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.universe.world.lighting.FloodLightCalculation;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import org.bson.BsonDocument;

/** Actual native local flood propagation, isolated from sky and live world blocks. */
public final class NativeFloorLightingVerification {
    private static final int X=16,Y=24,Z=16;

    public static void verify(World world)throws Exception {
        world.debugAssertInTickingThread();
        var chunk=WorldAccess.inMemory(world,ChunkUtil.indexChunk(0,0));
        require(chunk!=null,"Native light fixture needs only the already loaded origin chunk");
        int loadedBefore=world.getChunkStore().getLoadedChunksCount();
        var lamp=asset("SM_Lab_Lamp");var on=lamp.getBlockForState("On");var off=lamp.getBlockForState("Off");
        require(on!=null&&off!=null,"Native ceiling lamp switch states load");
        for(var type:List.of(lamp,on,off)) {
            require(type.getMaterial()==BlockMaterial.Solid&&type.getOpacity()==Opacity.Transparent,"Optical transmission must preserve physical solidity");
            require(type.getHitboxTypeIndex()==lamp.getHitboxTypeIndex()&&type.getHitboxType().equals("Full"),"Default and switched lamp retain one full collision cell");
        }
        require(lamp.getLight()!=null&&lamp.getLight().equals(on.getLight())&&off.getLight()==null,"Default starts lit, On keeps exact RGB/radius, Off has no source");

        // Run the installed solver, not a reimplementation. It reads actual
        // loaded BlockType RGB and opacity, including encoded On/Off state IDs.
        var solver=new DarkRoomSolver();
        var flood=FloodLightCalculation.class.getDeclaredMethod("floodChunkSection",WorldChunk.class,BlockSection.class,FluidSection.class,int.class);
        flood.setAccessible(true);
        for(var type:List.of(lamp,on)) {
            var section=saved(ceiling(type));
            var light=(ChunkLightDataBuilder)flood.invoke(solver,chunk,section,new FluidSection(),0);
            int under=ChunkUtil.indexBlock(X,Y-1,Z),workbench=ChunkUtil.indexBlock(X,Y-3,Z),aboveRoof=ChunkUtil.indexBlock(X,Y+2,Z);
            require(light.getGreenBlockLight(under)==type.getLight().green-1&&light.getBlueBlockLight(under)==type.getLight().blue-1,
                    "Actual native RGB escapes downward from the ceiling diffuser: "+type.getId());
            require(light.getGreenBlockLight(workbench)>=10&&light.getBlueBlockLight(workbench)>=10,
                    "Ceiling panel gives usable cyan light three blocks below: "+type.getId());
            require(light.getGreenBlockLight(aboveRoof)==0,"Solid backing still blocks propagation above the ceiling");
        }
        var savedOff=saved(ceiling(off));
        require(savedOff.get(X,Y,Z)==BlockType.getAssetMap().getIndex(off.getId()),"Native block save roundtrip preserves the Off state");
        var dark=(ChunkLightDataBuilder)flood.invoke(solver,chunk,savedOff,new FluidSection(),0);
        for(int y=Y;y>=Y-3;y--) {
            int cell=ChunkUtil.indexBlock(X,y,Z);
            require(dark.getRedBlockLight(cell)==0&&dark.getGreenBlockLight(cell)==0&&dark.getBlueBlockLight(cell)==0,"Saved Off panel remains dark in the native solver");
        }

        // Reproduce the old defect with identical seeded RGB in an optical
        // Solid cell. Native code must reject propagation despite a bright seed.
        var legacy=ceiling(asset("SM_Resonite_Tile"));
        var seed=new ChunkLightDataBuilder(legacy.getLocalChangeCounter());int source=ChunkUtil.indexBlock(X,Y,Z);
        seed.setBlockLight(source,lamp.getLight().red,lamp.getLight().green,lamp.getLight().blue);
        var queue=new BitSet(ChunkUtil.SIZE_BLOCKS);queue.set(source);
        var propagate=FloodLightCalculation.class.getDeclaredMethod("propagateLight",BitSet.class,BlockSection.class,ChunkLightDataBuilder.class);
        propagate.setAccessible(true);propagate.invoke(solver,queue,legacy,seed);
        require(seed.getGreenBlockLight(source)==lamp.getLight().green&&seed.getGreenBlockLight(ChunkUtil.indexBlock(X,Y-1,Z))==0,
                "Regression control proves Solid opacity traps the same lamp source RGB");

        var floor=asset("SM_Resonite_Floor_Panel");
        require(floor.getMaterial()==BlockMaterial.Solid&&floor.getOpacity()==Opacity.Solid&&floor.getHitboxType().equals("Full"),"Floor is normal full solid masonry");
        var packet=floor.toPacket();
        for(var side:List.of(BlockNeighbor.Up,BlockNeighbor.Down,BlockNeighbor.North,BlockNeighbor.East,BlockNeighbor.South,BlockNeighbor.West))
            require(packet.supporting!=null&&packet.supporting.get(side)!=null&&Arrays.stream(packet.supporting.get(side)).anyMatch(face->"Full".equals(face.faceType)),
                    "Floor supports native furniture and wall fittings on "+side);
        require(world.getChunkStore().getLoadedChunksCount()==loadedBefore,"Detached native light tests load no chunks and edit no world blocks");
        System.out.println("NATIVE_FLOOR_LIGHTING_VERIFICATION_PASSED: actual ceiling RGB propagation, solid-opacity regression control, backing occlusion, saved On/Off states and full floor support");
    }

    private static BlockSection ceiling(BlockType source) {
        var section=new BlockSection();int stone=BlockType.getAssetMap().getIndex("Rock_Stone");
        for(int x=0;x<ChunkUtil.SIZE;x++)for(int z=0;z<ChunkUtil.SIZE;z++)for(int y=Y;y<=Y+1;y++)section.set(x,y,z,stone,0,0);
        section.set(X,Y,Z,BlockType.getAssetMap().getIndex(source.getId()),0,0);
        return section;
    }
    private static BlockSection saved(BlockSection section) {
        String encoded=BlockSection.CODEC.encode(section,new ExtraInfo()).asDocument().toJson();
        return BlockSection.CODEC.decode(BsonDocument.parse(encoded),new ExtraInfo());
    }
    private static final class DarkRoomSolver extends FloodLightCalculation {
        private DarkRoomSolver(){super(null);}
        @Override protected byte getSkyValue(WorldChunk chunk,int chunkY,int x,int y,int z,int sectionY,int height){return 0;}
    }
    private static BlockType asset(String id){var type=BlockType.getAssetMap().getAsset(id);require(type!=null,"Native asset is loaded: "+id);return type;}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
