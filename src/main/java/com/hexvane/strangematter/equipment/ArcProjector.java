package com.hexvane.strangematter.equipment;

import com.hexvane.strangematter.effects.GadgetEffects;
import com.hexvane.strangematter.effects.MachineLinks;
import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.attitude.Attitude;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.OverlapBehavior;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.*;
import com.hypixel.hytale.server.core.modules.entity.damage.*;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import org.joml.Vector3d;
import java.util.*;

/** A paid 5 Hz pulse uses native attributed damage and exactly the rift/stabilizer arc renderer. */
final class ArcProjector {
    static final int MAX_TARGETS=4,MAX_CANDIDATES=128;
    static float damage(int hop){return (float)(8*Math.pow(.75,Math.max(0,hop)));}
    static boolean eligible(Ref<EntityStore> attacker,Ref<EntityStore> target,Store<EntityStore> store,boolean chain){
        if(target==null||!target.isValid()||target.equals(attacker)||target.getStore()!=store
                ||store.getComponent(target,Player.getComponentType())!=null
                ||store.getComponent(target,DeathComponent.getComponentType())!=null
                ||store.getComponent(target,Invulnerable.getComponentType())!=null
                ||store.getComponent(target,NPCMountComponent.getComponentType())!=null)return false;
        var npc=store.getComponent(target,NPCEntity.getComponentType());if(npc==null||!npc.getCanCauseDamage(attacker,store))return false;
        var effects=store.getComponent(target,EffectControllerComponent.getComponentType());if(effects!=null&&effects.isInvulnerable())return false;
        var attitude=AdvancedGadgetTargeting.attitude(target,attacker,store);if(attitude==null)return false;
        return chain?attitude==Attitude.HOSTILE:attitude!=Attitude.FRIENDLY&&attitude!=Attitude.REVERED&&attitude!=Attitude.IGNORE;
    }
    static String identity(Ref<EntityStore> target,Store<EntityStore> store){var id=store.getComponent(target,UUIDComponent.getComponentType());return id==null?String.format("%010d",target.getIndex()):id.getUuid().toString();}
    static List<Ref<EntityStore>> chain(Ref<EntityStore> attacker,Store<EntityStore> store,AdvancedGadgetTargeting.Aim aim){
        var end=new Vector3d(aim.eye()).fma(aim.distance(),aim.direction());
        var min=new Vector3d(aim.eye()).min(end).sub(1,1,1);var max=new Vector3d(aim.eye()).max(end).add(1,1,1);
        var candidates=EquipmentQueries.inBox(store,min,max,false);candidates.sort(Comparator.comparing(ref->identity(ref,store)));
        Ref<EntityStore> first=null;double nearest=aim.distance();int count=0;
        for(var target:candidates){
            if(++count>MAX_CANDIDATES)break;if(!eligible(attacker,target,store,false))continue;
            var transform=store.getComponent(target,TransformComponent.getComponentType());var bounds=store.getComponent(target,BoundingBox.getComponentType());if(transform==null||bounds==null)continue;
            double distance=AdvancedGadgetTargeting.rayBody(aim.eye(),aim.direction(),transform.getPosition(),bounds.getBoundingBox(),nearest);
            var center=AdvancedGadgetTargeting.center(target,store);
            if(distance<nearest&&center!=null&&AdvancedGadgetTargeting.visible(store,aim.eye(),center)){nearest=distance;first=target;}
        }
        if(first==null)return List.of();var result=new ArrayList<Ref<EntityStore>>();result.add(first);
        while(result.size()<MAX_TARGETS){
            var from=AdvancedGadgetTargeting.center(result.getLast(),store);if(from==null)break;
            var nearby=EquipmentQueries.inBox(store,new Vector3d(from).sub(5,5,5),new Vector3d(from).add(5,5,5),false);
            nearby.removeIf(ref->result.contains(ref)||!eligible(attacker,ref,store,true));
            nearby.sort(Comparator.<Ref<EntityStore>>comparingDouble(ref->{var p=AdvancedGadgetTargeting.center(ref,store);return p==null?Double.POSITIVE_INFINITY:p.distanceSquared(from);}).thenComparing(ref->identity(ref,store)));
            Ref<EntityStore> next=null;count=0;
            for(var target:nearby){if(++count>MAX_CANDIDATES)break;var center=AdvancedGadgetTargeting.center(target,store);
                if(center!=null&&center.distanceSquared(from)<=25&&AdvancedGadgetTargeting.visible(store,from,center)){next=target;break;}}
            if(next==null)break;result.add(next);
        }
        return result;
    }
    static void fire(World world,Ref<EntityStore> attacker,AdvancedGadgetTargeting.Aim aim,int frame){
        var store=world.getEntityStore().getStore();var targets=chain(attacker,store,aim);
        var from=EquipmentQueries.handheldOrigin(aim.eye(),aim.direction());
        // Do not visually skip a point-blank wall with the hand offset.
        if(!AdvancedGadgetTargeting.visible(store,aim.eye(),from))from=new Vector3d(aim.eye());
        GadgetEffects.use(world,"SM_Arc_Muzzle",from);
        if(targets.isEmpty()){MachineLinks.rift(world,from,new Vector3d(aim.eye()).fma(aim.distance(),aim.direction()),frame);return;}
        int cause=DamageCause.getAssetMap().getIndex("Elemental");
        for(int hop=0;hop<targets.size();hop++){
            var target=targets.get(hop);if(!eligible(attacker,target,store,hop>0))break;
            var to=AdvancedGadgetTargeting.center(target,store);if(to==null)break;
            MachineLinks.rift(world,from,to,frame+hop*5);GadgetEffects.use(world,"SM_Arc_Impact",to);
            var damage=new Damage(new Damage.EntitySource(attacker),cause,damage(hop));
            if(cause>=0)DamageSystems.executeDamage(target,store,damage);
            if(cause>=0&&!damage.isCancelled()&&damage.getAmount()>0&&target.isValid()&&!GraviticManipulator.largeOrBoss(store,target)){
                var effect=EntityEffect.getAssetMap().getAsset("SM_Arc_Slow");var controller=store.getComponent(target,EffectControllerComponent.getComponentType());
                if(effect!=null&&controller!=null)controller.addEffect(target,effect,.35f,OverlapBehavior.OVERWRITE,store);
            }
            from=to;
        }
    }
}
