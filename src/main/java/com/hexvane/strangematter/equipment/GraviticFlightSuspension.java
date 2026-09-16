package com.hexvane.strangematter.equipment;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.dependency.*;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.effect.*;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.movement.controllers.*;
import com.hypixel.hytale.server.npc.systems.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Fly applies gravity even in forced-motion mode. Suppress that one native steering pass,
 * then restore the exact controller setting; no gravity override or lease is serialized.
 */
public final class GraviticFlightSuspension {
    private static final Map<Ref<EntityStore>,ActiveEntityEffect> leases=new ConcurrentHashMap<>();
    private static final Map<Store<EntityStore>,Map<MotionControllerFly,Double>> pending=new IdentityHashMap<>();
    private GraviticFlightSuspension(){}
    public static void register(IComponentRegistry<EntityStore> registry){registry.registerSystem(new Before());registry.registerSystem(new After());}
    static void track(Ref<EntityStore> target,ActiveEntityEffect effect){leases.put(target,effect);}
    static void cleanup(Store<EntityStore> store){restore(store);leases.keySet().removeIf(ref->ref.getStore()==store);}
    static void release(Ref<EntityStore> target){
        leases.remove(target);
        if(!target.isValid())return;var store=target.getStore();var npc=store.getComponent(target,NPCEntity.getComponentType());
        if(npc!=null&&npc.getRole()!=null)restore(store,npc.getRole().getActiveMotionController());
    }
    private static synchronized void suspend(Store<EntityStore> store,MotionControllerFly motion){
        pending.computeIfAbsent(store,ignored->new IdentityHashMap<>()).putIfAbsent(motion,motion.getGravity());motion.setGravity(0);
    }
    private static synchronized void restore(Store<EntityStore> store,MotionController motion){
        var values=pending.get(store);if(values==null)return;var gravity=values.remove(motion);
        if(gravity!=null&&motion instanceof MotionControllerFly flight)flight.setGravity(gravity);if(values.isEmpty())pending.remove(store);
    }
    private static synchronized void restore(Store<EntityStore> store){var values=pending.remove(store);if(values!=null)values.forEach(MotionControllerFly::setGravity);}
    public static final class Before extends TickingSystem<EntityStore>{
        @Override public Set<Dependency<EntityStore>> getDependencies(){return Set.of(new SystemDependency<>(Order.AFTER,AvoidanceSystem.class),new SystemDependency<>(Order.AFTER,NPCVelocityInstructionSystem.class),new SystemDependency<>(Order.AFTER,com.hypixel.hytale.server.core.entity.knockback.KnockbackSystems.ApplyKnockback.class),new SystemDependency<>(Order.BEFORE,SteeringSystem.class));}
        @Override public void tick(float dt,int systemIndex,Store<EntityStore> store){
            restore(store); // Also repairs a prior interrupted steering pass before any new lease.
            int index=EntityEffect.getAssetMap().getIndex(GraviticManipulator.EFFECT);if(index<0)return;
            for(var entry:leases.entrySet()){
                var ref=entry.getKey();if(!ref.isValid()){leases.remove(ref,entry.getValue());continue;}if(ref.getStore()!=store)continue;
                var controller=store.getComponent(ref,EffectControllerComponent.getComponentType());var active=controller==null?null:controller.getActiveEffects().get(index);
                var npc=store.getComponent(ref,NPCEntity.getComponentType());var current=npc==null||npc.getRole()==null?null:npc.getRole().getActiveMotionController();
                if(active!=entry.getValue()||!active.isInfinite()&&active.getRemainingDuration()<=0||store.getComponent(ref,DeathComponent.getComponentType())!=null){
                    leases.remove(ref,entry.getValue());
                    // Expiry must also end our forced-steering mode. Preserve any distinct
                    // impulse another system has supplied since our last suspension refresh.
                    if(current!=null&&current.getExternalVelocity().equals(new org.joml.Vector3d(0,.00001,0))&&current.getCombinedExternalVelocityLength()<.000011)current.clearExternalForces();
                    continue;
                }
                if(!(current instanceof MotionControllerFly motion))continue;
                suspend(store,motion);motion.clearExternalForces();motion.setVelocity(new org.joml.Vector3d(0,.00001,0),null,true);
            }
        }
    }
    public static final class After extends TickingSystem<EntityStore>{
        @Override public Set<Dependency<EntityStore>> getDependencies(){return Set.of(new SystemDependency<>(Order.AFTER,SteeringSystem.class));}
        @Override public void tick(float dt,int systemIndex,Store<EntityStore> store){restore(store);}
    }
}
