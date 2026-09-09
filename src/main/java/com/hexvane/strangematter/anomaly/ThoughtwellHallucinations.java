package com.hexvane.strangematter.anomaly;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.dependency.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.*;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;
import java.util.Set;
import java.util.UUID;

/** Native tracked presentation entities. Ownership is filtered before visibility is committed. */
public final class ThoughtwellHallucinations {
    static final String[] MODELS={"SM_Thoughtwell_Phantom_Wolf","SM_Thoughtwell_Phantom_Spectre","SM_Thoughtwell_Phantom_Crawler"};
    private static ComponentType<EntityStore,PrivatePhantom> type;
    private ThoughtwellHallucinations() {}

    /** Register during plugin setup, before any world starts ticking. */
    public static void register(IComponentRegistry<EntityStore> registry) {
        type=registry.registerComponent(PrivatePhantom.class,PrivatePhantom::new);
        registry.registerSystem(new VisibilityFilter(type));
    }
    static ComponentType<EntityStore,PrivatePhantom> componentType(){return type;}

    public static final class PrivatePhantom implements Component<EntityStore> {
        final Ref<EntityStore> owner;
        public PrivatePhantom(){this(null);}
        PrivatePhantom(Ref<EntityStore> owner){this.owner=owner;}
        @Override public Component<EntityStore> clone(){return new PrivatePhantom(owner);}
    }

    static Ref<EntityStore> spawn(World world,Ref<EntityStore> owner,int kind,Vector3d position,Rotation3f facing) {
        if(type==null||!owner.isValid())return null;
        var store=world.getEntityStore().getStore();
        var asset=ModelAsset.getAssetMap().getAsset(MODELS[Math.floorMod(kind,MODELS.length)]);
        if(asset==null)return null;
        // Native creature rigs use their own configured proportions. These are
        // new visual entities, never models applied to the player or real animals.
        var model=Model.createScaledModel(asset,kind%3==1?1.7f:kind%3==2?1.45f:1.0f);
        var holder=EntityStore.REGISTRY.newHolder();
        holder.addComponent(type,new PrivatePhantom(owner));
        holder.addComponent(TransformComponent.getComponentType(),new TransformComponent(position,facing));
        holder.addComponent(ModelComponent.getComponentType(),new ModelComponent(model));
        holder.addComponent(BoundingBox.getComponentType(),new BoundingBox(model.getBoundingBox()));
        holder.addComponent(HeadRotation.getComponentType(),new HeadRotation(facing));
        holder.addComponent(UUIDComponent.getComponentType(),new UUIDComponent(UUID.randomUUID()));
        holder.addComponent(NetworkId.getComponentType(),new NetworkId(store.getExternalData().takeNextNetworkId()));
        holder.addComponent(Intangible.getComponentType(),Intangible.INSTANCE);
        holder.addComponent(Invulnerable.getComponentType(),Invulnerable.INSTANCE);
        holder.addComponent(EntityStore.REGISTRY.getNonSerializedComponentType(),NonSerialized.get());
        var animation=new ActiveAnimationComponent();animation.setPlayingAnimation(AnimationSlot.Movement,"Run");
        holder.addComponent(ActiveAnimationComponent.getComponentType(),animation);
        return store.addEntity(holder,AddReason.SPAWN);
    }

    static void remove(Ref<EntityStore> phantom) {
        if(phantom!=null&&phantom.isValid())phantom.getStore().removeEntity(phantom,RemoveReason.REMOVE);
    }

    public static final class VisibilityFilter extends EntityTickingSystem<EntityStore> {
        private final ComponentType<EntityStore,PrivatePhantom> phantomType;
        private static final Set<Dependency<EntityStore>> DEPENDENCIES=Set.of(
                new SystemGroupDependency<>(Order.AFTER,EntityTrackerSystems.FIND_VISIBLE_ENTITIES_GROUP),
                new SystemDependency<>(Order.BEFORE,EntityTrackerSystems.ClearPreviouslyVisible.class));
        VisibilityFilter(ComponentType<EntityStore,PrivatePhantom> type){phantomType=type;}
        @Override public Query<EntityStore> getQuery(){return EntityTrackerSystems.EntityViewer.getComponentType();}
        @Override public Set<Dependency<EntityStore>> getDependencies(){return DEPENDENCIES;}
        @Override public void tick(float dt,int index,ArchetypeChunk<EntityStore> chunk,Store<EntityStore> store,CommandBuffer<EntityStore> commands) {
            var viewer=chunk.getComponent(index,EntityTrackerSystems.EntityViewer.getComponentType());
            var viewerRef=chunk.getReferenceTo(index);
            for(var iterator=viewer.visible.iterator();iterator.hasNext();) {
                var candidate=iterator.next();
                if(!candidate.isValid()){iterator.remove();continue;}
                var phantom=commands.getComponent(candidate,phantomType);
                if(phantom!=null&&(phantom.owner==null||!phantom.owner.isValid()||phantom.owner!=viewerRef)) {
                    iterator.remove();viewer.hiddenCount++;
                }
            }
        }
    }
}
