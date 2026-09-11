package com.hexvane.strangematter.equipment;

import com.hypixel.hytale.builtin.blockphysics.BlockPhysicsUtil;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.BlockNeighbor;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockOperations;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import org.joml.Vector3i;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

/** Native placement clearance, actual support evaluation and client wire support faces. */
public final class NativeWallMountVerification {
    private static final Vector3i ORIGIN=new Vector3i(16,268,16);
    private record Cell(Vector3i position,int id,int rotation,int filler) {}

    public static void verify(World world){
        var chunk=world.getChunkIfLoaded(ChunkUtil.indexChunk(0,0));require(chunk!=null,"Wall mount fixture uses a loaded chunk");
        var section=chunk.getBlockChunk().getSectionAtBlockY(ORIGIN.y);
        var saved=new ArrayList<Cell>();
        for(int x=15;x<=17;x++)for(int z=15;z<=17;z++)
            saved.add(new Cell(new Vector3i(x,ORIGIN.y,z),section.get(x,ORIGIN.y,z),section.getRotationIndex(x,ORIGIN.y,z),section.getFiller(x,ORIGIN.y,z)));
        int samples=0;
        try{
            var supports=new ArrayList<BlockType>();
            for(String id:List.of("SM_Resonite_Block","SM_Resonite_Tile","SM_Fancy_Resonite_Tile","SM_Resonite_Pillar","Rock_Stone"))supports.add(asset(id));
            var doubled=asset("SM_Resonite_Tile_Slab").getBlockForState("Block");require(doubled!=null,"Native doubled slab state exists");supports.add(doubled);
            var reader=new BlockPhysicsUtil.SupportReader(){
                @Override public boolean isPositionAvailable(int x,int y,int z){return world.getChunkIfLoaded(ChunkUtil.indexChunkFromBlock(x,z))!=null;}
                @Override public BlockPhysicsUtil.SupportBlock getBlock(int x,int y,int z){
                    var current=world.getChunkIfLoaded(ChunkUtil.indexChunkFromBlock(x,z));if(current==null)return null;
                    var blocks=current.getBlockChunk().getSectionAtBlockY(y);
                    return new BlockPhysicsUtil.SupportBlock(blocks.get(x,y,z),blocks.getRotationIndex(x,y,z),blocks.getFiller(x,y,z),Fluid.EMPTY_ID,Fluid.EMPTY);
                }
                @Override public int getSupportValue(int x,int y,int z){return 0;}
            };
            for(var support:supports){
                assertPacketFaces(support);
                for(String id:List.of("SM_Resonite_Wall_Monitor","SM_Resonite_Sign"))for(var yaw:Rotation.NORMAL){
                    clear(chunk);var mount=asset(id);int rotation=RotationTuple.of(yaw,Rotation.None).index();
                    var direction=new Vector3i(0,0,-1);yaw.rotateY(direction,direction);
                    var wall=new Vector3i(ORIGIN).add(direction);put(chunk,wall,support,rotation);
                    require(BlockOperations.testPlaceBlock(world.getChunkStore().getStore(),section,ORIGIN.x,ORIGIN.y,ORIGIN.z,mount,rotation),
                            "Monitor or sign fits against the wall without reserving the supporting cell: "+id+" "+support.getId()+" "+yaw);
                    require(BlockPhysicsUtil.testBlockPhysics(reader,ORIGIN.x,ORIGIN.y,ORIGIN.z,mount,rotation,0)==BlockPhysicsUtil.SATISFIES_SUPPORT,
                            "Native support permits the mounted item on Resonite: "+id+" "+support.getId()+" "+yaw);
                    put(chunk,ORIGIN,mount,rotation);
                    require(section.get(ORIGIN.x,ORIGIN.y,ORIGIN.z)==BlockType.getAssetMap().getIndex(id),"The actual native block was placed");
                    put(chunk,wall,BlockType.EMPTY,0);
                    require(BlockPhysicsUtil.testBlockPhysics(reader,ORIGIN.x,ORIGIN.y,ORIGIN.z,mount,rotation,0)==BlockPhysicsUtil.DOESNT_SATISFY,
                            "Removing the wall invalidates the native mount support");
                    var wrongSide=new Vector3i(ORIGIN).sub(direction);put(chunk,wrongSide,support,rotation);
                    require(BlockPhysicsUtil.testBlockPhysics(reader,ORIGIN.x,ORIGIN.y,ORIGIN.z,mount,rotation,0)==BlockPhysicsUtil.DOESNT_SATISFY,
                            "A wall in front of the monitor or sign cannot satisfy its rotated rear mounting requirement");
                    put(chunk,wrongSide,BlockType.EMPTY,0);put(chunk,wall,asset("SM_Resonite_Chair"),rotation);
                    require(BlockPhysicsUtil.testBlockPhysics(reader,ORIGIN.x,ORIGIN.y,ORIGIN.z,mount,rotation,0)==BlockPhysicsUtil.DOESNT_SATISFY,
                            "A nearby partial furniture model has not been given false full wall support");
                    samples++;
                }
            }
        }finally{
            for(var cell:saved)section.set(cell.position.x,cell.position.y,cell.position.z,cell.id,cell.rotation,cell.filler);
        }
        System.out.println("NATIVE_WALL_MOUNT_VERIFICATION_PASSED: "+samples+" actual native support and clearance cases, all four wall orientations, client wire full faces, doubled slabs, wall removal, wrong side rejection and partial furniture rejection.");
    }
    private static void assertPacketFaces(BlockType type){
        var packet=type.toPacket();var bytes=MemorySegment.ofArray(new byte[packet.computeSize()]);packet.serialize(bytes,0);
        var decoded=com.hypixel.hytale.protocol.BlockType.toObject(bytes);
        for(var side:List.of(BlockNeighbor.Up,BlockNeighbor.Down,BlockNeighbor.North,BlockNeighbor.East,BlockNeighbor.South,BlockNeighbor.West)){
            var faces=decoded.supporting.get(side);
            require(faces!=null&&java.util.Arrays.stream(faces).anyMatch(face->"Full".equals(face.faceType)),
                    "The native client packet provides full support on "+side+" for "+type.getId());
        }
    }
    private static BlockType asset(String id){var type=BlockType.getAssetMap().getAsset(id);require(type!=null,"Loaded native block "+id);return type;}
    private static void put(WorldChunk chunk,Vector3i position,BlockType type,int rotation){
        chunk.getBlockChunk().getSectionAtBlockY(position.y).set(position.x,position.y,position.z,BlockType.getAssetMap().getIndex(type.getId()),rotation,0);
    }
    private static void clear(WorldChunk chunk){
        for(int x=15;x<=17;x++)for(int z=15;z<=17;z++)chunk.getBlockChunk().getSectionAtBlockY(ORIGIN.y).set(x,ORIGIN.y,z,0,0,0);
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
