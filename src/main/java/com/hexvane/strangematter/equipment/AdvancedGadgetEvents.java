package com.hexvane.strangematter.equipment;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.component.system.WorldEventSystem;
import com.hypixel.hytale.server.core.event.events.ecs.*;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

/** Protect write-ahead block ownership while native terrain checkpoints are in flight. */
public final class AdvancedGadgetEvents {
    /** NPC ability-effect flags are client presentation. Native damage is suppressed separately.
     * The finite effect is the complete lease: expiry/release/restart cannot leave an AI flag behind.
     */
    public static final class HeldAttack extends DamageEventSystem {
        @Override public SystemGroup<EntityStore> getGroup(){return DamageModule.get().getFilterDamageGroup();}
        @Override public Query<EntityStore> getQuery(){return Query.any();}
        @Override public void handle(int index,ArchetypeChunk<EntityStore> chunk,Store<EntityStore> store,CommandBuffer<EntityStore> commands,com.hypixel.hytale.server.core.modules.entity.damage.Damage damage){
            if(!(damage.getSource() instanceof com.hypixel.hytale.server.core.modules.entity.damage.Damage.EntitySource source))return;
            var ref=source.getRef();if(ref==null||!ref.isValid()||ref.getStore()!=store||store.getComponent(ref,NPCEntity.getComponentType())==null)return;
            var controller=store.getComponent(ref,EffectControllerComponent.getComponentType());if(controller==null)return;
            int hold=EntityEffect.getAssetMap().getIndex(GraviticManipulator.EFFECT);if(hold<0)return;
            var active=controller.getActiveEffects().get(hold);
            if(active!=null&&(active.isInfinite()||active.getRemainingDuration()>0))damage.setCancelled(true);
        }
    }
    public static final class Place extends EntityEventSystem<EntityStore,PlaceBlockEvent>{
        private final AdvancedGadgets gadgets;public Place(AdvancedGadgets gadgets){super(PlaceBlockEvent.class);this.gadgets=gadgets;}
        @Override public Query<EntityStore> getQuery(){return Query.any();}
        @Override public void handle(int i,ArchetypeChunk<EntityStore> chunk,Store<EntityStore> store,CommandBuffer<EntityStore> commands,PlaceBlockEvent event){synchronized(gadgets){
            var world=store.getExternalData().getWorld();var pos=event.getTargetBlock();
            if(gadgets.gravity.blocks.reserved(world,pos)){event.setCancelled(true);return;}
            // Native connected placement may merge an adjacent chest without targeting its cell.
            var item=event.getItemInHand();if(item!=null&&item.getItemId().toLowerCase(java.util.Locale.ROOT).contains("chest"))
                for(var offset:new int[][]{{1,0},{-1,0},{0,1},{0,-1}})if(GraviticChestMarker.locked(world,new org.joml.Vector3i(pos).add(offset[0],0,offset[1])))event.setCancelled(true);
        }}
    }
    public static final class Use extends EntityEventSystem<EntityStore,UseBlockEvent.Pre>{
        private final AdvancedGadgets gadgets;public Use(AdvancedGadgets gadgets){super(UseBlockEvent.Pre.class);this.gadgets=gadgets;}
        @Override public Query<EntityStore> getQuery(){return Query.any();}
        @Override public void handle(int i,ArchetypeChunk<EntityStore> chunk,Store<EntityStore> store,CommandBuffer<EntityStore> commands,UseBlockEvent.Pre event){synchronized(gadgets){if(gadgets.gravity.blocks.reserved(store.getExternalData().getWorld(),event.getTargetBlock()))event.setCancelled(true);}}
    }
    public static final class Break extends EntityEventSystem<EntityStore,BreakBlockEvent>{
        private final AdvancedGadgets gadgets;public Break(AdvancedGadgets gadgets){super(BreakBlockEvent.class);this.gadgets=gadgets;}
        @Override public Query<EntityStore> getQuery(){return Query.any();}
        @Override public void handle(int i,ArchetypeChunk<EntityStore> chunk,Store<EntityStore> store,CommandBuffer<EntityStore> commands,BreakBlockEvent event){synchronized(gadgets){if(gadgets.gravity.blocks.reserved(store.getExternalData().getWorld(),event.getTargetBlock()))event.setCancelled(true);}}
    }
    public static final class Damage extends EntityEventSystem<EntityStore,DamageBlockEvent>{
        private final AdvancedGadgets gadgets;public Damage(AdvancedGadgets gadgets){super(DamageBlockEvent.class);this.gadgets=gadgets;}
        @Override public Query<EntityStore> getQuery(){return Query.any();}
        @Override public void handle(int i,ArchetypeChunk<EntityStore> chunk,Store<EntityStore> store,CommandBuffer<EntityStore> commands,DamageBlockEvent event){synchronized(gadgets){if(gadgets.gravity.blocks.reserved(store.getExternalData().getWorld(),event.getTargetBlock()))event.setCancelled(true);}}
    }
    public static final class EnvironmentBreak extends WorldEventSystem<EntityStore,EnvironmentBreakBlockEvent>{
        private final AdvancedGadgets gadgets;public EnvironmentBreak(AdvancedGadgets gadgets){super(EnvironmentBreakBlockEvent.class);this.gadgets=gadgets;}
        @Override public void handle(Store<EntityStore> store,CommandBuffer<EntityStore> commands,EnvironmentBreakBlockEvent event){synchronized(gadgets){gadgets.gravity.blocks.environmentBreak(store.getExternalData().getWorld(),event.getTargetBlock());}}
    }
}
