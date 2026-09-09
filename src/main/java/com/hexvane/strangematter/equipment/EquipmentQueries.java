package com.hexvane.strangematter.equipment;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import org.joml.Vector3d;
import java.util.*;

/** Spatial indexes store entity origins; narrow-phase checks include full creature bodies and item drops. */
final class EquipmentQueries {
    /** Presentation only: place a hand-held flash beyond the first-person near clip. */
    static Vector3d handheldOrigin(Vector3d eye,Vector3d direction){
        var forward=new Vector3d(direction);if(forward.lengthSquared()<1e-12)forward.set(0,0,-1);else forward.normalize();
        var right=new Vector3d(-forward.z,0,forward.x);if(right.lengthSquared()<1e-12)right.set(1,0,0);else right.normalize();
        return new Vector3d(eye).add(forward.mul(.6)).add(right.mul(.18)).add(0,-.18,0);
    }
    static List<Ref<EntityStore>> inBox(Store<EntityStore> store,Vector3d min,Vector3d max,boolean items){
        var candidates=new ArrayList<>(TargetUtil.getAllEntitiesInBox(new Vector3d(min).sub(4,4,4),new Vector3d(max).add(4,4,4),store));
        if(items)store.getResource(EntityModule.get().getItemSpatialResourceType()).getSpatialStructure().collectBox(new Vector3d(min).sub(1,1,1),new Vector3d(max).add(1,1,1),candidates);
        candidates.removeIf(ref->{
            if(ref==null||!ref.isValid())return true;
            var transform=store.getComponent(ref,TransformComponent.getComponentType());var box=store.getComponent(ref,BoundingBox.getComponentType());
            return transform==null||!intersects(transform.getPosition(),box==null?null:box.getBoundingBox(),min,max);
        });
        return new ArrayList<>(new LinkedHashSet<>(candidates));
    }
    static boolean intersects(Vector3d origin,Box box,Vector3d min,Vector3d max){
        if(box==null)return origin.x>=min.x&&origin.x<=max.x&&origin.y>=min.y&&origin.y<=max.y&&origin.z>=min.z&&origin.z<=max.z;
        return origin.x+box.max.x>=min.x&&origin.x+box.min.x<=max.x&&origin.y+box.max.y>=min.y&&origin.y+box.min.y<=max.y&&origin.z+box.max.z>=min.z&&origin.z+box.min.z<=max.z;
    }
}
