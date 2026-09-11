package com.hexvane.strangematter.worldgen;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hexvane.strangematter.util.InventoryOps;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.ChunkColumn;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.universe.world.events.ChunkPreLoadProcessEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.FillerBlockUtil;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import org.joml.Vector3d;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Fresh settlement plots, persistent native NPC merchants and authoritative world-thread transactions. */
public final class ScientistService {
    @FunctionalInterface public interface MachineRegistrar { void register(World world,org.joml.Vector3i position,String itemId); }
    public static final String ROLE="SM_Anomaly_Scientist";
    public static final String APPEARANCE="SM_Klops_Scientist";
    private static final Gson GSON=new GsonBuilder().setPrettyPrinting().create();
    private record Block(int x,int y,int z,String name,int rotation,int filler) {}
    private record Prefab(List<Block> blocks) {}
    private record SaveData(int version,List<ScientistRecord> scientists) {}
    private final Map<UUID,ScientistRecord> scientists=new LinkedHashMap<>();
    private final Map<String,Double> timers=new HashMap<>();
    private final Path file;
    private final Prefab laboratory;
    private boolean dirty;
    private int laboratoryWeight=6,ordinaryPlotWeight=10;
    private boolean generationEnabled=true;
    private MachineRegistrar machineRegistrar=(world,position,id)->{};

    public ScientistService(Path directory) {
        file=directory.resolve("scientists.json");
        try(InputStream input=getClass().getResourceAsStream("/Server/Prefabs/StrangeMatter/Anomaly_Scientist_Lab.prefab.json")) {
            if(input==null)throw new IOException("Missing native scientist laboratory prefab");
            laboratory=GSON.fromJson(new InputStreamReader(input,StandardCharsets.UTF_8),Prefab.class);
        }catch(IOException e){throw new UncheckedIOException(e);}
        if(Files.exists(file))try(Reader reader=Files.newBufferedReader(file,StandardCharsets.UTF_8)) {
            SaveData data=GSON.fromJson(reader,SaveData.class);
            if(data==null||data.version!=1||data.scientists==null)throw new IOException("Invalid scientist save");
            for(var state:data.scientists)scientists.put(state.id,state);
        }catch(IOException e){throw new UncheckedIOException("Cannot load scientist identities",e);}
    }
    public synchronized void setGenerationEnabled(boolean enabled){generationEnabled=enabled;}
    public synchronized void setMachineRegistrar(MachineRegistrar registrar){machineRegistrar=Objects.requireNonNull(registrar);}
    /** Weight six is source-authentic. The other pool weight is explicitly a Hytale placement adaptation. */
    public synchronized void setPlotWeights(int laboratory,int ordinary){laboratoryWeight=Math.max(0,laboratory);ordinaryPlotWeight=Math.max(0,ordinary);}
    public synchronized ScientistRecord record(UUID id){return scientists.get(id);}
    public synchronized Collection<ScientistRecord> records(){return List.copyOf(scientists.values());}

