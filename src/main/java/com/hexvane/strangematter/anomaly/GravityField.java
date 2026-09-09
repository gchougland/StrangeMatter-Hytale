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
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.ActiveAnimationComponent;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSystems;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerInput;
import com.hypixel.hytale.server.core.modules.entity.player.KnockbackPredictionSystems;
import com.hypixel.hytale.server.core.universe.system.PlayerVelocityInstructionSystem;
import com.hypixel.hytale.protocol.packets.entities.ChangeVelocity;
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
    // Each world owns its mutable state. The immutable field list is published by AnomalyService.
    private static final Map<World,GravityField> ACTIVE=new ConcurrentHashMap<>();
    private final Map<World,List<Field>> fields=new ConcurrentHashMap<>();
    private final Map<Ref<EntityStore>,PlayerLease> players=new ConcurrentHashMap<>();
    private final Map<Ref<EntityStore>,Drift> drifts=new ConcurrentHashMap<>();
    private record Field(UUID id,double x,double y,double z,double age) {
        double squared(Vector3d p){double dx=p.x-x,dy=p.y-y,dz=p.z-z;return dx*dx+dy*dy+dz*dz;}
    }
    private final Map<Ref<EntityStore>,Sample> samples=new ConcurrentHashMap<>();
    private final Map<Ref<EntityStore>,Intent> intents=new ConcurrentHashMap<>();
    private static final class Intent {
        final Vector3d axes=new Vector3d();double age;
    }
    private static final class Sample {
        final Vector3d position,measured=new Vector3d();double elapsed;
        Sample(Vector3d p){position=new Vector3d(p);}
        void update(Vector3d p,double dt){
            elapsed+=dt;
            if(position.distanceSquared(p)>1e-8){
                var delta=new Vector3d(p).sub(position);
                if(elapsed<=.3&&delta.length()<Math.max(2,elapsed*35))measured.set(delta).div(elapsed);else measured.zero();
                position.set(p);elapsed=0;
            }else if(elapsed>.3)measured.zero();
        }
    }
    private static final class PlayerLease {
        final World world;final UUID source;final Vector3d momentum,lastDesired=new Vector3d();
        final ArrayDeque<Vector3d> recentCommands=new ArrayDeque<>();
        final double phase,height;double elapsed,verticalTravel,inputSuppression;Velocity.Instruction pending;
        PlayerLease(World world,Field field,Ref<EntityStore> ref,Vector3d entry,double height){this.world=world;source=field.id;momentum=entry;phase=(ref.hashCode()&255)*.13;this.height=height;}
    }
    private static final class Drift {
        final UUID source; final double height,phase;
        ActiveAnimationComponent animationComponent;String originalAnimation,appliedAnimation;
        final RailStepConfig collision=new RailStepConfig();final RailStepResult result=new RailStepResult();
        Drift(Field field,Ref<EntityStore> ref,Vector3d p){source=field.id;phase=(ref.hashCode()&255)*.13;
            height=Math.max(p.y,Math.min(field.y+.7,p.y+1.3));}
    }
    void publish(World world,List<AnomalyRecord> active) {
        fields.put(world,active.stream().map(a->new Field(a.id,a.x,a.y,a.z,a.age)).toList());
        ACTIVE.put(world,this);cleanupPlayers(world);
        drifts.keySet().removeIf(ref->!ref.isValid());
    }
    void remove(World world,UUID source) {
        fields.computeIfPresent(world,(w,list)->list.stream().filter(f->!f.id.equals(source)).toList());
        cleanupPlayers(world);var store=world.getEntityStore().getStore();
        for(var entry:drifts.entrySet())if(entry.getValue().source.equals(source)&&(!entry.getKey().isValid()||entry.getKey().getStore()==store))restoreDrift(entry.getKey(),store);
    }
    void clear(World world) {
        fields.remove(world);ACTIVE.remove(world,this);var store=world.getEntityStore().getStore();
        for(var entry:players.entrySet())if(entry.getValue().world==world)restore(entry.getKey(),store);
        for(var ref:drifts.keySet())if(!ref.isValid()||ref.getStore()==store)restoreDrift(ref,store);
        samples.keySet().removeIf(ref->!ref.isValid()||ref.getStore()==store);
        intents.keySet().removeIf(ref->!ref.isValid()||ref.getStore()==store);
    }
    private Field at(World world,Vector3d p) {
        Field closest=null;double best=64;
        for(var field:fields.getOrDefault(world,List.of())){double d=field.squared(p);if(d<best){closest=field;best=d;}}
        return closest;
    }
    private boolean eligible(Ref<EntityStore> ref,ComponentAccessor<EntityStore> store){
        var player=store.getComponent(ref,Player.getComponentType());
        var states=store.getComponent(ref,MovementStatesComponent.getComponentType());
        return player!=null&&player.getMountEntityId()==0&&(states==null||!states.getMovementStates().flying)
                &&store.getComponent(ref,DeathComponent.getComponentType())==null&&!hat(ref,store)&&!heldInStasis(ref,store);
    }
    private void cleanupPlayers(World world){
        var store=world.getEntityStore().getStore();
        for(var ref:players.keySet()){
            var lease=players.get(ref);if(lease==null||lease.world!=world)continue;
            var transform=ref.isValid()&&ref.getStore()==store?store.getComponent(ref,TransformComponent.getComponentType()):null;
            if(transform==null||!eligible(ref,store)||at(world,transform.getPosition())==null)restore(ref,store);
        }
    }
    private void movePlayer(Ref<EntityStore> ref,double dt,CommandBuffer<EntityStore> store){
        var world=store.getExternalData().getWorld();var p=store.getComponent(ref,TransformComponent.getComponentType()).getPosition();
        var sample=samples.get(ref);
        if(sample==null){sample=new Sample(p);samples.put(ref,sample);}else sample.update(p,dt);
        var field=at(world,p);var velocity=store.getComponent(ref,Velocity.getComponentType());
        if(field==null||velocity==null||!eligible(ref,store)){restore(ref,store);return;}
        var lease=players.get(ref);
        if(lease==null){
            var entry=GravityMomentum.entry(velocity.getClientVelocity(),sample.measured);
            // A stationary entrant also rises gently from the ground before settling into the wave.
            if(Math.abs(entry.y)<.2)entry.y=.55;
            lease=new PlayerLease(world,field,ref,entry,p.y);players.put(ref,lease);
        }
        // Only remove our own not-yet-flushed command. Other knockback or tool instructions remain.
        if(lease.pending!=null)velocity.getInstructions().remove(lease.pending);
        lease.elapsed+=dt;
        lease.inputSuppression=Math.max(0,lease.inputSuppression-dt);
        if(!velocity.getInstructions().isEmpty()){
            // Native player instructions execute in order. Yield this tick so our Set cannot
            // erase an earlier tool or knockback command, and retain its impulse on later ticks.
            for(var instruction:velocity.getInstructions())if(instruction.getConfig()==null&&GravityMomentum.finite(instruction.getVelocity())){
                switch(instruction.getType()){
                    case Set -> lease.momentum.set(instruction.getVelocity());
                    case Add -> lease.momentum.add(instruction.getVelocity());
                }
            }
            // Configured forces retain their own native channel and must not be counted twice.
            lease.inputSuppression=1;lease.recentCommands.clear();intents.remove(ref);
            lease.verticalTravel+=lease.momentum.y*dt;
            lease.lastDesired.set(lease.momentum);lease.pending=null;return;
        }
        var intent=intents.get(ref);var head=store.getComponent(ref,HeadRotation.getComponentType());
        if(intent!=null&&intent.age<=GravityMomentum.INPUT_LEASE&&head!=null)
            GravityMomentum.steer(lease.momentum,intent.axes,head.getRotation().yaw(),head.getRotation().pitch(),dt);
        lease.lastDesired.set(GravityMomentum.desired(lease.momentum,dt,lease.elapsed,lease.phase));
        lease.verticalTravel+=lease.momentum.y*dt;
        double targetHeight=lease.height+lease.verticalTravel+GravityMomentum.bob(lease.elapsed,lease.phase)-GravityMomentum.bob(0,lease.phase);
        // Reported positions correct accumulated client integration drift. Only bounded velocity
        // is adjusted, so walls and ceilings remain under the native collision controller.
        lease.lastDesired.y+=GravityMomentum.heightCorrection(targetHeight,p.y);
        var manager=store.getComponent(ref,MovementManager.getComponentType());
        boolean inverted=manager!=null&&manager.getSettings()!=null&&manager.getSettings().invertedGravity;
        var command=GravityMomentum.instruction(lease.lastDesired,dt,inverted);
        // A nonnull config targets SplitVelocity's separate applied-force list; it does not
        // replace the falling externalVelocity channel. The native null-config Set does.
        velocity.addInstruction(command,null,ChangeVelocityType.Set);
        lease.pending=velocity.getInstructions().getLast();
        lease.recentCommands.addLast(new Vector3d(command));
        while(lease.recentCommands.size()>6)lease.recentCommands.removeFirst();
    }
    private static boolean hat(Ref<EntityStore> ref,ComponentAccessor<EntityStore> store) {
        var armor=store.getComponent(ref,InventoryComponent.Armor.getComponentType());short head=(short)ItemArmorSlot.Head.ordinal();
        if(armor==null||head>=armor.getInventory().getCapacity())return false;
        var item=armor.getInventory().getItemStack(head);return !ItemStack.isEmpty(item)&&"SM_Tinfoil_Hat".equals(item.getItemId());
    }
    private void restore(Ref<EntityStore> ref,ComponentAccessor<EntityStore> store) {
        var lease=players.remove(ref);if(lease==null||!ref.isValid()||lease.world!=store.getExternalData().getWorld())return;
        intents.remove(ref);
        var velocity=store.getComponent(ref,Velocity.getComponentType());
        if(velocity!=null&&lease.pending!=null)velocity.getInstructions().remove(lease.pending);
        var owner=store.getComponent(ref,PlayerRef.getComponentType());
        // Preserve the final drift as momentum in the ordinary native velocity channel;
        // no flight permission, saved movement setting, transform or avatar was changed.
        if(owner!=null&&lease.world.getWorldConfig().getUuid().equals(owner.getWorldUuid())){
            var v=lease.lastDesired;owner.getPacketHandler().writeNoCache(new ChangeVelocity((float)v.x,(float)v.y,(float)v.z,ChangeVelocityType.Set,null));
        }
    }
    private void floatAnimation(Ref<EntityStore> ref,Drift drift,NPCEntity npc,ComponentAccessor<EntityStore> store){
        var model=store.getComponent(ref,ModelComponent.getComponentType());
        var active=store.getComponent(ref,ActiveAnimationComponent.getComponentType());
        if(model==null||active==null)return;
        var clips=model.getModel().getAnimationSetMap();String chosen=clips.containsKey("FlyIdle")?"FlyIdle":clips.containsKey("Fly")?"Fly":null;
        if(chosen==null)return;
        if(drift.animationComponent==null){
            drift.animationComponent=active;drift.originalAnimation=active.getActiveAnimations()[AnimationSlot.Movement.ordinal()];drift.appliedAnimation=chosen;
            npc.playAnimation(ref,AnimationSlot.Movement,chosen,false,store);
        }
    }
    private void restoreDrift(Ref<EntityStore> ref,ComponentAccessor<EntityStore> store){
        var drift=drifts.remove(ref);if(drift==null||!ref.isValid()||ref.getStore()!=store.getExternalData().getWorld().getEntityStore().getStore())return;
        var active=store.getComponent(ref,ActiveAnimationComponent.getComponentType());var npc=store.getComponent(ref,NPCEntity.getComponentType());
        if(npc!=null&&active!=null&&active==drift.animationComponent&&Objects.equals(active.getActiveAnimations()[AnimationSlot.Movement.ordinal()],drift.appliedAnimation))
            npc.playAnimation(ref,AnimationSlot.Movement,drift.originalAnimation,false,store);
    }
    private void move(Ref<EntityStore> ref,double dt,CommandBuffer<EntityStore> store) {
        var world=store.getExternalData().getWorld();var transform=store.getComponent(ref,TransformComponent.getComponentType());
        var field=transform==null?null:at(world,transform.getPosition());
        if(field==null||store.getComponent(ref,DeathComponent.getComponentType())!=null
                ||store.getComponent(ref,NPCMountComponent.getComponentType())!=null||store.getComponent(ref,Frozen.getComponentType())!=null
                ||heldInStasis(ref,store)) {restoreDrift(ref,store);return;}
        var velocity=store.getComponent(ref,Velocity.getComponentType());if(velocity==null)return;
        var p=transform.getPosition();var drift=drifts.get(ref);
        if(drift==null||!drift.source.equals(field.id)){restoreDrift(ref,store);drift=new Drift(field,ref,p);drifts.put(ref,drift);}
        double strength=Math.max(.15,1-Math.sqrt(field.squared(p))/8);
        double targetY=drift.height+GravityMomentum.bob(field.age,drift.phase);
        // Small tangential drift plus a soft vertical suspension, bounded under one block/second.
        var motion=new Vector3d(Math.cos(field.age*.6+drift.phase)*.24*strength,
                Math.max(-.7,Math.min(.9,(targetY-p.y)*1.4)),Math.sin(field.age*.7+drift.phase)*.24*strength);
        var npc=store.getComponent(ref,NPCEntity.getComponentType());
        if(npc!=null) {
            var role=npc.getRole();if(role==null||!(role.getActiveMotionController() instanceof MotionControllerBase controller))return;
            // This native path performs swept block collisions and triggers. Its per-tick rail flag
            // tells SteeringSystem to skip its later full-gravity integration, while AI still ticks.
            controller.applyRailStep(ref,role,new Vector3d(motion).mul(dt),drift.collision,drift.result,store);
            velocity.set(motion);floatAnimation(ref,drift,npc,store);
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
    /** Observe input before native processing clears the queue; never consume or rewrite it. */
    static final class InputSystem extends EntityTickingSystem<EntityStore> {
        @Override public Query<EntityStore> getQuery(){return Query.and(Player.getComponentType(),PlayerInput.getComponentType(),HeadRotation.getComponentType());}
        @Override public boolean isParallel(int size,int tasks){return false;}
        @Override public Set<Dependency<EntityStore>> getDependencies(){return Set.of(
                new SystemDependency<>(Order.BEFORE,PlayerSystems.ProcessPlayerInput.class),
                new SystemDependency<>(Order.BEFORE,KnockbackPredictionSystems.CaptureKnockbackInput.class));}
        @Override public void tick(float dt,int index,ArchetypeChunk<EntityStore> chunk,Store<EntityStore> store,CommandBuffer<EntityStore> commands){
            var helper=ACTIVE.get(store.getExternalData().getWorld());if(helper==null)return;
            var ref=chunk.getReferenceTo(index);var input=chunk.getComponent(index,PlayerInput.getComponentType());
            var intent=helper.intents.get(ref);if(intent!=null){intent.age+=Math.max(0,dt);if(intent.age>GravityMomentum.INPUT_LEASE)intent.axes.zero();}
            double yaw=chunk.getComponent(index,HeadRotation.getComponentType()).getRotation().yaw();
            var stateComponent=store.getComponent(ref,MovementStatesComponent.getComponentType());
            MovementStates states=stateComponent==null?null:stateComponent.getMovementStates();
            Vector3d wish=null,reported=null;boolean stateUpdate=false;double wishYaw=yaw;
            for(var update:input.getMovementUpdateQueue()){
                if(update instanceof PlayerInput.SetHead head)yaw=head.direction().yaw;
                else if(update instanceof PlayerInput.SetMovementStates movement){states=movement.movementStates();stateUpdate=true;}
                else if(update instanceof PlayerInput.SetClientVelocity velocity)reported=velocity.getVelocity();
                else if(update instanceof PlayerInput.WishMovement movement){wish=new Vector3d(movement.getX(),movement.getY(),movement.getZ());wishYaw=yaw;}
            }
            Vector3d axes=null;var lease=helper.players.get(ref);
            if(wish!=null)axes=GravityMomentum.inputAxes(wish,wishYaw);
            else if(reported!=null){
                // These flags are the client's locomotion state, not a direction. A fresh report
                // supplies only the horizontal residual after removing our own recent motion.
                boolean moving=states!=null&&!states.idle&&!states.horizontalIdle
                        &&(states.walking||states.running||states.sprinting||states.crouching);
                axes=moving&&lease!=null&&lease.inputSuppression<=0
                        ?GravityMomentum.residualAxes(reported,lease.recentCommands,yaw):new Vector3d();
            }else if(stateUpdate&&states!=null&&(states.idle||states.horizontalIdle))axes=new Vector3d();
            if(axes!=null){
                if(intent==null){intent=new Intent();helper.intents.put(ref,intent);}
                intent.axes.set(axes);intent.age=0;
            }
        }
    }
    static final class MotionSystem extends EntityTickingSystem<EntityStore> {
        @Override public Query<EntityStore> getQuery(){return Query.and(TransformComponent.getComponentType(),Velocity.getComponentType(),Query.or(Player.getComponentType(),NPCEntity.getComponentType(),ItemComponent.getComponentType()));}
        @Override public boolean isParallel(int size,int tasks){return false;}
        @Override public Set<Dependency<EntityStore>> getDependencies(){return Set.of(
                new SystemDependency<>(Order.AFTER,ItemPrePhysicsSystem.class),new SystemDependency<>(Order.BEFORE,ItemPhysicsSystem.class),
                new SystemDependency<>(Order.AFTER,AvoidanceSystem.class),new SystemDependency<>(Order.BEFORE,SteeringSystem.class),
                new SystemDependency<>(Order.AFTER,PlayerSystems.ProcessPlayerInput.class),new SystemDependency<>(Order.BEFORE,PlayerVelocityInstructionSystem.class));}
        @Override public void tick(float dt,int index,ArchetypeChunk<EntityStore> chunk,Store<EntityStore> store,CommandBuffer<EntityStore> commands){
            var helper=ACTIVE.get(store.getExternalData().getWorld());if(helper!=null){
                var ref=chunk.getReferenceTo(index);
                if(chunk.getComponent(index,Player.getComponentType())!=null)helper.movePlayer(ref,Math.min(dt,.1),commands);
                else helper.move(ref,Math.min(dt,.25),commands);
            }
        }
    }
    static final class CleanupSystem extends RefSystem<EntityStore> {
        @Override public Query<EntityStore> getQuery(){return Query.or(PlayerRef.getComponentType(),NPCEntity.getComponentType());}
        @Override public void onEntityAdded(Ref<EntityStore> ref,AddReason reason,Store<EntityStore> store,CommandBuffer<EntityStore> commands){}
        @Override public void onEntityRemove(Ref<EntityStore> ref,RemoveReason reason,Store<EntityStore> store,CommandBuffer<EntityStore> commands){
            var helper=ACTIVE.get(store.getExternalData().getWorld());if(helper!=null){helper.restore(ref,commands);helper.restoreDrift(ref,commands);helper.samples.remove(ref);helper.intents.remove(ref);}
        }
    }
}
