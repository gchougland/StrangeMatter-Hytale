package com.hexvane.strangematter.equipment;

import com.hexvane.strangematter.machine.MachineService;
import com.hexvane.strangematter.machine.MachineState;
import com.hexvane.strangematter.effects.GadgetEffects;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
import com.hypixel.hytale.math.util.ChunkUtil;
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
            if(pos.y<0||pos.y>=ChunkUtil.HEIGHT||world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(pos.x,pos.z))==null)continue;
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
                case "SM_Levitation_Pad"->lift(world,m,visual);
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
    private void lift(World world,MachineState m,boolean visual){
        int ceiling=m.y+machines.config.levitationHeight;
        for(int y=m.y+1;y<=ceiling+2&&y<ChunkUtil.HEIGHT;y++){var block=world.getBlockType(m.x,y,m.z);if(block!=null&&block.getMaterial()!=BlockMaterial.Empty){ceiling=y-3;break;}}
        if(ceiling<=m.y)return;
        var store=world.getEntityStore().getStore();var bottom=new Vector3d(m.x,m.y+.15,m.z);var top=new Vector3d(m.x+1,ceiling+1,m.z+1);
        for(var ref:EquipmentQueries.inBox(store,bottom,top,true)){
            if(!ref.isValid())continue;var velocity=store.getComponent(ref,Velocity.getComponentType());var transform=store.getComponent(ref,TransformComponent.getComponentType());if(velocity==null||transform==null)continue;
            var player=store.getComponent(ref,Player.getComponentType());if(player!=null&&player.getGameMode()==GameMode.Creative)continue;
            double y=m.ascending?Math.min(4,Math.max(0,(ceiling-transform.getPosition().y)*3)):-1;
            if(player!=null){var v=new Vector3d(velocity.getClientVelocity());v.y=y;velocity.addInstruction(v,null,ChangeVelocityType.Set);}else velocity.set(velocity.getX(),y,velocity.getZ());
        }
        Boolean previous=liftDirections.put(m.key(),m.ascending);var beamOrigin=new Vector3d(m.x+.5,m.y+.2,m.z+.5);
        if(previous==null||previous!=m.ascending)GadgetEffects.sound(world,(m.ascending?"SM_Levitation_Up":"SM_Levitation_Down")+"_SFX",beamOrigin);
        if(visual)GadgetEffects.column(world,beamOrigin,Math.max(0,ceiling+1-beamOrigin.y),m.ascending);
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
    void toggleDoor(World world,Vector3i pos,BlockType type){var chunk=world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(pos.x,pos.z));if(chunk!=null)chunk.setBlockInteractionState(pos,type,type.getId().contains("OpenDoor")?"CloseDoorIn":"OpenDoorIn");}
    synchronized void cleanup(World world){ticks.remove(world.getName());specimens.entrySet().removeIf(e->e.getKey().startsWith(world.getName()+":"));liftDirections.keySet().removeIf(key->key.startsWith(world.getName()+":"));machines.save();}
}
