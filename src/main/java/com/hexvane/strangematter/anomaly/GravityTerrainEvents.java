package com.hexvane.strangematter.anomaly;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.component.system.WorldEventSystem;
import com.hypixel.hytale.server.core.event.events.ecs.*;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/** Protects the short terrain restoration/checkpoint interval and records new raw-block construction. */
public final class GravityTerrainEvents {
    public static final class Place extends EntityEventSystem<EntityStore,PlaceBlockEvent> {
        private final GravityTerrain terrain;
        public Place(AnomalyService service){this(service.terrain());}
        Place(GravityTerrain terrain){super(PlaceBlockEvent.class);this.terrain=terrain;}
        @Override public Query<EntityStore> getQuery(){return Player.getComponentType();}
        @Override public void handle(int index,ArchetypeChunk<EntityStore> chunk,Store<EntityStore> store,CommandBuffer<EntityStore> commands,PlaceBlockEvent event){
            if(event.isCancelled())return;var world=store.getExternalData().getWorld();var p=event.getTargetBlock();
            if(terrain.protectedReturn(world,p.x,p.y,p.z)){event.setCancelled(true);return;}
            var stack=event.getItemInHand();
            if(stack!=null&&GravityTerrain.natural(stack.getItem().getBlockId()))terrain.placed(world,p.x,p.y,p.z);
        }
    }
    public static final class Break extends EntityEventSystem<EntityStore,BreakBlockEvent> {
        private final GravityTerrain terrain;
        public Break(AnomalyService service){this(service.terrain());}
        Break(GravityTerrain terrain){super(BreakBlockEvent.class);this.terrain=terrain;}
        @Override public Query<EntityStore> getQuery(){return Query.any();}
        @Override public void handle(int index,ArchetypeChunk<EntityStore> chunk,Store<EntityStore> store,CommandBuffer<EntityStore> commands,BreakBlockEvent event){
            var p=event.getTargetBlock();if(terrain.protectedReturn(store.getExternalData().getWorld(),p.x,p.y,p.z))event.setCancelled(true);
        }
    }
    public static final class Damage extends EntityEventSystem<EntityStore,DamageBlockEvent> {
        private final GravityTerrain terrain;
        public Damage(AnomalyService service){this(service.terrain());}
        Damage(GravityTerrain terrain){super(DamageBlockEvent.class);this.terrain=terrain;}
        @Override public Query<EntityStore> getQuery(){return Query.any();}
        @Override public void handle(int index,ArchetypeChunk<EntityStore> chunk,Store<EntityStore> store,CommandBuffer<EntityStore> commands,DamageBlockEvent event){
            var p=event.getTargetBlock();if(terrain.protectedReturn(store.getExternalData().getWorld(),p.x,p.y,p.z))event.setCancelled(true);
        }
    }
    public static final class EnvironmentBreak extends WorldEventSystem<EntityStore,EnvironmentBreakBlockEvent> {
        private final GravityTerrain terrain;
        public EnvironmentBreak(AnomalyService service){this(service.terrain());}
        EnvironmentBreak(GravityTerrain terrain){super(EnvironmentBreakBlockEvent.class);this.terrain=terrain;}
        @Override public void handle(Store<EntityStore> store,CommandBuffer<EntityStore> commands,EnvironmentBreakBlockEvent event){
            var p=event.getTargetBlock();terrain.environmentBreak(store.getExternalData().getWorld(),p.x,p.y,p.z);
        }
    }
}
