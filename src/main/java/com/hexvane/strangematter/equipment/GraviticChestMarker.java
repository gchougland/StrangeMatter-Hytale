package com.hexvane.strangematter.equipment;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.dependency.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterType;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.modules.block.system.ItemContainerSystems;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import java.util.*;

/** Durable identity of a chest whose contents cannot be used until its placement is committed. */
public final class GraviticChestMarker implements Component<ChunkStore> {
    private static ComponentType<ChunkStore,GraviticChestMarker> type;
    public static final BuilderCodec<GraviticChestMarker> CODEC=BuilderCodec.builder(GraviticChestMarker.class,GraviticChestMarker::new)
            .append(new KeyedCodec<>("Journal",Codec.STRING),(c,v)->c.journal=UUID.fromString(v),c->c.journal.toString()).add()
            .append(new KeyedCodec<>("Receipt",Codec.STRING),(c,v)->c.receipt=UUID.fromString(v),c->c.receipt.toString()).add().build();
    // Native registry validates encoder defaults before loading any assets.
    UUID journal=new UUID(0,0),receipt=new UUID(0,0);
    private GraviticChestMarker(){}
    GraviticChestMarker(UUID journal,UUID receipt){this.journal=journal;this.receipt=receipt;}
    public static ComponentType<ChunkStore,GraviticChestMarker> getComponentType(){return type;}
    public static boolean locked(com.hypixel.hytale.server.core.universe.world.World world,org.joml.Vector3i position){
        var origin=GraviticBlockJournal.origin(world,position);return origin!=null&&GraviticChestTransport.marker(world,origin)!=null;
    }
    public static void register(IComponentRegistry<ChunkStore> registry){
        if(type==null){type=registry.registerComponent(GraviticChestMarker.class,"SM_GraviticChest",CODEC);registry.registerSystem(new LockOnLoad());}
    }
    @Override public GraviticChestMarker clone(){return new GraviticChestMarker(journal,receipt);}
    private static final class LockOnLoad extends RefSystem<ChunkStore>{
        @Override public Query<ChunkStore> getQuery(){return Query.and(type,ItemContainerBlock.getComponentType());}
        @Override public Set<Dependency<ChunkStore>> getDependencies(){return Set.of(new SystemDependency<>(Order.AFTER,ItemContainerSystems.OnAddedOrRemoved.class));}
        @Override public void onEntityAdded(Ref<ChunkStore> ref,AddReason reason,Store<ChunkStore> store,CommandBuffer<ChunkStore> commands){
            commands.getComponent(ref,ItemContainerBlock.getComponentType()).getItemContainer().setGlobalFilter(FilterType.DENY_ALL);
        }
        @Override public void onEntityRemove(Ref<ChunkStore> ref,RemoveReason reason,Store<ChunkStore> store,CommandBuffer<ChunkStore> commands){}
    }
}
