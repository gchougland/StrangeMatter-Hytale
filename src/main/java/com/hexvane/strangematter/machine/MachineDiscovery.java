package com.hexvane.strangematter.machine;

import com.hexvane.strangematter.util.WorldAccess;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.component.system.RefChangeSystem;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.ChunkSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.*;
import org.joml.Vector3i;

/** Physical loaded-block reconciliation. Never loads a chunk or resets a saved machine. */
public final class MachineDiscovery extends RefSystem<ChunkStore> {
    private static final int SECTION_BUDGET=2, COLUMN_BUDGET=8, BLOCK_BUDGET=128, NEIGHBOR_BUDGET=1024, SEED_BUDGET=128;
    private static final int[][] FACES={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
    private final MachineService machines;
    private static final class Column {
        final Ref<ChunkStore> ref;int y,cursor;BlockSection section;IntArrayList positions;
        Column(Ref<ChunkStore> ref){this.ref=ref;}
    }
    private static final class Pending {
        final LinkedHashMap<Long,Column> columns=new LinkedHashMap<>();int ticks,seed;
    }
    private final Map<World,Pending> worlds=new java.util.concurrent.ConcurrentHashMap<>();
    private IntArrayList ids;private int assetCount=-1;
    public MachineDiscovery(MachineService machines){this.machines=machines;}
    public static final class Sections extends RefSystem<ChunkStore> {
        private final MachineDiscovery discovery;
        public Sections(MachineDiscovery discovery){this.discovery=discovery;}
        @Override public Query<ChunkStore> getQuery(){return Query.and(ChunkSection.getComponentType(),BlockSection.getComponentType(),Query.not(ChunkStore.REGISTRY.getNonTickingComponentType()));}
        @Override public void onEntityAdded(Ref<ChunkStore> ref,AddReason reason,Store<ChunkStore> store,CommandBuffer<ChunkStore> buffer){
            discovery.sectionPublished(ref,store);
        }
        @Override public void onEntityRemove(Ref<ChunkStore> ref,RemoveReason reason,Store<ChunkStore> store,CommandBuffer<ChunkStore> buffer){}
    }
    /** Native RefSystem callbacks cover entity publication, not component changes. */
    public static final class Activation extends RefChangeSystem<ChunkStore,NonTicking<ChunkStore>> {
        private final MachineDiscovery discovery;
        public Activation(MachineDiscovery discovery){this.discovery=discovery;}
        @Override public ComponentType<ChunkStore,NonTicking<ChunkStore>> componentType(){return ChunkStore.REGISTRY.getNonTickingComponentType();}
        @Override public Query<ChunkStore> getQuery(){return Query.and(ChunkSection.getComponentType(),BlockSection.getComponentType());}
        @Override public void onComponentAdded(Ref<ChunkStore> ref,NonTicking<ChunkStore> component,Store<ChunkStore> store,CommandBuffer<ChunkStore> buffer){}
        @Override public void onComponentSet(Ref<ChunkStore> ref,NonTicking<ChunkStore> old,NonTicking<ChunkStore> component,Store<ChunkStore> store,CommandBuffer<ChunkStore> buffer){}
        @Override public void onComponentRemoved(Ref<ChunkStore> ref,NonTicking<ChunkStore> component,Store<ChunkStore> store,CommandBuffer<ChunkStore> buffer){discovery.sectionPublished(ref,store);}
    }
    private void sectionPublished(Ref<ChunkStore> ref,Store<ChunkStore> store){
        var world=store.getExternalData().getWorld();world.execute(()->{
            if(!ref.isValid()||store.isShutdown()||store.getComponent(ref,ChunkStore.REGISTRY.getNonTickingComponentType())!=null)return;
            var section=store.getComponent(ref,ChunkSection.getComponentType());if(section==null)return;
            var chunk=WorldAccess.inMemory(world,ChunkUtil.indexChunk(section.getX(),section.getZ()));
            if(chunk!=null){worlds.computeIfAbsent(world,k->new Pending()).columns.remove(ChunkUtil.indexChunk(chunk.getX(),chunk.getZ()));enqueue(chunk);}
        });
    }
    @Override public Query<ChunkStore> getQuery(){return WorldChunk.getComponentType();}
    @Override public void onEntityAdded(Ref<ChunkStore> ref,AddReason reason,Store<ChunkStore> store,CommandBuffer<ChunkStore> buffer){
        var world=store.getExternalData().getWorld();world.execute(()->{if(!ref.isValid()||store.isShutdown())return;var chunk=store.getComponent(ref,WorldChunk.getComponentType());if(chunk!=null)enqueue(chunk);});
    }
    @Override public void onEntityRemove(Ref<ChunkStore> ref,RemoveReason reason,Store<ChunkStore> store,CommandBuffer<ChunkStore> buffer){
        var world=store.getExternalData().getWorld();var chunk=store.getComponent(ref,WorldChunk.getComponentType());var pending=worlds.get(world);
        if(chunk!=null&&pending!=null)pending.columns.remove(ChunkUtil.indexChunk(chunk.getX(),chunk.getZ()));
    }
    public void enqueue(WorldChunk chunk){
        chunk.getWorld().debugAssertInTickingThread();var pending=worlds.computeIfAbsent(chunk.getWorld(),k->new Pending());long index=ChunkUtil.indexChunk(chunk.getX(),chunk.getZ());
        var existing=pending.columns.get(index);if(existing==null||existing.ref!=chunk.getReference())pending.columns.put(index,new Column(chunk.getReference()));
    }
    /** Startup recovery for columns published before plugin start. Work remains tick-budgeted. */
    public void enqueueLoaded(World world){
        for(long index:world.getChunkStore().getChunkIndexes().toLongArray()){var chunk=WorldAccess.inMemory(world,index);if(chunk!=null)enqueue(chunk);}
    }
    public void cleanup(World world){worlds.remove(world);}
    public void tick(World world){
        world.debugAssertInTickingThread();var pending=worlds.computeIfAbsent(world,k->new Pending());
        int sections=0,blocks=0,columns=Math.min(COLUMN_BUDGET,pending.columns.size());
        while(!pending.columns.isEmpty()&&sections<SECTION_BUDGET&&blocks<BLOCK_BUDGET&&columns-->0){
            var entry=pending.columns.entrySet().iterator().next();long index=entry.getKey();var work=entry.getValue();pending.columns.remove(index);
            var chunk=WorldAccess.loaded(world,index);if(!work.ref.isValid())continue;
            if(chunk==null){pending.columns.put(index,work);continue;}
            if(chunk.getReference()!=work.ref){enqueue(chunk);continue;}
            while(work.y<ChunkUtil.HEIGHT_SECTIONS&&sections<SECTION_BUDGET&&blocks<BLOCK_BUDGET){
                var section=WorldAccess.tickingSection(chunk,work.y*ChunkUtil.SIZE);
                if(section==null){work.y++;work.positions=null;work.section=null;work.cursor=0;sections++;continue;}
                if(work.positions==null||work.section!=section){
                    work.section=section;work.positions=new IntArrayList();work.cursor=0;section.find(indexes(),position->work.positions.add(position));sections++;
                }
                while(work.cursor<work.positions.size()&&blocks++<BLOCK_BUDGET){
                    int indexInSection=work.positions.getInt(work.cursor++);if(section.getFiller(indexInSection)!=0)continue;
                    String id=MachineService.baseId(BlockType.getAssetMap().getAsset(section.get(indexInSection)));if(!MachineService.powerNode(id))continue;
                    var position=new Vector3i((chunk.getX()<<ChunkUtil.BITS)+ChunkUtil.xFromIndex(indexInSection),work.y*ChunkUtil.SIZE+ChunkUtil.yFromIndex(indexInSection),(chunk.getZ()<<ChunkUtil.BITS)+ChunkUtil.zFromIndex(indexInSection));
                    machines.register(world,position,id);
                }
                if(work.cursor==work.positions.size()){work.y++;work.positions=null;work.section=null;}
            }
            if(work.y<ChunkUtil.HEIGHT_SECTIONS)pending.columns.put(index,work);
        }
        if(pending.ticks++%20==0)neighbors(world,pending);
    }
    private synchronized IntArrayList indexes(){
        int count=BlockType.getAssetMap().getAssetMap().size();if(ids!=null&&count==assetCount)return ids;
        ids=new IntArrayList();assetCount=count;
        for(var type:BlockType.getAssetMap().getAssetMap().values())if(MachineService.powerNode(MachineService.baseId(type)))ids.add(BlockType.getAssetMap().getIndex(type.getId()));
        return ids;
    }
    /** Repair missed placements/edits around known network ports, without rescanning whole columns. */
    private void neighbors(World world,Pending pending){
        var seeds=machines.inWorld(world).stream().filter(s->MachineService.powerNode(s.id)).toList();if(seeds.isEmpty())return;
        var queue=new ArrayDeque<Vector3i>();var seen=new HashSet<Vector3i>();
        int count=Math.min(SEED_BUDGET,seeds.size());for(int n=0;n<count;n++){var state=seeds.get(Math.floorMod(pending.seed+n,seeds.size()));for(var face:FACES)queue.add(new Vector3i(state.x+face[0],state.y+face[1],state.z+face[2]));}
        pending.seed=Math.floorMod(pending.seed+count,seeds.size());
        for(int reads=0;!queue.isEmpty()&&reads<NEIGHBOR_BUDGET;){var position=queue.remove();if(!seen.add(position))continue;reads++;
            if(position.y<0||position.y>=ChunkUtil.HEIGHT)continue;var chunk=WorldAccess.loaded(world,ChunkUtil.indexChunkFromBlock(position.x,position.z));
            if(chunk==null||WorldAccess.tickingSection(chunk,position.y)==null||WorldAccess.filler(chunk,position.x,position.y,position.z)!=0)continue;
            String id=MachineService.baseId(WorldAccess.blockType(chunk,position));if(!MachineService.powerNode(id))continue;
            var state=machines.get(world,position);if(state!=null&&state.id.equals(id))continue;
            machines.register(world,position,id);for(var face:FACES)queue.add(new Vector3i(position).add(face[0],face[1],face[2]));
        }
    }
}
