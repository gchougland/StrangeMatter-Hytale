package com.hexvane.strangematter.anomaly;

import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.dependency.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.protocol.*;
import com.hypixel.hytale.server.core.entity.Frozen;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemPhysicsSystem;
import com.hypixel.hytale.server.core.modules.entity.item.ItemPrePhysicsSystem;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.movement.controllers.MotionControllerBase;
import com.hypixel.hytale.server.npc.movement.controllers.RailStepConfig;
import com.hypixel.hytale.server.npc.movement.controllers.RailStepResult;
import com.hypixel.hytale.server.npc.systems.AvoidanceSystem;
import com.hypixel.hytale.server.npc.systems.SteeringSystem;
import org.joml.Vector3d;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Native zero-G locomotion. No persisted physics, frozen flags, avatar changes or teleports. */
final class GravityField {
    static final float HORIZONTAL_SPEED=3.6f, VERTICAL_SPEED=2.2f;
    // Each world owns its mutable state. The immutable field list is published by AnomalyService.
    private static final Map<World,GravityField> ACTIVE=new ConcurrentHashMap<>();
    private final Map<World,List<Field>> fields=new ConcurrentHashMap<>();
    private final Map<Ref<EntityStore>,PlayerLease> players=new ConcurrentHashMap<>();
    private final Map<Ref<EntityStore>,Drift> drifts=new ConcurrentHashMap<>();
    private record Field(UUID id,double x,double y,double z,double age) {
        double squared(Vector3d p){double dx=p.x-x,dy=p.y-y,dz=p.z-z;return dx*dx+dy*dy+dz*dz;}
    }
    private record PlayerLease(World world,MovementManager manager,MovementSettings settings,
                               FlyMode fly,float horizontal,float vertical,boolean flying) {}
    private static final class Drift {
        final UUID source; final double height,phase;
        final RailStepConfig collision=new RailStepConfig();final RailStepResult result=new RailStepResult();
        Drift(Field field,Ref<EntityStore> ref,Vector3d p){source=field.id;phase=(ref.hashCode()&255)*.13;
            height=Math.max(p.y,Math.min(field.y+.7,p.y+1.3));}
    }
    void publish(World world,List<AnomalyRecord> active) {
        fields.put(world,active.stream().map(a->new Field(a.id,a.x,a.y,a.z,a.age)).toList());
        ACTIVE.put(world,this);updatePlayers(world);
        drifts.keySet().removeIf(ref->!ref.isValid());
    }
    void remove(World world,UUID source) {
        fields.computeIfPresent(world,(w,list)->list.stream().filter(f->!f.id.equals(source)).toList());
        updatePlayers(world);
        drifts.entrySet().removeIf(e->e.getValue().source.equals(source)&&(!e.getKey().isValid()||e.getKey().getStore()==world.getEntityStore().getStore()));
    }
    void clear(World world) {
        fields.remove(world);ACTIVE.remove(world,this);var store=world.getEntityStore().getStore();
        for(var entry:players.entrySet())if(entry.getValue().world==world)restore(entry.getKey(),store);
        drifts.keySet().removeIf(ref->!ref.isValid()||ref.getStore()==store);
    }
    private Field at(World world,Vector3d p) {
        Field closest=null;double best=64;
        for(var field:fields.getOrDefault(world,List.of())){double d=field.squared(p);if(d<best){closest=field;best=d;}}
        return closest;
    }
    private void updatePlayers(World world) {
        var store=world.getEntityStore().getStore();
        for(var owner:world.getPlayerRefs()) {
            var ref=owner.getReference();if(ref==null||!ref.isValid()||ref.getStore()!=store)continue;
            var player=store.getComponent(ref,Player.getComponentType());
            var transform=store.getComponent(ref,TransformComponent.getComponentType());
            var manager=store.getComponent(ref,MovementManager.getComponentType());
            var states=store.getComponent(ref,MovementStatesComponent.getComponentType());
            boolean eligible=player!=null&&transform!=null&&manager!=null&&manager.getSettings()!=null&&states!=null
                    &&player.getGameMode()!=GameMode.Creative&&player.getMountEntityId()==0
                    &&store.getComponent(ref,DeathComponent.getComponentType())==null&&!hat(ref,store)
                    &&at(world,transform.getPosition())!=null;
            if(!eligible){restore(ref,store);continue;}
            var lease=players.get(ref);
            if(lease!=null&&(lease.manager!=manager||lease.settings!=manager.getSettings())){players.remove(ref,lease);lease=null;}
            if(lease==null) {
                var settings=manager.getSettings();
                lease=new PlayerLease(world,manager,settings,settings.fly,settings.horizontalFlySpeed,settings.verticalFlySpeed,states.getMovementStates().flying);
                players.put(ref,lease);
                settings.fly=FlyMode.Forced;settings.horizontalFlySpeed=HORIZONTAL_SPEED;settings.verticalFlySpeed=VERTICAL_SPEED;
                manager.update(owner.getPacketHandler());
                Player.applyMovementStates(ref,new SavedMovementStates(true),states.getMovementStates(),store);
                // One gentle lift breaks ground contact; native fly locomotion owns every later step.
                var velocity=store.getComponent(ref,Velocity.getComponentType());
                if(velocity!=null)velocity.addInstruction(new Vector3d(0,.8,0),null,ChangeVelocityType.Add);
            }
        }
        for(var entry:players.entrySet())if(entry.getValue().world==world&&(!entry.getKey().isValid()||entry.getKey().getStore()!=store))players.remove(entry.getKey(),entry.getValue());
    }
    private static boolean hat(Ref<EntityStore> ref,ComponentAccessor<EntityStore> store) {
        var armor=store.getComponent(ref,InventoryComponent.Armor.getComponentType());short head=(short)ItemArmorSlot.Head.ordinal();
        if(armor==null||head>=armor.getInventory().getCapacity())return false;
        var item=armor.getInventory().getItemStack(head);return !ItemStack.isEmpty(item)&&"SM_Tinfoil_Hat".equals(item.getItemId());
    }
    private void restore(Ref<EntityStore> ref,ComponentAccessor<EntityStore> store) {
        var lease=players.remove(ref);if(lease==null||!ref.isValid()||lease.world!=store.getExternalData().getWorld())return;
        var manager=store.getComponent(ref,MovementManager.getComponentType());
        if(manager!=lease.manager||manager.getSettings()!=lease.settings)return;
        var settings=manager.getSettings();boolean ownsFlight=settings.fly==FlyMode.Forced;
        if(ownsFlight)settings.fly=lease.fly;
        if(settings.horizontalFlySpeed==HORIZONTAL_SPEED)settings.horizontalFlySpeed=lease.horizontal;
        if(settings.verticalFlySpeed==VERTICAL_SPEED)settings.verticalFlySpeed=lease.vertical;
        var owner=store.getComponent(ref,PlayerRef.getComponentType());
        if(owner!=null)manager.update(owner.getPacketHandler());
        var states=store.getComponent(ref,MovementStatesComponent.getComponentType());
        if(ownsFlight&&states!=null&&owner!=null)Player.applyMovementStates(ref,new SavedMovementStates(lease.flying),states.getMovementStates(),store);
    }
    private void move(Ref<EntityStore> ref,double dt,CommandBuffer<EntityStore> store) {
        var world=store.getExternalData().getWorld();var transform=store.getComponent(ref,TransformComponent.getComponentType());
        var field=transform==null?null:at(world,transform.getPosition());
        if(field==null||store.getComponent(ref,DeathComponent.getComponentType())!=null
                ||store.getComponent(ref,NPCMountComponent.getComponentType())!=null||store.getComponent(ref,Frozen.getComponentType())!=null
                ||heldInStasis(ref,store)) {drifts.remove(ref);return;}
        var velocity=store.getComponent(ref,Velocity.getComponentType());if(velocity==null)return;
        var p=transform.getPosition();var drift=drifts.get(ref);
        if(drift==null||!drift.source.equals(field.id)){drift=new Drift(field,ref,p);drifts.put(ref,drift);}
        double strength=Math.max(.15,1-Math.sqrt(field.squared(p))/8);
        double targetY=drift.height+.22*Math.sin(field.age*.8+drift.phase);
        // Small tangential drift plus a soft vertical suspension, bounded under one block/second.
        var motion=new Vector3d(Math.cos(field.age*.6+drift.phase)*.24*strength,
                Math.max(-.7,Math.min(.9,(targetY-p.y)*1.4)),Math.sin(field.age*.7+drift.phase)*.24*strength);
        var npc=store.getComponent(ref,NPCEntity.getComponentType());
        if(npc!=null) {
            var role=npc.getRole();if(role==null||!(role.getActiveMotionController() instanceof MotionControllerBase controller))return;
            // This native path performs swept block collisions and triggers. Its per-tick rail flag
            // tells SteeringSystem to skip its later full-gravity integration, while AI still ticks.
            controller.applyRailStep(ref,role,new Vector3d(motion).mul(dt),drift.collision,drift.result,store);
            velocity.set(motion);
        } else if(store.getComponent(ref,ItemComponent.getComponentType())!=null) {
            // Runs after native gravity and before native collision integration. Never changes
            // serialized PhysicsValues, pickup delays, item metadata, or the item's expiry clock.
            motion.x+=velocity.getX()*Math.pow(.5,dt);motion.z+=velocity.getZ()*Math.pow(.5,dt);
            double horizontal=Math.hypot(motion.x,motion.z);if(horizontal>1){motion.x/=horizontal;motion.z/=horizontal;}
            velocity.set(motion);
        }
    }
    private static boolean heldInStasis(Ref<EntityStore> ref,ComponentAccessor<EntityStore> store){
        var effects=store.getComponent(ref,EffectControllerComponent.getComponentType());
        var active=effects==null?null:effects.getActiveEffects().get(EntityEffect.getAssetMap().getIndex("SM_Stasis_Hold"));
        return active!=null&&(active.isInfinite()||active.getRemainingDuration()>0);
    }
    static final class MotionSystem extends EntityTickingSystem<EntityStore> {
        @Override public Query<EntityStore> getQuery(){return Query.and(TransformComponent.getComponentType(),Velocity.getComponentType(),Query.or(NPCEntity.getComponentType(),ItemComponent.getComponentType()));}
        @Override public boolean isParallel(int size,int tasks){return false;}
        @Override public Set<Dependency<EntityStore>> getDependencies(){return Set.of(
                new SystemDependency<>(Order.AFTER,ItemPrePhysicsSystem.class),new SystemDependency<>(Order.BEFORE,ItemPhysicsSystem.class),
                new SystemDependency<>(Order.AFTER,AvoidanceSystem.class),new SystemDependency<>(Order.BEFORE,SteeringSystem.class));}
        @Override public void tick(float dt,int index,ArchetypeChunk<EntityStore> chunk,Store<EntityStore> store,CommandBuffer<EntityStore> commands){
            var helper=ACTIVE.get(store.getExternalData().getWorld());if(helper!=null)helper.move(chunk.getReferenceTo(index),Math.min(dt,.25),commands);
        }
    }
    static final class CleanupSystem extends RefSystem<EntityStore> {
        @Override public Query<EntityStore> getQuery(){return PlayerRef.getComponentType();}
        @Override public void onEntityAdded(Ref<EntityStore> ref,AddReason reason,Store<EntityStore> store,CommandBuffer<EntityStore> commands){}
        @Override public void onEntityRemove(Ref<EntityStore> ref,RemoveReason reason,Store<EntityStore> store,CommandBuffer<EntityStore> commands){
            var helper=ACTIVE.get(store.getExternalData().getWorld());if(helper!=null)helper.restore(ref,commands);
        }
    }
}
