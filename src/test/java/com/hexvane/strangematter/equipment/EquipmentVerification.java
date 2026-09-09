package com.hexvane.strangematter.equipment;

import com.hypixel.hytale.math.shape.Box;
import org.joml.Vector3d;
import org.joml.Vector3i;
import java.util.*;

/** Headless checks for original blob geometry and creature/body field intersection. */
public final class EquipmentVerification {
    public static void main(String[] args){
        int total=0;
        for(int seed=0;seed<1000;seed++){
            var offsets=LaboratoryFields.temporalOffsets(new Random(seed));var unique=new HashSet<>(offsets);
            require(unique.size()==offsets.size(),"No duplicate temporal blocks");
            for(var p:offsets)require(p.lengthSquared()<=6.25&&Math.abs(p.x)<=2&&Math.abs(p.y)<=2&&Math.abs(p.z)<=2,"Original radius-two ragged volume bounds");
            for(int x=-1;x<=1;x++)for(int y=-1;y<=1;y++)for(int z=-1;z<=1;z++)if(x*x+y*y+z*z<=2)require(unique.contains(new Vector3i(x,y,z)),"Dense core cannot have holes");
            total+=offsets.size();
        }
        require(total>61400&&total<63400,"Original 70 percent shell occupancy across 1000 seeds");
        var body=new Box(new Vector3d(-.3,0,-.3),new Vector3d(.3,1.8,.3));
        require(EquipmentQueries.intersects(new Vector3d(0,0,0),body,new Vector3d(-.1,1.3,-.1),new Vector3d(.1,1.5,.1)),"Chrono projectile hits torso even when feet are outside its tiny radius");
        require(!EquipmentQueries.intersects(new Vector3d(0,0,0),body,new Vector3d(1,1.3,1),new Vector3d(1.1,1.5,1.1)),"Near broad-phase candidate does not create a false impact");
        System.out.println("Equipment verification passed: 1000 chrono volumes, core density, shell occupancy, projectile/body intersection.");
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