    /** Register at EventPriority.FIRST, before native EARLY block-entity initialization. Never touches an existing chunk. */
    public synchronized void onChunkPreLoad(ChunkPreLoadProcessEvent event) {
        if(!generationEnabled||laboratoryWeight==0||!event.isNewlyGenerated())return;
        WorldChunk chunk=event.getChunk();World world=chunk.getWorld();
        if(world.getName().toLowerCase(Locale.ROOT).contains("instance"))return;
        var column=event.getHolder().getComponent(ChunkColumn.getComponentType());
        if(column==null||column.getSectionHolders()==null)return;
        // The original is a village house, never a wilderness ruin. Native settlement furniture is the plot marker.
        boolean settlement=false;
        outer:for(int x=1;x<32;x+=2)for(int z=1;z<32;z+=2) {
            int top=chunk.getHeight(x,z);
            for(int y=Math.max(1,top-16);y<=top;y++) {
                BlockType block=chunk.getBlockType(x,y,z);if(block==null)continue;String id=block.getId();
                if(id.startsWith("Furniture_Kweebec_")||id.startsWith("Furniture_Village_")){settlement=true;break outer;}
            }
        }
        if(!settlement)return;
        for(var state:scientists.values())if(state.world.equals(world.getName())&&Math.abs(state.x-chunk.getX()*32)<128&&Math.abs(state.z-chunk.getZ()*32)<128)return;
        Random random=new Random(world.getWorldConfig().getSeed()^ChunkUtil.indexChunk(chunk.getX(),chunk.getZ())^0x537472616e67654cL);
        if(random.nextInt(Math.max(1,laboratoryWeight+ordinaryPlotWeight))>=laboratoryWeight)return;
        for(int attempt=0;attempt<48;attempt++) {
            int localX=2+random.nextInt(20),localZ=2+random.nextInt(21),y=chunk.getHeight(localX,localZ);
            if(!emptyPlot(chunk,localX,y,localZ))continue;
            int x=chunk.getX()*32+localX,z=chunk.getZ()*32+localZ;
            if(!placeGenerated(chunk,column,localX,y,localZ))continue;
            ScientistRecord record=new ScientistRecord(UUID.randomUUID(),world.getName(),x,y,z);
            scientists.put(record.id,record);dirty=true;save();return;
        }
    }
    private boolean emptyPlot(WorldChunk chunk,int x,int y,int z) {
        if(y<2||y>310)return false;
        for(int dx=-1;dx<=9;dx++)for(int dz=-1;dz<=8;dz++) {
            int top=chunk.getHeight(x+dx,z+dz);if(top!=y)return false;
            BlockType ground=chunk.getBlockType(x+dx,y,z+dz);
            if(ground==null)return false;String id=ground.getId();
            if(!(id.startsWith("Soil_Grass")||id.startsWith("Soil_Dirt")||id.startsWith("Soil_Sand")||id.startsWith("Soil_Gravel")||id.startsWith("Soil_Snow")))return false;
            for(int dy=1;dy<=8;dy++)if(chunk.getBlock(x+dx,y+dy,z+dz)!=0)return false;
        }
        return true;
    }
    private boolean placeGenerated(WorldChunk chunk,ChunkColumn column,int x,int y,int z) {
        if(column==null||column.getSectionHolders()==null)return false;
        var sections=column.getSectionHolders();
        for(Block block:laboratory.blocks) {
            if(!"Empty".equals(block.name)&&BlockType.getAssetMap().getIndex(block.name)<0)return false;
            var holder=sections[ChunkUtil.indexSection(y+block.y)];if(holder==null)return false;
            var fluid=holder.getComponent(FluidSection.getComponentType());
            if(fluid!=null&&fluid.getFluidId(x+block.x,y+block.y,z+block.z)!=0)return false;
        }
        // Resolve full native collision footprints before mutation; reject any component that protrudes beyond the checked plot.
        List<Block> blocks=new ArrayList<>(laboratory.blocks);
        for(Block block:laboratory.blocks) {
            if("Empty".equals(block.name)||block.filler!=0)continue;
            int id=BlockType.getAssetMap().getIndex(block.name);
            var boxes=FillerBlockUtil.multiCellFootprint(id,block.rotation);
            if(boxes!=null)FillerBlockUtil.forEachFillerBlock(boxes,(dx,dy,dz)->{
                if(dx!=0||dy!=0||dz!=0)blocks.add(new Block(block.x+dx,block.y+dy,block.z+dz,block.name,block.rotation,FillerBlockUtil.pack(dx,dy,dz)));
            });
        }
        for(Block block:blocks)if(block.x<0||block.x>=9||block.y<0||block.y>=8||block.z<0||block.z>=8)return false;
        // A native furnishing can have a larger collision box than its Minecraft counterpart. Never erase a wall to fit it.
        for(Block filler:blocks)if(filler.filler!=0)for(Block original:laboratory.blocks)
            if(filler.x==original.x&&filler.y==original.y&&filler.z==original.z&&!"Empty".equals(original.name)&&!filler.name.equals(original.name))return false;
        for(Block block:blocks) {
            int id="Empty".equals(block.name)?0:BlockType.getAssetMap().getIndex(block.name);
            var section=sections[ChunkUtil.indexSection(y+block.y)].ensureAndGetComponent(BlockSection.getComponentType());
            section.set(x+block.x,y+block.y,z+block.z,id,block.rotation,block.filler);
        }
        for(int dx=0;dx<9;dx++)for(int dz=0;dz<8;dz++)chunk.getBlockChunk().updateHeight(x+dx,z+dz);
        return true;
    }
    /** Explicit placement by an operator/native settlement integrator. Does not place terrain or respawn a dead scientist. */
    public synchronized UUID spawnScientist(World world,Vector3d position) {
        var state=new ScientistRecord(UUID.randomUUID(),world.getName(),(int)Math.floor(position.x-5.832658),(int)Math.floor(position.y-1),(int)Math.floor(position.z-2.335622));
        scientists.put(state.id,state);spawn(world,state,position);dirty=true;save();return state.id;
    }
    private void spawn(World world,ScientistRecord state,Vector3d position) {
        var store=world.getEntityStore().getStore();var pair=NPCPlugin.get().spawnNPC(store,ROLE,null,position,new Rotation3f());
        if(pair==null)return;
        var uuid=store.getComponent(pair.first(),UUIDComponent.getComponentType());
        if(uuid!=null){state.entity=uuid.getUuid();state.spawned=true;dirty=true;}
    }
    public synchronized void tick(World world,double dt) {
        double time=timers.getOrDefault(world.getName(),0d)+dt;
        if(time<.25){timers.put(world.getName(),time);return;}timers.put(world.getName(),0d);
        var store=world.getEntityStore().getStore();
        var date=store.getResource(WorldTimeResource.getResourceType()).getGameDateTime();
        long slot=date.toLocalDate().toEpochDay()*2+(date.getHour()>=12?1:0);
        for(var state:scientists.values()) {
            if(!state.world.equals(world.getName()))continue;
            if(world.getChunkIfLoaded(ChunkUtil.indexChunkFromBlock(state.x,state.z))==null)continue;
            registerMachine(world,state,4,1,1,"SM_Research_Machine");
            registerMachine(world,state,6,2,1,"SM_Stasis_Projector");
            if(!state.spawned)spawn(world,state,new Vector3d(state.x+5.832658,state.y+1,state.z+2.335622));
            if(state.entity==null)continue;
            var ref=world.getEntityStore().getRefFromUUID(state.entity);if(ref==null||!ref.isValid())continue;
            var npc=store.getComponent(ref,NPCEntity.getComponentType());if(npc==null||!ROLE.equals(npc.getRoleName()))continue;
            // The former stationary role omitted LeashPos from native saves. Its
            // default zero vector is not the laboratory home; preserve all valid homes.
            if(npc.getLeashPoint().lengthSquared()==0)
                npc.setLeashPoint(new Vector3d(state.x+5.832658,state.y+1,state.z+2.335622));
            var model=store.getComponent(ref,ModelComponent.getComponentType());
            if(model!=null&&Set.of("Klops","Klops_Merchant").contains(model.getModel().getModelAssetId())) {
                var asset=ModelAsset.getAssetMap().getAsset(APPEARANCE);
                if(asset!=null)store.putComponent(ref,ModelComponent.getComponentType(),new ModelComponent(Model.createScaledModel(asset,model.getModel().getScale())));
            }
            var transform=store.getComponent(ref,TransformComponent.getComponentType());if(transform==null)continue;
            var workstation=world.getBlockType(state.x+4,state.y+1,state.z+1);
            if(date.getHour()>=6&&date.getHour()<18&&workstation!=null&&workstation.getId().startsWith("SM_Research_Machine")&&transform.getPosition().distanceSquared(state.x+4.5,state.y+1,state.z+1.5)<36)
                dirty|=state.restock(slot);
            StateSupport support=StateSupport.get(ref,store);
            if(support==null)continue;
            boolean trading=false;
            for(var player:world.getPlayerRefs()) {
                var playerEntity=player.getReference();if(playerEntity==null||!playerEntity.isValid())continue;
                if(support.consumeInteraction(playerEntity))open(player,store,state.id);
                var customer=store.getComponent(playerEntity,Player.getComponentType());
                if(customer!=null&&customer.getPageManager().getCustomPage() instanceof ScientistPage page
                        &&page.isTradingWith(state.id)&&near(state.id,player,store))trading=true;
            }
            // Read the actual open pages so closing, disconnecting or changing worlds
            // cannot leave a merchant stuck, and one customer cannot release another's trade.
            boolean paused=support.inState(support.getStateHelper().getStateIndex("Trading"));
            if(trading!=paused)support.setState(ref,trading?"Trading":"Idle",null,store);
        }
        save();
    }
    private void registerMachine(World world,ScientistRecord lab,int x,int y,int z,String id) {
        var position=new org.joml.Vector3i(lab.x+x,lab.y+y,lab.z+z);
        var block=world.getBlockType(position.x,position.y,position.z);
        if(block!=null&&id.equals(block.getId()))machineRegistrar.register(world,position,id);
    }
    public synchronized boolean open(PlayerRef player,Store<EntityStore> store,UUID identity) {
        var ref=player.getReference();if(ref==null||!ref.isValid()||!near(identity,player,store))return false;
        var entity=store.getComponent(ref,Player.getComponentType());if(entity==null)return false;
        entity.getPageManager().openCustomPage(ref,store,new ScientistPage(player,this,identity));
        var merchant=store.getExternalData().getWorld().getEntityStore().getRefFromUUID(scientists.get(identity).entity);
        if(merchant!=null&&merchant.isValid()) {
            var support=StateSupport.get(merchant,store);
            if(support!=null&&!support.inState(support.getStateHelper().getStateIndex("Trading")))
                support.setState(merchant,"Trading",null,store);
        }
        return true;
    }
    private boolean near(UUID identity,PlayerRef player,Store<EntityStore> store) {
        var state=scientists.get(identity);var world=store.getExternalData().getWorld();
        if(state==null||state.entity==null||!state.world.equals(world.getName()))return false;
        var merchant=world.getEntityStore().getRefFromUUID(state.entity);var ref=player.getReference();
        if(merchant==null||!merchant.isValid()||ref==null||!ref.isValid())return false;
        var a=store.getComponent(merchant,TransformComponent.getComponentType());var b=store.getComponent(ref,TransformComponent.getComponentType());
        var npc=store.getComponent(merchant,NPCEntity.getComponentType());
        return npc!=null&&ROLE.equals(npc.getRoleName())&&a!=null&&b!=null&&a.getPosition().distanceSquared(b.getPosition())<=36;
    }
    public synchronized String trade(UUID identity,PlayerRef player,Store<EntityStore> store,String offerId) {
        if(!near(identity,player,store))return "The scientist is too far away.";
        var state=scientists.get(identity);var offer=ScientistTrades.get(offerId);
        if(offer==null||!state.offers().contains(offer))return "That trade is not available.";
        if(state.stock(offer)==0)return "This offer is sold out until the scientist restocks.";
        var inventory=InventoryComponent.getCombined(store,player.getReference(),InventoryComponent.HOTBAR_FIRST);
        if(inventory==null)return "Your inventory is unavailable.";
        var output=new ItemStack(offer.output(),offer.outputCount());
        if(!inventory.canAddItemStack(output,true,false))return "Make room for the offered items.";
        var cost=Map.of(offer.input(),state.price(offer));var removed=InventoryOps.take(inventory,cost);
        if(removed==null)return "You do not have the required items.";
        if(!InventoryOps.give(inventory,output)){for(var item:removed)InventoryOps.give(inventory,item);return "Trade cancelled: no inventory space.";}
        int before=ScientistTrades.tier(state.xp);state.traded(offer);dirty=true;save();
        return ScientistTrades.tier(state.xp)>before?"The scientist is now "+ScientistTrades.tierName(ScientistTrades.tier(state.xp))+". New offers unlocked.":"Trade completed.";
    }
    public synchronized void save() {
        if(!dirty)return;
        try {
            Files.createDirectories(file.getParent());Path next=file.resolveSibling(file.getFileName()+".tmp");
            try(Writer writer=Files.newBufferedWriter(next,StandardCharsets.UTF_8)){GSON.toJson(new SaveData(1,new ArrayList<>(scientists.values())),writer);}
            try{Files.move(next,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException e){Files.move(next,file,StandardCopyOption.REPLACE_EXISTING);}dirty=false;
        }catch(IOException e){throw new UncheckedIOException("Cannot save scientist identities and stock",e);}
    }
}
