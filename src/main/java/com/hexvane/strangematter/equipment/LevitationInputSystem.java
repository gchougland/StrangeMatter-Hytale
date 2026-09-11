package com.hexvane.strangematter.equipment;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.dependency.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.modules.entity.player.KnockbackPredictionSystems;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerInput;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSystems;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Observes native movement intent without consuming input or changing its normal processing. */
public final class LevitationInputSystem extends EntityTickingSystem<EntityStore> {
    private static final double INPUT_LEASE = .35;
    // Inner maps and samples are accessed exclusively by their own world thread.
    private static final Map<World,Map<Ref<EntityStore>,Intent>> WORLDS = new ConcurrentHashMap<>();
    private static final class Intent { boolean moving; double age; }

    @Override public Query<EntityStore> getQuery(){return Query.and(Player.getComponentType(),PlayerInput.getComponentType(),Velocity.getComponentType());}
    @Override public boolean isParallel(int size,int tasks){return false;}
    @Override public Set<Dependency<EntityStore>> getDependencies(){return Set.of(
            new SystemDependency<>(Order.BEFORE,PlayerSystems.ProcessPlayerInput.class),
            new SystemDependency<>(Order.BEFORE,KnockbackPredictionSystems.CaptureKnockbackInput.class));}
    @Override public void tick(float dt,int index,ArchetypeChunk<EntityStore> chunk,Store<EntityStore> store,CommandBuffer<EntityStore> commands){
        var samples=WORLDS.computeIfAbsent(store.getExternalData().getWorld(),world->new HashMap<>());
        var ref=chunk.getReferenceTo(index);var intent=samples.get(ref);
        if(intent==null){samples.keySet().removeIf(old->!old.isValid());intent=new Intent();samples.put(ref,intent);}
        intent.age+=Math.max(0,dt);if(intent.age>INPUT_LEASE)intent.moving=false;
        var statesComponent=store.getComponent(ref,MovementStatesComponent.getComponentType());
        MovementStates states=statesComponent==null?null:statesComponent.getMovementStates();
        Vector3d wish=null,reported=null;boolean changedStates=false;
        for(var update:chunk.getComponent(index,PlayerInput.getComponentType()).getMovementUpdateQueue()){
            if(update instanceof PlayerInput.SetMovementStates movement){states=movement.movementStates();changedStates=true;}
            else if(update instanceof PlayerInput.SetClientVelocity movement)reported=movement.getVelocity();
            else if(update instanceof PlayerInput.WishMovement movement)wish=new Vector3d(movement.getX(),movement.getY(),movement.getZ());
        }
        if(wish!=null){
            intent.moving=wish.isFinite()&&wish.lengthSquared()>.0001;intent.age=0;
        }else if(reported!=null||changedStates){
            // Ordinary client packets may omit WishMovement. Locomotion flags, together
            // with a fresh report, distinguish walking from passive airborne momentum.
            var velocity=reported==null?chunk.getComponent(index,Velocity.getComponentType()).getClientVelocity():reported;
            intent.moving=states!=null&&!states.idle&&!states.horizontalIdle
                    &&(states.walking||states.running||states.sprinting||states.crouching)
                    &&velocity.isFinite()&&(velocity.x*velocity.x+velocity.z*velocity.z)>.0025;
            intent.age=0;
        }
    }
    static boolean wantsToMove(Store<EntityStore> store,Ref<EntityStore> ref){
        var samples=WORLDS.get(store.getExternalData().getWorld());var intent=samples==null?null:samples.get(ref);
        return intent!=null&&intent.moving&&intent.age<=INPUT_LEASE;
    }
    static void cleanup(World world){WORLDS.remove(world);}
    /** Removal covers disconnects and native transfers before a destination receives fresh input. */
    public static final class RemoveSystem extends RefSystem<EntityStore> {
        @Override public Query<EntityStore> getQuery(){return Player.getComponentType();}
        @Override public void onEntityAdded(Ref<EntityStore> ref,AddReason reason,Store<EntityStore> store,CommandBuffer<EntityStore> commands){}
        @Override public void onEntityRemove(Ref<EntityStore> ref,RemoveReason reason,Store<EntityStore> store,CommandBuffer<EntityStore> commands){
            var samples=WORLDS.get(store.getExternalData().getWorld());if(samples!=null)samples.remove(ref);
        }
    }
}
