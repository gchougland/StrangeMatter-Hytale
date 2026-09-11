package com.hexvane.strangematter.anomaly;

import com.hexvane.strangematter.util.WorldAccess;

import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.modules.interaction.DoorBlockUtils;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import org.joml.Vector3i;
import java.util.*;

/** Real native placement clearance and connected architecture decisions in a loaded chunk. */
public final class NativeArchitectureVerification {
    private static final Vector3i ORIGIN=new Vector3i(16,280,16);
    private static final List<String> STATES=List.of("default","Corner_Left","Corner_Right","Inverted_Corner_Left","Inverted_Corner_Right");
    public static void verify(World world){
        var chunk=WorldAccess.loaded(world,ChunkUtil.indexChunk(0,0));require(chunk!=null,"Architecture fixture chunk loaded");
        try{
            verifyDoors(world,chunk);
            int samples=0;
            for(var pair:List.of(new String[]{"SM_Resonite_Tile_Stairs","Wood_Softwood_Stairs"},
                    new String[]{"SM_Resonite_Roof","Metal_Iron_Roof"},
                    new String[]{"SM_Resonite_Roof_Steep","Metal_Iron_Roof_Steep"},
                    new String[]{"SM_Resonite_Roof_Shallow","Metal_Iron_Roof_Shallow"})){
                var seen=new HashSet<String>();boolean wide=pair[0].endsWith("Shallow");
                for(var pitch:List.of(Rotation.None,Rotation.OneEighty))for(var yaw:Rotation.NORMAL){
                    int rotation=RotationTuple.of(yaw,pitch).index();var orientationSeen=new HashSet<String>();
                    for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++){
                        if(dx==0&&dz==0)continue;
                        for(var otherYaw:Rotation.NORMAL){
                            int otherRotation=RotationTuple.of(otherYaw,pitch).index();
                            String actual=joined(world,chunk,pair[0],rotation,dx,dz,otherRotation,wide);
                            String expected=joined(world,chunk,pair[1],rotation,dx,dz,otherRotation,wide);
                            require(actual.equals(expected),"Native joining differs: "+pair[0]+" "+rotation+" neighbor "+dx+","+dz+"/"+otherRotation+" actual="+actual+" expected="+expected);
                            orientationSeen.add(actual);seen.add(actual);samples++;
                        }
                    }
                    require(orientationSeen.containsAll(STATES),"All straight, outer and inner joins exist for "+pair[0]+" rotation "+rotation+": "+orientationSeen);
                }
                require(seen.containsAll(STATES),"Roof/stair does not expose all native join shapes: "+pair[0]+" "+seen);
            }
            var roof=BlockType.getAssetMap().getAsset("SM_Resonite_Roof");
            require(roof.getBlockForState("Topper")!=null,"Regular roof has the native apex cap state");
            require(BlockType.getAssetMap().getAsset("SM_Resonite_Roof_Flat")!=null,"Flat roof is independently placeable");
            System.out.println("NATIVE_ARCHITECTURE_VERIFICATION_PASSED: native door placement opens beside framed walls and rejects genuine swing obstructions; hatch clears surrounding floor; "+samples+" native roof/stair connection decisions match stock assets across upright and inverted orientations.");
        }finally{clear(chunk);}
    }
    private static void verifyDoors(World world,WorldChunk chunk){
        for(var yaw:Rotation.NORMAL){
            clear(chunk);int rotation=RotationTuple.of(yaw,Rotation.None).index();
            // Complete wall frame, with an empty front and back swing space.
            for(int y=0;y<3;y++)for(int x:List.of(-1,1))put(chunk,offset(yaw,x,y,0),"Rock_Stone",0);
            put(chunk,offset(yaw,0,2,0),"Rock_Stone",0);
            place(chunk,"SM_Resonite_Door",rotation);
            require(DoorBlockUtils.canOpenDoor(world.getChunkStore(),ORIGIN,"OpenDoorIn"),"Door cannot open inward inside frame at yaw "+yaw);
            require(DoorBlockUtils.canOpenDoor(world.getChunkStore(),ORIGIN,"OpenDoorOut"),"Door cannot open outward inside frame at yaw "+yaw);
            put(chunk,offset(yaw,0,0,1),"Rock_Stone",0);put(chunk,offset(yaw,0,0,-1),"Rock_Stone",0);
            require(!DoorBlockUtils.canOpenDoor(world.getChunkStore(),ORIGIN,"OpenDoorIn")&&!DoorBlockUtils.canOpenDoor(world.getChunkStore(),ORIGIN,"OpenDoorOut"),"Door must still reject real blocks in both swing paths");
            clear(chunk);
            for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)if(x!=0||z!=0)put(chunk,new Vector3i(ORIGIN).add(x,0,z),"Rock_Stone",0);
            put(chunk,new Vector3i(ORIGIN).add(0,-1,0),"Rock_Stone",0);place(chunk,"SM_Resonite_Trapdoor",rotation);
            require(DoorBlockUtils.canOpenDoor(world.getChunkStore(),ORIGIN,"OpenDoorOut"),"Hatch must open inside a surrounded floor cell at yaw "+yaw);
        }
    }
    private static String joined(World world,WorldChunk chunk,String id,int rotation,int dx,int dz,int otherRotation,boolean wide){
        clear(chunk);var base=BlockType.getAssetMap().getAsset(id);require(base!=null&&base.getConnectedBlockRuleSet()!=null,"Loaded connected asset "+id);
        put(chunk,ORIGIN,id,rotation);
        if(wide){
            // Native isWidthFulfilled requires one same-facing companion on
            // the corner's inside. getCornerConnection simultaneously requires
            // the opposite outside neighbor to be absent or face differently.
            // Filling both sides creates a continuous eave, so it intentionally
            // suppresses the outer corner even when the width check succeeds.
            var current=RotationTuple.get(rotation);var other=RotationTuple.get(otherRotation);
            var currentYaw=current.pitch()==Rotation.None?current.yaw():current.yaw().flip();
            var otherYaw=other.pitch()==Rotation.None?other.yaw():other.yaw().flip();
            if(otherYaw==currentYaw.add(Rotation.Ninety))put(chunk,offset(currentYaw,-1,0,0),id,rotation);
            else if(otherYaw==currentYaw.subtract(Rotation.Ninety))put(chunk,offset(currentYaw,1,0,0),id,rotation);
        }
        put(chunk,new Vector3i(ORIGIN).add(dx,0,dz),id,otherRotation);
        var result=base.getConnectedBlockRuleSet().getConnectedBlockType(world.getChunkStore(),ORIGIN,base,rotation,new Vector3i(0,1,0),true).orElseThrow();
        require(result.rotationIndex()==rotation,"Connected result preserves placement rotation");
        for(var state:STATES){var candidate=state.equals("default")?base:base.getBlockForState(state);if(candidate!=null&&candidate.getId().equals(result.blockTypeKey()))return state;}
        var cap=base.getBlockForState("Topper");if(cap!=null&&cap.getId().equals(result.blockTypeKey()))return "Topper";
        throw new AssertionError("Unknown native connected result "+id+" -> "+result.blockTypeKey());
    }
    private static Vector3i offset(Rotation yaw,int x,int y,int z){var result=new Vector3i(x,y,z);yaw.rotateY(result,result);return result.add(ORIGIN);}
    private static void place(WorldChunk chunk,String id,int rotation){var type=BlockType.getAssetMap().getAsset(id);require(type!=null,"Loaded placeable "+id);WorldAccess.set(chunk,ORIGIN.x,ORIGIN.y,ORIGIN.z,BlockType.getAssetMap().getIndex(id),type,rotation,0,0);}
    private static void put(WorldChunk chunk,Vector3i position,String id,int rotation){int value=BlockType.getAssetMap().getIndex(id);require(value>=0,"Loaded block "+id);WorldAccess.section(chunk,position.y).set(position.x,position.y,position.z,value,rotation,0);}
    private static void clear(WorldChunk chunk){for(int x=11;x<=21;x++)for(int z=11;z<=21;z++)for(int y=278;y<=283;y++)WorldAccess.section(chunk,y).set(x,y,z,0,0,0);}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
