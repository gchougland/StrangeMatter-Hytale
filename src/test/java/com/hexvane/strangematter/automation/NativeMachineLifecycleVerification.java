package com.hexvane.strangematter.automation;

import com.hexvane.strangematter.StrangeMatterConfig;
import com.hexvane.strangematter.anomaly.AnomalyService;
import com.hexvane.strangematter.equipment.NativePlayerFixture;
import com.hexvane.strangematter.machine.*;
import com.hexvane.strangematter.research.ResearchService;
import com.hexvane.strangematter.util.WorldAccess;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.component.system.RefChangeSystem;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.BlockRotation;
import com.hypixel.hytale.protocol.Rotation;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.PlaceBlockEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.interaction.BlockPlaceUtils;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.*;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.*;
import com.hypixel.hytale.server.core.universe.world.storage.*;
import com.hypixel.hytale.server.core.universe.world.worldgen.provider.FlatWorldGenProvider;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.joml.Vector3d;
import org.joml.Vector3i;
import static com.hypixel.hytale.server.core.universe.world.SetBlockSettings.*;

/** Real native placement/publication, independent Y-section residency and acknowledged disk reload. */
public final class NativeMachineLifecycleVerification {
    private static volatile Context active;
    private static final String WIRE="SM_Resonant_Conduit", RIFT="SM_Rift_Stabilizer", CONDENSER="SM_Resonance_Condenser";
    public static void register(JavaPlugin plugin){
        plugin.getEntityStoreRegistry().registerSystem(new PlaceBridge());
        plugin.getChunkStoreRegistry().registerSystem(new ColumnBridge());
        plugin.getChunkStoreRegistry().registerSystem(new SectionBridge());
        plugin.getChunkStoreRegistry().registerSystem(new ActivationBridge());
    }
    public static final class PlaceBridge extends EntityEventSystem<EntityStore,PlaceBlockEvent>{
        public PlaceBridge(){super(PlaceBlockEvent.class);}
        @Override public Query<EntityStore> getQuery(){return Player.getComponentType();}
        @Override public void handle(int index,ArchetypeChunk<EntityStore> chunk,Store<EntityStore> store,CommandBuffer<EntityStore> buffer,PlaceBlockEvent event){
            var c=active;if(c==null||c.world!=store.getExternalData().getWorld())return;c.events++;
            if(!c.suppressPlacement)new MachineEvents.Place(c.machines).handle(index,chunk,store,buffer,event);
        }
    }
    public static final class ColumnBridge extends RefSystem<ChunkStore>{
        @Override public Query<ChunkStore> getQuery(){return WorldChunk.getComponentType();}
        @Override public void onEntityAdded(Ref<ChunkStore> ref,AddReason reason,Store<ChunkStore> store,CommandBuffer<ChunkStore> buffer){var c=active;if(c!=null&&c.world==store.getExternalData().getWorld())c.machines.discovery().onEntityAdded(ref,reason,store,buffer);}
        @Override public void onEntityRemove(Ref<ChunkStore> ref,RemoveReason reason,Store<ChunkStore> store,CommandBuffer<ChunkStore> buffer){var c=active;if(c!=null&&c.world==store.getExternalData().getWorld())c.machines.discovery().onEntityRemove(ref,reason,store,buffer);}
    }
    public static final class SectionBridge extends RefSystem<ChunkStore>{
        @Override public Query<ChunkStore> getQuery(){return Query.and(ChunkSection.getComponentType(),BlockSection.getComponentType(),Query.not(ChunkStore.REGISTRY.getNonTickingComponentType()));}
        @Override public void onEntityAdded(Ref<ChunkStore> ref,AddReason reason,Store<ChunkStore> store,CommandBuffer<ChunkStore> buffer){var c=active;if(c!=null&&c.world==store.getExternalData().getWorld())new MachineDiscovery.Sections(c.machines.discovery()).onEntityAdded(ref,reason,store,buffer);}
        @Override public void onEntityRemove(Ref<ChunkStore> ref,RemoveReason reason,Store<ChunkStore> store,CommandBuffer<ChunkStore> buffer){}
    }
    public static final class ActivationBridge extends RefChangeSystem<ChunkStore,NonTicking<ChunkStore>>{
        @Override public ComponentType<ChunkStore,NonTicking<ChunkStore>> componentType(){return ChunkStore.REGISTRY.getNonTickingComponentType();}
        @Override public Query<ChunkStore> getQuery(){return Query.and(ChunkSection.getComponentType(),BlockSection.getComponentType());}
        @Override public void onComponentAdded(Ref<ChunkStore> ref,NonTicking<ChunkStore> component,Store<ChunkStore> store,CommandBuffer<ChunkStore> buffer){}
        @Override public void onComponentSet(Ref<ChunkStore> ref,NonTicking<ChunkStore> old,NonTicking<ChunkStore> component,Store<ChunkStore> store,CommandBuffer<ChunkStore> buffer){}
        @Override public void onComponentRemoved(Ref<ChunkStore> ref,NonTicking<ChunkStore> component,Store<ChunkStore> store,CommandBuffer<ChunkStore> buffer){var c=active;if(c!=null&&c.world==store.getExternalData().getWorld())new MachineDiscovery.Activation(c.machines.discovery()).onComponentRemoved(ref,component,store,buffer);}
    }
    private static final class Context implements AutoCloseable {
        final World world;final ResearchService research;final Path directory;final MachineService machines;final FactoryService factory;final boolean cubic;
        NativePlayerFixture player;boolean suppressPlacement;int events;MachineState savedStorage,source,verticalSource,verticalStorage;String savedVariant;int reserve;
        Context(World world,ResearchService research,boolean cubic)throws Exception{
            this.world=world;this.research=research;this.cubic=cubic;directory=Files.createTempDirectory("sm-native-machine-lifecycle-");
            machines=new MachineService(directory,new StrangeMatterConfig(),research,new AnomalyService(directory));factory=new FactoryService(machines,research);machines.setFactory(factory);
        }
        WorldChunk column(Vector3i p){return Objects.requireNonNull(WorldAccess.loaded(world,ChunkUtil.indexChunkFromBlock(p.x,p.z)),"Fixture column must be ticking");}
        MachineState place(Vector3i p,String id,boolean tracked){
            var chunk=column(p);if(WorldAccess.block(chunk,p.x,p.y-1,p.z)==0)WorldAccess.set(chunk,p.x,p.y-1,p.z,"Rock_Stone",NO_UPDATE_STATE|NO_SEND_PARTICLES|FORCE_CHANGED);
            WorldAccess.set(chunk,p.x,p.y,p.z,"Empty",NO_UPDATE_STATE|NO_SEND_PARTICLES|FORCE_CHANGED);
            var item=new ItemStack(id,1);player.hotbar().setItemStackForSlot((short)0,item,false);int before=events;suppressPlacement=!tracked;
            boolean placed;
            try{placed=BlockPlaceUtils.placeBlock(player.ref(),item,item.getBlockKey(),player.hotbar(),new Vector3i(0,1,0),new Vector3i(p),new BlockRotation(Rotation.None,Rotation.None,Rotation.None),(byte)0,false,world.getChunkStore().getChunkSectionReferenceAtBlock(p.x,p.y,p.z),world.getChunkStore().getStore(),player.store(),false,false,true);}
            finally{suppressPlacement=false;}
            world.consumeTaskQueue();require(placed&&events==before+1,"Native placement publishes exactly one real PlaceBlockEvent for "+id+" at "+p);
            require(id.equals(MachineService.baseId(WorldAccess.blockType(chunk,p))),"Native placement owns the expected physical block at "+p);
            var state=machines.get(world,p);require(tracked?state!=null:state==null,"Placement registration follows the native event receipt at "+p);return state;
        }
        void inputs(MachineState state){for(var face:EnergyStoragePorts.Face.values())EnergyStoragePorts.set(state,face,EnergyStoragePorts.Mode.INPUT);factory.changed(world,state);}
        void tick(int count){for(int n=0;n<count;n++)machines.tick(world,.05);}
        void seams(){
            player=NativePlayerFixture.create(world,"PowerLifecycleOwner",new Vector3d(12.5,240,12.5));
            int[][] pairs={{-3,200,8,1,0,0},{29,200,16,1,0,0},{8,200,-3,0,0,1},{16,200,29,0,0,1}};
            var sources=new ArrayList<MachineState>();var stores=new ArrayList<MachineState>();
            for(var p:pairs){var start=new Vector3i(p[0],p[1],p[2]);sources.add(place(start,RIFT,true));for(int n=1;n<=4;n++)place(new Vector3i(start).add(n*p[3],n*p[4],n*p[5]),WIRE,true);var sink=place(new Vector3i(start).add(5*p[3],5*p[4],5*p[5]),EnergyStoragePorts.ID,true);inputs(sink);stores.add(sink);}
            // The stabilizer occupies two vertical cells. Route beside its mast rather
            // than overwriting the native filler with the first vertical conduit.
            verticalSource=place(new Vector3i(19,125,20),RIFT,true);sources.add(verticalSource);
            for(int y=125;y<=130;y++)place(new Vector3i(20,y,20),WIRE,true);
            verticalStorage=place(new Vector3i(21,130,20),EnergyStoragePorts.ID,true);inputs(verticalStorage);stores.add(verticalStorage);
            for(var s:sources)require(machines.valid(world,s),"All native source origins and their filler cells remain intact after constructing the route");
            for(var s:sources)s.energy=1000;tick(1);
            for(int n=0;n<sources.size();n++)require(sources.get(n).energy==980&&stores.get(n).energy==20,"Native event-created route transfers exactly 20 RE across signed X/Z or Y127/128 chunk seam "+n);
            // A changed connection variant must survive physical reconciliation without replacing state.
            var bridge=new Vector3i(-1,200,8);WorldAccess.state(column(bridge),bridge,WorldAccess.blockType(column(bridge),bridge),"Connection35");
            var existing=machines.get(world,bridge);machines.discovery().enqueueLoaded(world);tick(100);require(machines.get(world,bridge)==existing,"Full loaded-column discovery reuses existing state across native connection variants");
            for(var s:sources)s.enabled=false;for(var s:stores)s.enabled=false;
        }
        void exactSavedLayout(){
            // Exact user-world layout, translated X+1584/Z-160. Both physical Connection35
            // bridge blocks exist but have no placement receipt, matching the copied region.
            savedStorage=place(new Vector3i(4,123,20),EnergyStoragePorts.ID,true);inputs(savedStorage);
            place(new Vector3i(4,123,19),WIRE,true);source=place(new Vector3i(5,123,18),RIFT,true);
            var condenser=place(new Vector3i(6,123,18),CONDENSER,true);condenser.enabled=false;
            for(var p:List.of(new Vector3i(7,123,19),new Vector3i(8,123,19),new Vector3i(8,122,19),new Vector3i(8,122,18),new Vector3i(8,122,17)))place(p,WIRE,true);
            var other=place(new Vector3i(7,122,17),RIFT,true);
            for(int x=5;x<=6;x++){var p=new Vector3i(x,123,19);place(p,WIRE,false);WorldAccess.state(column(p),p,WorldAccess.blockType(column(p),p),"Connection35");}
            source.energy=1200;other.energy=1200;savedStorage.energy=34250;
            var report=PowerDiagnostics.inspect(world,machines,savedStorage.block(),12);
            require(report.nodes().stream().filter(n->n.registry().equals("MISSING")).count()==2,"Physical diagnostics identifies precisely the two missing Connection35 bridge registrations");
            require(report.routes().stream().anyMatch(r->r.sink().equals(new ResonantNetwork.Position(4,123,20))&&!r.registeredPath()),"Real physical route exists while registry graph is disconnected");
            require(machines.get(world,new Vector3i(5,123,19))==null&&machines.get(world,new Vector3i(6,123,19))==null,"Diagnostic inspection itself does not repair or mutate the missing bridge");
            for(int n=0;n<100&&savedStorage.energy==34250;n++)tick(1);
            require(savedStorage.energy==34270&&condenser.energy==0&&!condenser.enabled,"Discovery repairs the saved topology, delivers one exact 20 RE storage quantum, and preserves the disabled condenser");
            require(source.energy+other.energy+savedStorage.energy==36650,"Physical repair conserves the original 2400+34250 RE reserve");
            require(PowerDiagnostics.inspect(world,machines,savedStorage.block(),12).nodes().stream().allMatch(n->n.registry().equals("MATCH")),"The repaired physical topology has no registry gaps");
            other.enabled=false;savedStorage.enabled=false;source.enabled=false;reserve=savedStorage.energy;tick(1);
            var p=new Vector3i(5,123,19);WorldAccess.state(column(p),p,WorldAccess.blockType(column(p),p),"Connection35");savedVariant=WorldAccess.blockType(column(p),p).getId();
        }
        void parkedSection(){
            var p=savedStorage.block();var store=world.getChunkStore().getStore();var ref=world.getChunkStore().getChunkSectionReferenceAtBlock(p.x,p.y,p.z);
            var component=factory.component(world,savedStorage);require(component.data.energy==reserve,"Native component is checkpointed before parking");
            store.ensureComponent(ref,ChunkStore.REGISTRY.getNonTickingComponentType());
            require(WorldAccess.section(column(p),p.y)!=null&&WorldAccess.tickingSection(column(p),p.y)==null,"A native NonTicking Y section remains physically resident inside a ticking column");
            var blocks=store.getComponent(ref,BlockComponentSection.getComponentType());var holder=blocks.getBlockHolder(ChunkUtil.indexBlock(p.x,p.y,p.z));
            require(BlockModule.getBlockEntity(world,p.x,p.y,p.z)==null&&holder!=null,"Native non-ticking transition parks the authoritative factory holder");
            require(factory.register(world,savedStorage)==null&&blocks.getBlockHolder(ChunkUtil.indexBlock(p.x,p.y,p.z))==holder,"Factory registration cannot replace an existing parked holder");
            source.enabled=true;savedStorage.enabled=true;int energy=source.energy;tick(25);
            require(source.energy==energy&&savedStorage.energy==reserve&&machines.get(world,p)==savedStorage,"Non-ticking sections neither transfer nor delete their saved registry state");
            var missing=new Vector3i(5,123,19);machines.removed(world,missing);tick(25);require(machines.get(world,missing)==null,"Discovery never registers a physical conduit from a NonTicking section");
            store.removeComponent(ref,ChunkStore.REGISTRY.getNonTickingComponentType());
            require(WorldAccess.tickingSection(column(p),p.y)!=null,"Native section activation republishes the original terrain");
            require(factory.component(world,savedStorage).data.energy==reserve,"Native reactivation preserves exact storage reserve and restores its original holder");
            for(int n=0;n<100&&savedStorage.energy==reserve;n++)tick(1);
            require(savedStorage.energy==reserve+20&&machines.get(world,missing)!=null,"Reactivated section discovers its missing bridge and resumes transfer");
            source.enabled=false;savedStorage.enabled=false;reserve=savedStorage.energy;tick(1);machines.save();
            WorldAccess.state(column(missing),missing,WorldAccess.blockType(column(missing),missing),"Connection35");savedVariant=WorldAccess.blockType(column(missing),missing).getId();
        }
        void isolatedActivation(){
            var p=new Vector3i(12,270,12);place(p,"SM_Pattern_Assembler",false);
            WorldAccess.state(column(p),p,WorldAccess.blockType(column(p),p),"Tier3Working");
            var store=world.getChunkStore().getStore();var section=world.getChunkStore().getChunkSectionReferenceAtBlock(p.x,p.y,p.z);
            store.ensureComponent(section,ChunkStore.REGISTRY.getNonTickingComponentType());machines.discovery().enqueueLoaded(world);tick(100);
            require(machines.get(world,p)==null,"An isolated cold section is skipped even during complete column discovery");
            store.removeComponent(section,ChunkStore.REGISTRY.getNonTickingComponentType());world.consumeTaskQueue();
            for(int n=0;n<100&&machines.get(world,p)==null;n++)tick(1);
            require(machines.get(world,p)!=null&&machines.get(world,p).id.equals("SM_Pattern_Assembler"),"Independent native section activation discovers an isolated Tier3Working machine without neighbor seeds");
        }
        CompletableFuture<Void> save(){return save(savedStorage.block());}
        CompletableFuture<Void> save(Vector3i p){var saved=TubeCheckpoints.saveAt(world,p);if(saved!=null)return saved;return CompletableFuture.runAsync(()->{},CompletableFuture.delayedExecutor(20,TimeUnit.MILLISECONDS)).thenComposeAsync(v->save(p),world);}
        CompletableFuture<Void> reload(){
            var p=savedStorage.block();var chunk=column(p);var chunks=world.getChunkStore();var ref=chunks.getChunkSectionReferenceAtBlock(p.x,p.y,p.z);var other=chunks.getChunkSectionReferenceAtBlock(p.x,200,p.z);
            snapshot("ACKNOWLEDGED_BEFORE_UNLOAD",savedStorage);
            var savedPayload=FactoryComponent.CODEC.encode(factory.component(world,savedStorage),new com.hypixel.hytale.codec.ExtraInfo());
            CompletableFuture<?> loaded;
            if(cubic){
                chunks.removeSection(ref,RemoveReason.UNLOAD);require(other!=null&&other.isValid()&&column(p)==chunk,"A separate Y section retains the live column while the power section unloads");
                require(WorldAccess.section(chunk,p.y)==null,"The target native Y section is genuinely unpublished");tick(25);
                loaded=chunks.getChunkSectionReferenceAtBlockAsync(p.x,p.y,p.z,GetChunkFlags.SET_TICKING);
            }else{
                // IndexedStorage is column-only: native section unloading is unsupported there.
                chunks.remove(chunk.getReference(),RemoveReason.UNLOAD);require(WorldAccess.inMemory(world,ChunkUtil.indexChunkFromBlock(p.x,p.z))==null,"Native default provider unloads its complete column");tick(25);
                loaded=WorldAccess.load(world,ChunkUtil.indexChunkFromBlock(p.x,p.z));
            }
            require(machines.get(world,p)==savedStorage&&savedStorage.energy==reserve&&!savedStorage.enabled,"Unavailable section never appears as air or destroys persisted energy/settings");
            return loaded.thenAcceptAsync(restored->{
                var live=column(p);require(restored!=null&&WorldAccess.tickingSection(live,p.y)!=null,"Native disk reload republishes the same Y section for "+(cubic?"RocksDb":"IndexedStorage"));
                var bridge=new Vector3i(5,123,19);require(savedVariant.equals(WorldAccess.blockType(live,bridge).getId()),"Native disk save/reload retains changed Connection35 geometry");
                snapshot("RELOADED_BEFORE_FACTORY_ACCESS",savedStorage);
                var component=factory.component(world,savedStorage);require(component!=null&&component.data.energy==reserve&&savedStorage.energy==reserve,"Native disk reload retains the authoritative storage energy: expected="+reserve+", state="+savedStorage.energy+", native="+(component==null?"null":component.data.energy));
                require(savedPayload.equals(FactoryComponent.CODEC.encode(component,new com.hypixel.hytale.codec.ExtraInfo())),"Native parked-holder promotion retains the entire exact Factory payload, including owner, identity, containers, settings and energy");
                require(BlockModule.getBlockEntity(world,p.x,p.y,p.z)!=null&&world.getChunkStore().getStore().getComponent(world.getChunkStore().getChunkSectionReferenceAtBlock(p.x,p.y,p.z),BlockComponentSection.getComponentType()).getBlockHolder(ChunkUtil.indexBlock(p.x,p.y,p.z))==null,"A promoted factory has one live native owner and no duplicate parked holder");
                for(var face:EnergyStoragePorts.Face.values())require(EnergyStoragePorts.mode(savedStorage,face)==EnergyStoragePorts.Mode.INPUT,"Native disk reload preserves local face "+face);
                source.enabled=true;savedStorage.enabled=true;for(int n=0;n<100&&savedStorage.energy==reserve;n++)tick(1);require(savedStorage.energy==reserve+20,"Native section reload restores live power transfer across the original physical route");
            },world).thenComposeAsync(v->verticalReload(),world).thenRun(()->System.out.println("NATIVE_MACHINE_LIFECYCLE_"+(cubic?"CUBIC":"LEGACY")+"_VERIFICATION_PASSED: real native placement across +/- X/Z and Y127/128 seams, copied-world Connection35 repair, conserved reserve, real parked holder, isolated activation and acknowledged "+(cubic?"independent-section":"whole-column")+" disk reload."));
        }
        void snapshot(String phase,MachineState state){
            var chunks=world.getChunkStore();var store=chunks.getStore();var ref=BlockModule.getBlockEntity(world,state.x,state.y,state.z);FactoryComponent component=null;String publication="NONE";
            if(ref!=null&&ref.isValid()){component=store.getComponent(ref,FactoryComponent.getComponentType());publication="LIVE";}
            else{var section=chunks.getChunkSectionReferenceAtBlock(state.x,state.y,state.z);var blocks=section==null?null:store.getComponent(section,BlockComponentSection.getComponentType());var holder=blocks==null?null:blocks.getBlockHolder(ChunkUtil.indexBlock(state.x,state.y,state.z));if(holder!=null){component=holder.getComponent(FactoryComponent.getComponentType());publication="PARKED";}}
            System.out.println("NATIVE_MACHINE_RELOAD_SNAPSHOT "+(cubic?"CUBIC":"LEGACY")+" "+phase+" expected="+reserve+" state="+state.energy+" "+publication+" "+(component==null?"NO_FACTORY":FactoryComponent.CODEC.encode(component,new com.hypixel.hytale.codec.ExtraInfo()).asDocument().toJson()));
        }
        CompletableFuture<Void> verticalReload(){
            if(!cubic)return CompletableFuture.completedFuture(null);
            source.enabled=false;savedStorage.enabled=false;verticalSource.enabled=true;verticalSource.energy=1000;verticalStorage.enabled=true;verticalStorage.energy=200;tick(1);
            int supplied=verticalSource.energy,stored=verticalStorage.energy;var p=verticalStorage.block();
            return save(p).thenComposeAsync(v->{
                var chunks=world.getChunkStore();var section=chunks.getChunkSectionReferenceAtBlock(p.x,p.y,p.z);chunks.removeSection(section,RemoveReason.UNLOAD);tick(25);
                require(WorldAccess.tickingSection(column(verticalSource.block()),verticalSource.y)!=null&&WorldAccess.section(column(p),p.y)==null,"Vertical source remains active while only the native sink section unloads");
                require(verticalSource.energy==supplied&&verticalStorage.energy==stored,"Cached cross-section route stops immediately and conserves both reserves");
                return chunks.getChunkSectionReferenceAtBlockAsync(p.x,p.y,p.z,GetChunkFlags.SET_TICKING).thenAcceptAsync(restored->{
                    require(restored!=null&&restored.isValid()&&factory.component(world,verticalStorage).data.energy==stored,"Reload retains the unloaded vertical sink's native reserve");
                    for(int n=0;n<100&&verticalStorage.energy==stored;n++)tick(1);
                    require(verticalSource.energy==supplied-20&&verticalStorage.energy==stored+20,"Native vertical section publication restores the same cached route without duplication");
                },world);
            },world);
        }
        @Override public void close(){if(player!=null)player.close();factory.close();machines.cleanupPresentation(world);machines.close();active=null;}
    }
    public static CompletableFuture<Void> verifyAsync(ResearchService research){
        return verifyAsync(research,false).thenCompose(v->verifyAsync(research,true)).thenRun(()->System.out.println("NATIVE_MACHINE_LIFECYCLE_VERIFICATION_PASSED: both actual native storage providers and section/column lifecycles."));
    }
    private static CompletableFuture<Void> verifyAsync(ResearchService research,boolean cubic){
        var config=new WorldConfig();config.setWorldGenProvider(cubic?com.hypixel.hytale.server.core.universe.world.worldgen.provider.CubicTestWorldGenProvider.CODEC.decode(org.bson.BsonDocument.parse("{\"PlatformBlockType\":\"Empty\",\"EdgeBlockType\":\"Empty\",\"CornerBlockType\":\"Empty\",\"PlatformHalfExtent\":0,\"PrefabPaths\":[],\"PrefabDensity\":0.0}"),new com.hypixel.hytale.codec.ExtraInfo()):new FlatWorldGenProvider());config.setSpawningNPC(false);config.setIsSpawnMarkersEnabled(false);config.setBlockTicking(false);config.setCanUnloadChunks(false);
        if(cubic)config.setChunkStorageProvider(new com.hypixel.hytale.server.core.universe.world.storage.provider.RocksDbChunkStorageProvider());
        String name="sm_power_lifecycle_"+UUID.randomUUID().toString().replace("-","");
        return Universe.get().makeWorld(name,Universe.get().validateWorldPath(name),config).thenCompose(world->CompletableFuture.supplyAsync(()->{
            try{var c=new Context(world,research,cubic);active=c;return c;}catch(Exception e){throw new CompletionException(e);}
        },world).thenCompose(c->CompletableFuture.allOf(WorldAccess.load(world,ChunkUtil.indexChunk(-1,0)),WorldAccess.load(world,ChunkUtil.indexChunk(0,0)),WorldAccess.load(world,ChunkUtil.indexChunk(1,0)),WorldAccess.load(world,ChunkUtil.indexChunk(0,-1)),WorldAccess.load(world,ChunkUtil.indexChunk(0,1)))
            .thenComposeAsync(unused->{
                if(!cubic)return CompletableFuture.completedFuture(null);
                var sections=new ArrayList<CompletableFuture<?>>();for(var at:List.of(new int[]{-1,0},new int[]{0,0},new int[]{1,0},new int[]{0,-1},new int[]{0,1}))for(int y:new int[]{3,4,6,7,8})sections.add(world.getChunkStore().getChunkSectionReferenceAsync(at[0],y,at[1],GetChunkFlags.SET_TICKING));
                return CompletableFuture.allOf(sections.toArray(CompletableFuture[]::new));
            },world).thenComposeAsync(unused->{c.seams();c.exactSavedLayout();c.isolatedActivation();c.parkedSection();return c.save().thenComposeAsync(v->c.reload(),world);},world)
            .whenCompleteAsync((v,failure)->c.close(),world)));
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
