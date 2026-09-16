package com.hexvane.strangematter.machine;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import java.util.Arrays;
import org.joml.Vector3i;

/** Saved directions are relative to the block's front panel; routing uses native world faces. */
public final class EnergyStoragePorts {
    public static final String ID="SM_Resonant_Energy_Storage";
    public enum Mode {INPUT,OUTPUT,DISABLED;public Mode next(){return values()[(ordinal()+1)%values().length];}}
    public enum Face {
        FRONT(0,0,1),BACK(0,0,-1),LEFT(-1,0,0),RIGHT(1,0,0),TOP(0,1,0),BOTTOM(0,-1,0);
        final int x,y,z;Face(int x,int y,int z){this.x=x;this.y=y;this.z=z;}
    }
    /** Matches ResonantNetwork.Position.neighbors and the existing conduit connection mask. */
    private static final int[][] WORLD_FACES={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
    private EnergyStoragePorts(){}
    public static boolean storage(String id){return ID.equals(id);}
    public static Mode mode(MachineState state,Face face){
        var saved=state.energyFaces;
        if(saved!=null&&saved.length==Face.values().length&&saved[face.ordinal()]!=null)
            try{return Mode.valueOf(saved[face.ordinal()]);}catch(IllegalArgumentException ignored){}
        return face==Face.FRONT?Mode.OUTPUT:Mode.INPUT;
    }
    public static String[] snapshot(MachineState state){return Arrays.stream(Face.values()).map(f->mode(state,f).name()).toArray(String[]::new);}
    public static void set(MachineState state,Face face,Mode mode){state.energyFaces=snapshot(state);state.energyFaces[face.ordinal()]=mode.name();}
    public static boolean configured(MachineState state){return Arrays.stream(Face.values()).anyMatch(f->mode(state,f)!=(f==Face.FRONT?Mode.OUTPUT:Mode.INPUT));}
    public static int worldFace(MachineState state,Face face){
        var vector=new Vector3i(face.x,face.y,face.z);
        RotationTuple.get(state.powerRotation).applyRotationTo(vector);
        for(int i=0;i<WORLD_FACES.length;i++){var normal=WORLD_FACES[i];if(vector.x==normal[0]&&vector.y==normal[1]&&vector.z==normal[2])return i;}
        throw new IllegalStateException("Native block rotation did not yield a cardinal face");
    }
    public static boolean permits(MachineState state,int worldFace,Mode direction){
        if(!storage(state.id))return true;
        if(worldFace<0)return false;
        for(var face:Face.values())if(worldFace(state,face)==worldFace)return mode(state,face)==direction;
        return false;
    }
}
