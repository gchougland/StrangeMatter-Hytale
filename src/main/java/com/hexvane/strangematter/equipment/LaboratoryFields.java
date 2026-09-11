package com.hexvane.strangematter.equipment;

import com.hexvane.strangematter.util.WorldAccess;

import com.hexvane.strangematter.machine.MachineService;
import com.hexvane.strangematter.machine.MachineState;
import com.hexvane.strangematter.effects.GadgetEffects;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.collision.CollisionModule;
import com.hypixel.hytale.server.core.modules.collision.CollisionResult;
import com.hypixel.hytale.protocol.BlockMaterial;
import com.hypixel.hytale.protocol.ChangeVelocityType;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.OverlapBehavior;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import org.joml.Vector3d;
import org.joml.Vector3i;
import java.util.*;

/** Finite native effects expire automatically; temporal blocks and their remaining lifetimes persist. */
final class LaboratoryFields {
    private final MachineService machines;
    private final Map<String,Double> ticks=new HashMap<>();
    private final Map<String,Specimens> specimens=new HashMap<>();
    private final Map<String,Boolean> liftDirections=new HashMap<>();
    private static final class Specimens { Ref<EntityStore> mob,item; }
    private final Random random=new Random();
    LaboratoryFields(MachineService machines){this.machines=machines;}

