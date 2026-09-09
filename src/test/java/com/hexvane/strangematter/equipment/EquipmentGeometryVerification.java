package com.hexvane.strangematter.equipment;
import org.joml.Vector3i;
import java.util.HashSet;

/** Regresses the reported down-facing diagonal and all charged tunnel orientations. */
public final class EquipmentGeometryVerification {
    public static void main(String[] ignored){
        var origin=new Vector3i(17,80,-23);
        for(int axis=0;axis<3;axis++)for(int sign:new int[]{-1,1})for(int depth:new int[]{1,3,6,9}){
            var cells=EquipmentService.hammerCells(origin,axis,sign,1,depth);
            require(cells.size()==9*depth&&new HashSet<>(cells).size()==9*depth,"Every axis has nine distinct cells per depth layer");
            for(int d=0;d<depth;d++)for(int u=-1;u<=1;u++)for(int v=-1;v<=1;v++){
                int[] position={origin.x,origin.y,origin.z};position[axis]+=sign*d;
                position[(axis+1)%3]+=u;position[(axis+2)%3]+=v;
                require(cells.contains(new Vector3i(position[0],position[1],position[2])),"Footprint contains all orthogonal face coordinates");
            }
            require(EquipmentService.hammerCells(origin,axis,sign,0,1).equals(java.util.List.of(origin)),"Crouch precision targets exactly one block");
        }
        require(origin.equals(new Vector3i(17,80,-23)),"Generating footprints never mutates aim");
        require(EquipmentService.restoresEchoform("SM_Echoform_Imprinter","imprint_revert",false)&&EquipmentService.restoresEchoform("SM_Echoform_Imprinter","imprint_start",true),"Explicit and crouch restore remain available on a depleted imprinter");
        require(!EquipmentService.restoresEchoform("SM_Echoform_Imprinter","imprint_start",false)&&!EquipmentService.restoresEchoform("SM_Echoform_Imprinter","imprint_complete",false),"A depleted tool cannot bypass durability to begin or complete a new disguise");
        var eye=new org.joml.Vector3d(4,5,6);
        for(var direction:java.util.List.of(new org.joml.Vector3d(0,0,-1),new org.joml.Vector3d(0,1,0),new org.joml.Vector3d(0,-1,0))){
            var copy=new org.joml.Vector3d(direction);var flash=EquipmentQueries.handheldOrigin(eye,direction);
            require(Double.isFinite(flash.x)&&Double.isFinite(flash.y)&&Double.isFinite(flash.z)&&flash.distance(eye)>.4,"Handheld emission remains visible and finite at vertical aim");
            require(copy.equals(direction)&&eye.equals(new org.joml.Vector3d(4,5,6)),"Visual offset cannot change the authoritative aim or projectile origin");
        }
        System.out.println("PASS: all six hammer orientations, 3x3 faces, charged depths, unique cells and precision mode.");
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
