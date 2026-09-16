package com.hexvane.strangematter.equipment;

import com.hexvane.strangematter.util.WorldAccess;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.modules.collision.CollisionModule;
import com.hypixel.hytale.server.core.modules.collision.CollisionResult;
import com.hypixel.hytale.server.core.modules.entity.component.*;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;
import org.joml.Vector3i;

/** Shared authoritative rays and swept native block colliders for portable equipment. */
final class AdvancedGadgetTargeting {
    static final Box RAY = new Box(-.005,-.005,-.005,.005,.005,.005);
    record Aim(Vector3d eye,Vector3d direction,double distance,Vector3i block) {}
    /** Passive roles need not have an attitude sensor. Like native attitude filters, a new
     * consumer must request the cache before using WorldSupport's cached relationship query.
     * This preserves reputation/overrides; the default role attitude alone is insufficient.
     */
    static com.hypixel.hytale.server.core.asset.type.attitude.Attitude attitude(Ref<EntityStore> npc,Ref<EntityStore> target,Store<EntityStore> store){
        var support=store.getComponent(npc,com.hypixel.hytale.server.npc.role.support.WorldSupport.getComponentType());
        if(support==null)return null;
        support.requireAttitudeCache();
        return support.getAttitude(npc,target,store);
    }
    static Aim aim(Ref<EntityStore> ref,Store<EntityStore> store,double range) {
        var transform=store.getComponent(ref,TransformComponent.getComponentType());
        var head=store.getComponent(ref,HeadRotation.getComponentType());if(transform==null||head==null)return null;
        var eye=new Vector3d(transform.getPosition()).add(0,ModelComponent.getEyeHeight(ref,store),0);
        var direction=new Vector3d(head.getDirection());if(!direction.isFinite()||direction.lengthSquared()<1e-8)return null;direction.normalize();
        var delta=new Vector3d(direction).mul(range);double fraction=sweep(store,RAY,eye,delta);
        double distance=range*fraction;var point=new Vector3d(eye).fma(distance+.03,direction);
        return new Aim(eye,direction,distance,fraction<.9999?cell(point):null);
    }
    static Vector3i cell(Vector3d p){return new Vector3i((int)Math.floor(p.x),(int)Math.floor(p.y),(int)Math.floor(p.z));}
    static Vector3d center(Ref<EntityStore> ref,Store<EntityStore> store){
        var transform=store.getComponent(ref,TransformComponent.getComponentType());if(transform==null)return null;
        var p=new Vector3d(transform.getPosition());var bounds=store.getComponent(ref,BoundingBox.getComponentType());
        if(bounds!=null){var b=bounds.getBoundingBox();p.add((b.min.x+b.max.x)/2,(b.min.y+b.max.y)/2,(b.min.z+b.max.z)/2);}return p;
    }
    static boolean visible(Store<EntityStore> store,Vector3d from,Vector3d to){return sweep(store,RAY,from,new Vector3d(to).sub(from))>.995;}
    /** Slide the remaining carry movement along obstructing faces. In particular, a cube in a
     * one-cell hole can rise before moving sideways instead of losing its entire diagonal pull.
     * Every segment is swept with the original full body, so this cannot skip a wall or ceiling.
     */
    static Vector3d carryMove(Store<EntityStore> store,Box box,Vector3d from,Vector3d delta){
        double clear=sweep(store,box,from,delta);var position=new Vector3d(from).fma(clear,delta);
        if(clear>=.999999)return position;
        var remaining=new Vector3d(delta).mul(1-clear);
        for(int axis:new int[]{1,Math.abs(remaining.x)>=Math.abs(remaining.z)?0:2,Math.abs(remaining.x)>=Math.abs(remaining.z)?2:0}){
            var step=new Vector3d().setComponent(axis,remaining.get(axis));
            position.fma(sweep(store,box,position,step),step);
        }
        return position;
    }
    static double sweep(Store<EntityStore> store,Box box,Vector3d from,Vector3d delta){
        if(!from.isFinite()||!delta.isFinite())return 0;double length=delta.length();if(length<1e-8)return 1;
        var world=store.getExternalData().getWorld();double fraction=1;
        // Native collision cannot load terrain on behalf of a gadget. Full body corners are checked.
        int steps=Math.max(1,(int)Math.ceil(length/.2));
        for(int i=0;i<=steps;i++){
            var point=new Vector3d(from).fma((double)i/steps,delta);
            if(point.y+box.min.y<0||point.y+box.max.y>=ChunkUtil.HEIGHT)return Math.max(0,(double)(i-1)/steps);
            for(double x:new double[]{box.min.x,box.max.x})for(double z:new double[]{box.min.z,box.max.z})
                if(WorldAccess.loaded(world,ChunkUtil.indexChunkFromBlock((int)Math.floor(point.x+x),(int)Math.floor(point.z+z)))==null)return Math.max(0,(double)(i-1)/steps);
        }
        var result=new CollisionResult(false,false);CollisionModule.findCollisions(box,from,delta,false,result,store);
        for(int i=0;i<result.getBlockCollisionCount();i++){
            var hit=result.getBlockCollision(i);
            // Native standing bodies can overlap a floor by its contact tolerance. A separating
            // movement must still be allowed, otherwise the first upward grab can never lift them.
            if(hit.collisionNormal.lengthSquared()>.5&&hit.collisionNormal.dot(delta)>=0)continue;
            fraction=Math.min(fraction,Math.max(0,hit.collisionStart-.015/length));
        }
        return fraction;
    }
    static double rayBody(Vector3d origin,Vector3d direction,Vector3d body,Box box,double range){
        double near=0,far=range;
        for(int axis=0;axis<3;axis++){
            double p=origin.get(axis),d=direction.get(axis),min=body.get(axis)+box.min.get(axis),max=body.get(axis)+box.max.get(axis);
            if(Math.abs(d)<1e-9){if(p<min||p>max)return Double.POSITIVE_INFINITY;continue;}
            double a=(min-p)/d,b=(max-p)/d;if(a>b){double swap=a;a=b;b=swap;}
            near=Math.max(near,a);far=Math.min(far,b);if(near>far)return Double.POSITIVE_INFINITY;
        }
        return near;
    }
}