    /** Original chrono volume: guaranteed core at r<=1.7, 70% ragged shell to r<=2.5. */
    synchronized void temporal(World world,Vector3i center){
        for(var offset:temporalOffsets(random)){
            var pos=new Vector3i(center).add(offset);
            if(pos.y<0||pos.y>=ChunkUtil.HEIGHT||WorldAccess.inMemory(world,ChunkUtil.indexChunkFromBlock(pos.x,pos.z))==null)continue;
            var block=world.getBlockType(pos.x,pos.y,pos.z);
            if(block==null||block.getMaterial()!=BlockMaterial.Empty||block.getId().equals("SM_Time_Dilation_Block"))continue;
            world.setBlock(pos.x,pos.y,pos.z,"SM_Time_Dilation_Block");
            var placed=world.getBlockType(pos.x,pos.y,pos.z);
            if(placed!=null&&placed.getId().equals("SM_Time_Dilation_Block")){
                var state=machines.register(world,pos,placed.getId());state.age=0;state.progress=200+random.nextInt(401);
            }
        }
        machines.save();
    }
    static List<Vector3i> temporalOffsets(Random random){
        var positions=new ArrayList<Vector3i>();
        for(int x=-2;x<=2;x++)for(int y=-2;y<=2;y++)for(int z=-2;z<=2;z++){
            double squared=x*x+y*y+z*z;if(squared<=6.25&&(squared<=2.89||random.nextDouble()<.7))positions.add(new Vector3i(x,y,z));
        }
        return positions;
    }
    synchronized void tick(World world,double dt){
        double old=ticks.getOrDefault(world.getName(),0d),now=old+dt;ticks.put(world.getName(),now);
        var store=world.getEntityStore().getStore();boolean visual=(int)(old*4)!=(int)(now*4),effect=(int)(old*2)!=(int)(now*2);
        int elapsedTicks=(int)(now*20)-(int)(old*20);boolean temporalChanged=false;
        var active=new HashSet<String>();var slowed=new HashSet<Ref<EntityStore>>();
        for(var m:machines.inWorld(world)) {
            if(!m.enabled||!machines.valid(world,m))continue;
            switch(m.id){
                case "SM_Levitation_Pad"->lift(world,m,visual,dt);
                case "SM_Stasis_Projector"->{active.add(m.key());stasis(world,m,now,visual,effect);}
                case "SM_Time_Dilation_Block"->{
                    if(m.progress<=0)m.progress=200+random.nextInt(401);
                    m.age+=elapsedTicks;temporalChanged=true;
                    if(m.age>=m.progress){world.setBlock(m.x,m.y,m.z,"Empty");machines.removed(world,m.block());}
                    else {
                        for(var ref:EquipmentQueries.inBox(store,new Vector3d(m.x,m.y,m.z),new Vector3d(m.x+1,m.y+1,m.z+1),true))slowed.add(ref);
                        if(visual&&((m.x+m.y+m.z)&3)==0)GadgetEffects.particle(world,"SM_Temporal_Field",m.center());
                    }
                }
            }
        }
        for(var ref:slowed){
            if(effect)apply(store,ref,"SM_Temporal_Slow");
            var velocity=store.getComponent(ref,Velocity.getComponentType());
            if(velocity!=null&&store.getComponent(ref,Player.getComponentType())==null){
                double damping=Math.pow(.3,elapsedTicks);
                if(store.getComponent(ref,NPCEntity.getComponentType())==null)velocity.set(new Vector3d(velocity.getVelocity()).mul(damping));
                else velocity.set(velocity.getX(),velocity.getY()*damping,velocity.getZ());
            }
        }
        specimens.entrySet().removeIf(e->e.getKey().startsWith(world.getName()+":")&&!active.contains(e.getKey()));
        // Saving remaining simulation ticks avoids permanent blobs after restart or chunk unloading.
        if(temporalChanged&&(int)old/5!=(int)now/5)machines.save();
    }
    private static final double LIFT_SPEED = 4, LIFT_SKIN = .02;
    private static final Box BEAM_PROBE = new Box(-.001,0,-.001,.001,.001,.001);
    private void lift(World world,MachineState m,boolean visual,double dt){
        var store=world.getEntityStore().getStore();
        double maxFeet=Math.min(ChunkUtil.HEIGHT-1,(double)m.y+machines.config.levitationHeight);
        double shaftTop=liftShaftTop(store,m,maxFeet);
        if(shaftTop<=m.y+.2)return;
        var bottom=new Vector3d(m.x,m.y+.15,m.z);var top=new Vector3d(m.x+1,shaftTop,m.z+1);
        double bodyCeiling=shaftTop<maxFeet+1-1e-6?shaftTop:Double.POSITIVE_INFINITY;
        for(var ref:EquipmentQueries.inBox(store,bottom,top,true))applyLift(store,ref,m.x+.5,m.z+.5,m.y,maxFeet,bodyCeiling,m.ascending,dt);
        Boolean previous=liftDirections.put(m.key(),m.ascending);var beamOrigin=new Vector3d(m.x+.5,m.y+.2,m.z+.5);
        if(previous==null||previous!=m.ascending)GadgetEffects.sound(world,(m.ascending?"SM_Levitation_Up":"SM_Levitation_Down")+"_SFX",beamOrigin);
        if(visual)GadgetEffects.column(world,beamOrigin,Math.max(0,shaftTop-beamOrigin.y),m.ascending);
    }
    /** Actual shaft surface for both particle directions; native shapes also respect open doors and slabs. */
    static double liftShaftTop(Store<EntityStore> store,MachineState m,double maxFeet){
        var origin=new Vector3d(m.x+.5,m.y+1,m.z+.5);
        double distance=Math.max(0,Math.min(ChunkUtil.HEIGHT-1,maxFeet+1)-origin.y);
        return origin.y+clearVerticalDistance(store,BEAM_PROBE,origin,distance);
    }
    /** Velocity only: Hytale owns player movement, collision response and position updates. */
    static void applyLift(Store<EntityStore> store,Ref<EntityStore> ref,double centerX,double centerZ,double floor,double maxFeet,double shaftTop,boolean ascending,double dt){
        if(ref==null||!ref.isValid()||!Double.isFinite(dt)||dt<=0)return;
        var velocity=store.getComponent(ref,Velocity.getComponentType());var transform=store.getComponent(ref,TransformComponent.getComponentType());
        var bounds=store.getComponent(ref,BoundingBox.getComponentType());
        if(velocity==null||transform==null||bounds==null)return;
        var player=store.getComponent(ref,Player.getComponentType());if(player!=null&&player.getGameMode()==GameMode.Creative)return;
        var box=bounds.getBoundingBox();var origin=transform.getPosition();
        // Origin is not universally a feet position. Use the native body's actual min/max.
        double target=ascending?Math.min(maxFeet,shaftTop-box.max.y-LIFT_SKIN):floor-box.min.y;
        double requested=ascending?Math.max(0,target-origin.y):Math.min(0,target-origin.y);
        double travel=clearVerticalDistance(store,box,origin,requested);
        // At contact, native gravity and collision settling take over. Reissuing even
        // tiny downward Set instructions wakes the body and repeats landing effects.
        if(!ascending&&Math.abs(travel)<1e-6)return;
        double y=Math.copySign(Math.min(LIFT_SPEED,Math.abs(travel)/Math.max(.001,dt)),travel);
        if(player!=null){
            var v=new Vector3d(velocity.getClientVelocity());
            if(Math.abs(travel)>1e-6&&!LevitationInputSystem.wantsToMove(store,ref)){
                // A damped, speed-limited correction catches released entry momentum.
                // It yields entirely to native input and stops at the shaft endpoint.
                double targetX=(centerX-origin.x)*2.4,targetZ=(centerZ-origin.z)*2.4;
                double targetSpeed=Math.hypot(targetX,targetZ);
                if(targetSpeed>.8){targetX*=.8/targetSpeed;targetZ*=.8/targetSpeed;}
                double blend=1-Math.exp(-10*dt);
                v.x+=(targetX-v.x)*blend;v.z+=(targetZ-v.z)*blend;
                double speed=Math.hypot(v.x,v.z);if(speed>1){v.x/=speed;v.z/=speed;}
                // A body may enter with only a sliver overlapping the shaft. Do not
                // let leftover outward drift erase that overlap before the next tick.
                // This only reduces passive components; deliberate input bypasses it.
                if(v.x>0)v.x=Math.min(v.x,Math.max(0,(centerX+.5-origin.x-box.min.x-LIFT_SKIN)/dt));
                else if(v.x<0)v.x=Math.max(v.x,Math.min(0,(centerX-.5-origin.x-box.max.x+LIFT_SKIN)/dt));
                if(v.z>0)v.z=Math.min(v.z,Math.max(0,(centerZ+.5-origin.z-box.min.z-LIFT_SKIN)/dt));
                else if(v.z<0)v.z=Math.max(v.z,Math.min(0,(centerZ-.5-origin.z-box.max.z+LIFT_SKIN)/dt));
            }
            v.y=y;velocity.addInstruction(v,null,ChangeVelocityType.Set);
        }
        else velocity.set(velocity.getX(),y,velocity.getZ());
    }
    /** A full swept collider includes neighbouring blocks touched by wide or off-centre bodies. */
    private static double clearVerticalDistance(Store<EntityStore> store,Box box,Vector3d origin,double distance){
        if(Math.abs(distance)<1e-9)return 0;
        var result=new CollisionResult(false,false);
        CollisionModule.findCollisions(box,origin,new Vector3d(0,distance,0),false,result,store);
        double clear=Math.abs(distance);
        for(int i=0;i<result.getBlockCollisionCount();i++){
            var hit=result.getBlockCollision(i);
            // Contact with the floor must not prevent moving away from it, or vice versa.
            if(!hit.overlapping&&hit.collisionNormal.y*distance>=0)continue;
            clear=Math.min(clear,Math.max(0,Math.abs(distance)*Math.max(0,hit.collisionStart)-LIFT_SKIN));
        }
        return Math.copySign(clear,distance);
    }
    private void stasis(World world,MachineState m,double now,boolean visual,boolean effects){
        var store=world.getEntityStore().getStore();var center=m.center().add(0,.25,0);var pair=specimens.computeIfAbsent(m.key(),k->new Specimens());
        var oldMob=pair.mob;var oldItem=pair.item;
        if(!usable(store,pair.mob,center))pair.mob=null;if(!usable(store,pair.item,center))pair.item=null;
        for(var candidate:EquipmentQueries.inBox(store,new Vector3d(center).sub(.5,.5,.5),new Vector3d(center).add(.5,.5,.5),true)){
            if(!usable(store,candidate,center)||taken(candidate))continue;
            if(pair.mob==null&&store.getComponent(candidate,NPCEntity.getComponentType())!=null)pair.mob=candidate;
            else if(pair.item==null&&store.getComponent(candidate,ItemComponent.getComponentType())!=null)pair.item=candidate;
        }
        var floating=new Vector3d(center).add(0,Math.sin(now*2)*.1,0);
        hold(store,pair.mob,floating,effects);hold(store,pair.item,floating,effects);
        if((pair.mob!=null&&pair.mob!=oldMob)||(pair.item!=null&&pair.item!=oldItem))GadgetEffects.sound(world,"SM_Stasis_Hold_SFX",floating);
        if(visual){GadgetEffects.beam(world,"SM_Stasis_Beam",new Vector3d(m.x+.5,m.y+.2,m.z+.5),floating);if(pair.mob!=null||pair.item!=null)GadgetEffects.particle(world,"SM_Stasis_Hold",floating);}
    }
    private boolean taken(Ref<EntityStore> ref){return specimens.values().stream().anyMatch(s->ref.equals(s.mob)||ref.equals(s.item));}
    private static boolean usable(Store<EntityStore> store,Ref<EntityStore> ref,Vector3d center){
        if(ref==null||!ref.isValid()||ref.getStore()!=store||store.getComponent(ref,Player.getComponentType())!=null||store.getComponent(ref,DeathComponent.getComponentType())!=null||store.getComponent(ref,NPCMountComponent.getComponentType())!=null)return false;
        var transform=store.getComponent(ref,TransformComponent.getComponentType());return transform!=null&&transform.getPosition().distanceSquared(center)<=16&&store.getComponent(ref,Velocity.getComponentType())!=null;
    }
    private void hold(Store<EntityStore> store,Ref<EntityStore> ref,Vector3d floating,boolean effects){
        if(ref==null)return;
        if(effects)apply(store,ref,"SM_Stasis_Hold");
        // The source intentionally holds only captured specimens at a bobbing presentation point.
        store.getComponent(ref,TransformComponent.getComponentType()).setPosition(new Vector3d(floating));
        store.getComponent(ref,Velocity.getComponentType()).setZero();
        var item=store.getComponent(ref,ItemComponent.getComponentType());if(item!=null)item.setPickupDelay(.7f);
    }
    private void apply(Store<EntityStore> store,Ref<EntityStore> ref,String id){
        var effect=EntityEffect.getAssetMap().getAsset(id);var controller=store.getComponent(ref,EffectControllerComponent.getComponentType());
        if(effect!=null&&controller!=null)controller.addEffect(ref,effect,.7f,OverlapBehavior.OVERWRITE,store);
    }
    void toggleDoor(World world,Vector3i pos,BlockType type){var chunk=WorldAccess.inMemory(world,ChunkUtil.indexChunkFromBlock(pos.x,pos.z));if(chunk!=null)WorldAccess.state(chunk,pos,type,type.getId().contains("OpenDoor")?"CloseDoorIn":"OpenDoorIn");}
    synchronized void cleanup(World world){ticks.remove(world.getName());specimens.entrySet().removeIf(e->e.getKey().startsWith(world.getName()+":"));liftDirections.keySet().removeIf(key->key.startsWith(world.getName()+":"));LevitationInputSystem.cleanup(world);machines.save();}
}
