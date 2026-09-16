package com.hexvane.strangematter.equipment;

import com.hexvane.strangematter.effects.GadgetEffects;
import com.hexvane.strangematter.ui.gadget.GadgetHudService;
import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.asset.type.attitude.Attitude;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.*;
import com.hypixel.hytale.server.core.entity.effect.*;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.knockback.KnockbackComponent;
import com.hypixel.hytale.server.core.entity.Frozen;
import com.hypixel.hytale.server.core.inventory.*;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.component.*;
import com.hypixel.hytale.server.core.modules.entity.damage.*;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.entity.item.PreventPickup;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.modules.interaction.InteractionModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import org.joml.Vector3d;
import java.nio.file.Path;
import java.util.*;

/** One owner per original entity or journaled simple block; finite leases restore only our hold. */
final class GraviticManipulator {
    static final String ITEM="SM_Gravitic_Manipulator",TOKEN="SMGraviticHold",EFFECT="SM_Gravitic_Hold";
    static final double LAUNCH_SPEED=20,FLIGHT_GRAVITY=12,FLIGHT_SECONDS=3;
    private static final class Hold {
        final UUID owner,token;final World world;final PlayerRef player;final Ref<EntityStore> target;final UUID block;
        final Vector3d lastOwner=new Vector3d();ActiveEntityEffect activeEffect;double energy,visual,age;boolean launched;Vector3d flight=new Vector3d();
        Hold(PlayerRef player,World world,Ref<EntityStore> target,UUID block){this.player=player;this.owner=player.getUuid();this.world=world;this.target=target;this.block=block;token=UUID.randomUUID();}
    }
    private final Map<UUID,Hold> holds=new HashMap<>();private final List<Hold> flights=new ArrayList<>();
    private final GadgetHudService hud;final GraviticBlockJournal blocks;
    GraviticManipulator(Path directory,GadgetHudService hud){this(new GraviticBlockJournal(directory),hud);}
    GraviticManipulator(GraviticBlockJournal blocks,GadgetHudService hud){this.blocks=blocks;this.hud=hud;}
    static boolean largeOrBoss(Store<EntityStore> store,Ref<EntityStore> target){
        var bounds=store.getComponent(target,BoundingBox.getComponentType());if(bounds!=null){var b=bounds.getBoundingBox();if(b.max.x-b.min.x>2.5||b.max.z-b.min.z>2.5||b.max.y-b.min.y>3.5)return true;}
        var npc=store.getComponent(target,NPCEntity.getComponentType());var name=npc==null?"":npc.getRoleName();return name!=null&&(name.toLowerCase(Locale.ROOT).contains("boss")||name.toLowerCase(Locale.ROOT).contains("golem"));
    }
    private boolean eligible(Store<EntityStore> store,Ref<EntityStore> owner,Ref<EntityStore> target){
        if(target==null||!target.isValid()||target.getStore()!=store||target.equals(owner)||store.getComponent(target,Player.getComponentType())!=null
                ||store.getComponent(target,DeathComponent.getComponentType())!=null||store.getComponent(target,Invulnerable.getComponentType())!=null
                ||store.getComponent(target,Frozen.getComponentType())!=null
                ||store.getComponent(target,PreventPickup.getComponentType())!=null
                ||(store.getComponent(target,Intangible.getComponentType())!=null&&store.getComponent(target,ItemComponent.getComponentType())==null)
                ||store.getComponent(target,NPCMountComponent.getComponentType())!=null||store.getComponent(target,KnockbackComponent.getComponentType())!=null||store.getComponent(target,Velocity.getComponentType())==null||largeOrBoss(store,target))return false;
        if(holds.values().stream().anyMatch(h->h.target.equals(target))||flights.stream().anyMatch(h->h.target.equals(target)))return false;
        var npc=store.getComponent(target,NPCEntity.getComponentType());
        if(npc!=null){if(!npc.getCanCauseDamage(owner,store))return false;var attitude=AdvancedGadgetTargeting.attitude(target,owner,store);if(attitude==null||attitude==Attitude.FRIENDLY||attitude==Attitude.REVERED)return false;}
        else if(store.getComponent(target,ItemComponent.getComponentType())==null)return false;
        var effects=store.getComponent(target,EffectControllerComponent.getComponentType());
        if(effects!=null){if(effects.isInvulnerable())return false;for(String id:List.of(EFFECT,"SM_Stasis_Hold")){int index=EntityEffect.getAssetMap().getIndex(id);if(index>=0&&effects.getActiveEffects().containsKey(index))return false;}}
        return true;
    }
    void interact(PlayerRef p,Store<EntityStore> store,ItemContainer inventory,short slot,ItemStack item,String action){
        var old=holds.get(p.getUuid());var world=store.getExternalData().getWorld();
        if(old!=null&&old.world!=world){notice(p,store,"Previous target is releasing in its own world",false);return;}
        if("gravity_grab".equals(action)&&old!=null){release(old,false);notice(p,store,"Target released",false);return;}
        if("gravity_launch".equals(action)){
            if(old==null){notice(p,store,"Secondary: acquire a block or creature first",false);return;}
            if(old.world!=world||!old.token.toString().equals(item.getFromMetadataOrNull(TOKEN,Codec.STRING))){release(old,false);return;}
            if(old.block!=null&&!blocks.ready(old.block)){notice(p,store,"Securing terrain before launch",false);return;}
            var aim=AdvancedGadgetTargeting.aim(p.getReference(),store,8);if(aim==null)return;
            if(!GadgetEnergy.spend(inventory,slot,item,GadgetEnergy.cost("gravity_launch"),creative(p,store))){notice(p,store,"Insufficient energy to launch; secondary releases freely",true);return;}
            launch(old,aim.direction());return;
        }
        if(!"gravity_grab".equals(action))return;
        var aim=AdvancedGadgetTargeting.aim(p.getReference(),store,8);if(aim==null)return;
        var end=new Vector3d(aim.eye()).fma(aim.distance(),aim.direction());var candidates=EquipmentQueries.inBox(store,new Vector3d(aim.eye()).min(end).sub(1,1,1),new Vector3d(aim.eye()).max(end).add(1,1,1),true);
        candidates.sort(Comparator.comparing(ref->ArcProjector.identity(ref,store)));Ref<EntityStore> target=null;double distance=aim.distance();int checked=0;
        for(var candidate:candidates){if(++checked>128)break;if(!eligible(store,p.getReference(),candidate))continue;var bounds=store.getComponent(candidate,BoundingBox.getComponentType());var transform=store.getComponent(candidate,TransformComponent.getComponentType());if(bounds==null||transform==null)continue;
            double hit=AdvancedGadgetTargeting.rayBody(aim.eye(),aim.direction(),transform.getPosition(),bounds.getBoundingBox(),distance);if(hit<distance){distance=hit;target=candidate;}}
        if(target==null&&(aim.block()==null||!blocks.allowedBreak(world,p.getReference(),item,aim.block()))){notice(p,store,"Aim at an ordinary block, creature, or dropped item within 8 blocks",true);return;}
        try(var debit=GadgetEnergy.reserve(inventory,slot,item,GadgetEnergy.cost("gravity_grab"),creative(p,store))){
            if(debit==null){notice(p,store,"Insufficient energy to acquire a target",true);return;}
            UUID block=null;
            if(target==null){
                block=blocks.begin(world,p.getUuid(),aim.block());if(block==null){notice(p,store,"Terrain could not be secured",true);return;}
                try{target=blocks.visual(world,block);}catch(RuntimeException failure){blocks.detach(block);throw failure;}
            }
            if(target==null||!target.isValid()){if(block!=null)blocks.detach(block);return;}
            var hold=new Hold(p,world,target,block);hold.lastOwner.set(aim.eye());
            if(block==null&&!lease(hold,store)){notice(p,store,"This target resists suspension",true);return;}
            var actual=inventory.getItemStack(slot);if(actual==null||!inventory.setItemStackForSlot(slot,actual.withMetadata(TOKEN,Codec.STRING,hold.token.toString()),false).succeeded()){
                removeLease(hold);if(block!=null){blocks.detach(block);store.removeEntity(target,RemoveReason.REMOVE);}else restoreMotion(hold,store);return;
            }
            holds.put(p.getUuid(),hold);debit.commit();
            GadgetEffects.use(world,"SM_Gravitic_Acquire",AdvancedGadgetTargeting.center(target,store));notice(p,store,"Target suspended | Primary: launch | Secondary: release",false);
        }
    }
    private static boolean creative(PlayerRef p,Store<EntityStore> store){var player=store.getComponent(p.getReference(),Player.getComponentType());return player!=null&&player.getGameMode()==GameMode.Creative;}
    private void notice(PlayerRef p,Store<EntityStore> store,String text,boolean error){hud.notice(p,store,"Gravitic Manipulator",text,"Carrying uses energy; release is always free.",error);}
    void tick(World world,double dt){
        blocks.tick(world);var store=world.getEntityStore().getStore();dt=Math.min(.25,Math.max(0,dt));
        for(var hold:List.copyOf(holds.values()))if(hold.world==world){
            var owner=hold.player.getReference();if(owner==null||!owner.isValid()||owner.getStore()!=store||store.getComponent(owner,DeathComponent.getComponentType())!=null||!hold.target.isValid()||hold.target.getStore()!=store||store.getComponent(hold.target,DeathComponent.getComponentType())!=null){release(hold,false);continue;}
            var hotbar=store.getComponent(owner,InventoryComponent.Hotbar.getComponentType());short slot=hotbar==null?-1:hotbar.getActiveSlot();var item=slot<0?null:hotbar.getInventory().getItemStack(slot);
            if(ItemStack.isEmpty(item)||!ITEM.equals(item.getItemId())||!hold.token.toString().equals(item.getFromMetadataOrNull(TOKEN,Codec.STRING))){release(hold,false);continue;}
            var aim=AdvancedGadgetTargeting.aim(owner,store,8);if(aim==null||hold.lastOwner.distanceSquared(aim.eye())>64){release(hold,false);continue;}hold.lastOwner.set(aim.eye());
            hold.energy+=dt*GadgetEnergy.cost("gravity_second");int cost=(int)hold.energy;
            if(cost>0&&!GadgetEnergy.spend(hotbar.getInventory(),slot,item,cost,creative(hold.player,store))){release(hold,false);notice(hold.player,store,"Energy depleted; target released safely",true);continue;}hold.energy-=cost;
            var transform=store.getComponent(hold.target,TransformComponent.getComponentType());var bounds=store.getComponent(hold.target,BoundingBox.getComponentType());if(transform==null||bounds==null){release(hold,false);continue;}
            var current=new Vector3d(transform.getPosition());if(current.distanceSquared(aim.eye())>144){release(hold,false);continue;}
            var box=bounds.getBoundingBox();var centerOffset=new Vector3d((box.min.x+box.max.x)/2,(box.min.y+box.max.y)/2,(box.min.z+box.max.z)/2);
            var goal=new Vector3d(aim.eye()).fma(Math.max(.75,Math.min(3,aim.distance()-.65)),aim.direction()).sub(centerOffset);
            var delta=goal.sub(current);if(delta.length()>12*dt)delta.normalize(12*dt);
            if(hold.block!=null)transform.setPosition(AdvancedGadgetTargeting.carryMove(store,box,current,delta));
            else {double clear=AdvancedGadgetTargeting.sweep(store,box,current,delta);transform.setPosition(current.fma(clear,delta));}
            suspendMotion(hold,store);
            var dropped=store.getComponent(hold.target,ItemComponent.getComponentType());if(dropped!=null)dropped.setPickupDelay(.35f);
            cancelAttacks(hold,store);
            hold.visual+=dt;if(hold.visual>=.2){hold.visual%=.2;if(hold.block==null&&!lease(hold,store)){release(hold,false);continue;}
                var center=AdvancedGadgetTargeting.center(hold.target,store);GadgetEffects.beam(world,"SM_Stasis_Beam",EquipmentQueries.handheldOrigin(aim.eye(),aim.direction()),center);GadgetEffects.particle(world,"SM_Gravitic_Suspension",center);}
        }
        for(var flight:List.copyOf(flights))if(flight.world==world)tickFlight(flight,dt);
    }
    private boolean lease(Hold hold,Store<EntityStore> store){
        var controller=store.getComponent(hold.target,EffectControllerComponent.getComponentType());if(controller==null)return store.getComponent(hold.target,ItemComponent.getComponentType())!=null;
        int index=EntityEffect.getAssetMap().getIndex(EFFECT);var effect=EntityEffect.getAssetMap().getAsset(EFFECT);if(index<0||effect==null)return false;
        var current=controller.getActiveEffects().get(index);if(current!=null&&current!=hold.activeEffect)return false;
        if(!controller.addEffect(hold.target,effect,.35f,OverlapBehavior.OVERWRITE,store))return false;hold.activeEffect=controller.getActiveEffects().get(index);GraviticFlightSuspension.track(hold.target,hold.activeEffect);suspendMotion(hold,store);cancelAttacks(hold,store);return true;
    }
    private void cancelAttacks(Hold hold,Store<EntityStore> store){
        if(hold.block!=null||store.getComponent(hold.target,NPCEntity.getComponentType())==null)return;
        var manager=store.getComponent(hold.target,InteractionModule.get().getInteractionManagerComponent());if(manager==null)return;
        for(var chain:List.copyOf(manager.getChains().values()))switch(chain.getType()){
            case Primary,Secondary,Ability1,Ability2,Ability3->manager.cancelChains(chain);
            default->{}
        }
    }
    private void removeLease(Hold hold){
        GraviticFlightSuspension.release(hold.target);
        var store=hold.world.getEntityStore().getStore();if(!hold.target.isValid()||hold.target.getStore()!=store)return;var controller=store.getComponent(hold.target,EffectControllerComponent.getComponentType());int index=EntityEffect.getAssetMap().getIndex(EFFECT);
        if(controller!=null&&hold.activeEffect!=null&&controller.getActiveEffects().get(index)==hold.activeEffect)controller.removeEffect(hold.target,index,RemovalBehavior.COMPLETE,store);
    }
    private void launch(Hold hold,Vector3d direction){
        holds.remove(hold.owner);hold.launched=true;hold.age=0;hold.flight.set(direction).normalize(LAUNCH_SPEED);var store=hold.world.getEntityStore().getStore();
        // Blocks and creatures share the same bounded ballistic integrator. Native NPC Set
        // instructions go through knockback scaling; reissuing them also extends the impulse.
        // Keep the finite suspension lease until impact, then restore native falling/AI once.
        flights.add(hold);suspendMotion(hold,store);
        GadgetEffects.use(hold.world,"SM_Gravitic_Launch",AdvancedGadgetTargeting.center(hold.target,store));
    }
    private void tickFlight(Hold hold,double dt){
        var store=hold.world.getEntityStore().getStore();if(!hold.target.isValid()||hold.target.getStore()!=store||store.getComponent(hold.target,DeathComponent.getComponentType())!=null){release(hold,false);return;}var transform=store.getComponent(hold.target,TransformComponent.getComponentType());var bounds=store.getComponent(hold.target,BoundingBox.getComponentType());if(transform==null||bounds==null){release(hold,false);return;}
        if(hold.block==null&&!lease(hold,store)){release(hold,false);return;}
        hold.age+=dt;var start=new Vector3d(transform.getPosition());hold.flight.y-=FLIGHT_GRAVITY*dt;var delta=new Vector3d(hold.flight).mul(dt);double clear=AdvancedGadgetTargeting.sweep(store,bounds.getBoundingBox(),start,delta);
        var end=new Vector3d(start).fma(clear,delta);boolean hit=false;var owner=hold.player.getReference();
        if(owner!=null&&owner.isValid()&&owner.getStore()==store){
            int checked=0;for(var target:EquipmentQueries.inBox(store,new Vector3d(start).min(end).sub(.5,.5,.5),new Vector3d(start).max(end).add(.5,.5,.5),false)){
                if(++checked>128)break;if(target.equals(hold.target)||!ArcProjector.eligible(owner,target,store,false))continue;var center=AdvancedGadgetTargeting.center(target,store);if(center==null||!AdvancedGadgetTargeting.visible(store,start,center))continue;
                int cause=DamageCause.getAssetMap().getIndex("Physical");if(cause>=0)DamageSystems.executeDamage(target,store,new Damage(new Damage.EntitySource(owner),cause,20));hit=true;break;
            }
        }
        transform.setPosition(end);suspendMotion(hold,store);if(clear<.999||hit||hold.age>=FLIGHT_SECONDS)release(hold,false);
    }
    /** NPCs do not integrate their Velocity component. Their controller keeps separate falling
     * and knockback accumulators. A tiny direct external velocity selects native forced-motion
     * mode for one tick without introducing a serialized freeze or a scaled knockback impulse.
     * The position itself is moved by our full swept collider; missing a refresh resumes gravity.
     */
    private static void suspendMotion(Hold hold,Store<EntityStore> store){
        var velocity=store.getComponent(hold.target,Velocity.getComponentType());if(velocity!=null)velocity.setZero();
        var npc=store.getComponent(hold.target,NPCEntity.getComponentType());if(npc==null||npc.getRole()==null)return;
        var motion=npc.getRole().getActiveMotionController();if(motion==null)return;
        motion.clearExternalForces();motion.setVelocity(new Vector3d(0,.00001,0),null,true);
    }
    private static void restoreMotion(Hold hold,Store<EntityStore> store){
        var npc=store.getComponent(hold.target,NPCEntity.getComponentType());if(npc==null||npc.getRole()==null)return;
        var motion=npc.getRole().getActiveMotionController();if(motion==null)return;
        motion.clearExternalForces();
        var velocity=store.getComponent(hold.target,Velocity.getComponentType());if(velocity!=null)velocity.setZero();
        // Hand back a near-zero falling velocity rather than a stale pre-capture fall impulse.
        if(store.getComponent(hold.target,DeathComponent.getComponentType())==null)motion.setVelocity(new Vector3d(0,-.00001,0),null,false);
    }
    private void release(Hold hold,boolean cleanup){
        holds.remove(hold.owner,hold);flights.remove(hold);removeLease(hold);
        var owningStore=hold.world.getEntityStore().getStore();if(hold.block==null&&hold.target.isValid()&&hold.target.getStore()==owningStore)restoreMotion(hold,owningStore);
        if(hold.block!=null){
            var store=hold.world.getEntityStore().getStore();var center=hold.target.isValid()?AdvancedGadgetTargeting.center(hold.target,store):null;var receipt=blocks.receipt(hold.block);
            if(center==null&&receipt!=null)center=receipt.source().center();
            if(center!=null&&blocks.ready(hold.block))blocks.place(hold.world,hold.block,cleanup?null:hold.player.getReference(),center);
            blocks.detach(hold.block);if(hold.target.isValid()&&hold.target.getStore()==store)store.removeEntity(hold.target,RemoveReason.REMOVE);
        }
    }
    void cleanup(World world){for(var hold:List.copyOf(holds.values()))if(hold.world==world)release(hold,true);for(var flight:List.copyOf(flights))if(flight.world==world)release(flight,true);GraviticFlightSuspension.cleanup(world.getEntityStore().getStore());blocks.tick(world);}
}
