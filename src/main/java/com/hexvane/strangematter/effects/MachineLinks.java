package com.hexvane.strangematter.effects;

import com.hexvane.strangematter.anomaly.AnomalyType;
import com.hypixel.hytale.server.core.universe.world.World;
import org.joml.Vector3d;
import java.util.*;

/** World-space transfer paths. Each call emits finite particles only; machines throttle to 4 Hz. */
public final class MachineLinks {
    private MachineLinks() {}
    public static List<Vector3d> arc(Vector3d from,Vector3d to,int frame){
        double length=from.distance(to);if(!Double.isFinite(length)||length>32)return List.of();
        int steps=Math.max(2,Math.min(56,(int)Math.ceil(length/.23)));
        var axis=new Vector3d(to).sub(from);if(axis.lengthSquared()<.00001)return List.of(new Vector3d(to));
        axis.normalize();var side=new Vector3d(axis).cross(Math.abs(axis.y)<.9?new Vector3d(0,1,0):new Vector3d(1,0,0)).normalize();
        var up=new Vector3d(side).cross(axis).normalize();var points=new ArrayList<Vector3d>();
        var random=new Random(0x534d524946544cL+frame*31L);
        for(int i=0;i<=steps;i++){
            double t=(double)i/steps,envelope=Math.sin(Math.PI*t);
            double a=(Math.sin(t*21+frame)*.19+(random.nextDouble()-.5)*.28)*envelope;
            double b=(Math.cos(t*17-frame)*.15+(random.nextDouble()-.5)*.2)*envelope;
            points.add(new Vector3d(from).lerp(to,t).fma(a,side).fma(b,up));
        }
        return points;
    }
    public static Vector3d flowPoint(Vector3d from,Vector3d to,double t,double phase){
        double radius=Math.sin(Math.PI*t)*.3,angle=t*Math.PI*4-phase;
        return new Vector3d(from).lerp(to,t).add(Math.cos(angle)*radius,Math.sin(Math.PI*t)*.5,Math.sin(angle)*radius);
    }
    public static String stream(AnomalyType type){return "SM_Condenser_Link_"+type.name();}
    public static void rift(World world,Vector3d from,Vector3d receiver,int tick){
        for(var point:arc(from,receiver,tick/5))GadgetEffects.particle(world,"SM_Stabilizer_Link",point);
        if(tick%20==0)GadgetEffects.particle(world,"SM_Condenser_Receipt",receiver);
    }
    public static void condenser(World world,AnomalyType type,Vector3d from,Vector3d receiver,int tick){
        if(!Double.isFinite(from.distanceSquared(receiver))||from.distanceSquared(receiver)>1024)return;
        double time=tick*.05;
        for(int i=0;i<16;i++){
            double t=(i/16d+time*.23)%1;
            GadgetEffects.particle(world,stream(type),flowPoint(from,receiver,t,time*3));
        }
        if(tick%20==0)GadgetEffects.particle(world,"SM_Condenser_Receipt",receiver);
    }
}
