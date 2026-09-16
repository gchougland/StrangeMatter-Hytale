package com.hexvane.strangematter.equipment;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hexvane.strangematter.util.WorldAccess;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.DrawType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.*;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.BlockEntity;
import com.hypixel.hytale.server.core.event.events.ecs.*;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.component.*;
import com.hypixel.hytale.server.core.modules.entity.hitboxcollision.*;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.storage.component.ChunkSavingSystems;
import org.joml.Vector3d;
import org.joml.Vector3i;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.BiPredicate;

/** Write-ahead ownership of a cube or intact native chest. Presentation never owns inventory.
 * Source removal is checkpointed before relocation; destination placement is checkpointed before
 * retiring a receipt. Reserved cells cannot be mined during either checkpoint.
 */
final class GraviticBlockJournal {
    static final Box BOX=new Box(-.49,-.49,-.49,.49,.49,.49);
    enum Phase { PREPARED, HELD, PLACING, CONSUMED }
    record Cell(int x,int y,int z) { Vector3i pos(){return new Vector3i(x,y,z);} Vector3d center(){return new Vector3d(x+.5,y+.5,z+.5);} }
    record Receipt(UUID id,UUID owner,String world,String block,int rotation,Cell source,Cell destination,Phase phase,String chest,List<Cell> footprint) {
        Receipt(UUID id,UUID owner,String world,String block,int rotation,Cell source,Cell destination,Phase phase){this(id,owner,world,block,rotation,source,destination,phase,null,List.of(new Cell(0,0,0)));}
        Receipt phase(Phase p,Cell cell){return new Receipt(id,owner,world,block,rotation,source,cell,p,chest,footprint);}
        List<Cell> cells(Cell anchor){return footprint.stream().map(p->new Cell(anchor.x()+p.x(),anchor.y()+p.y(),anchor.z()+p.z())).toList();}
    }
    record Saved(int version,UUID journal,List<Receipt> receipts) {Saved(int version,List<Receipt> receipts){this(version,null,receipts);}}
    private static final Gson GSON=new GsonBuilder().setPrettyPrinting().create();
    private static final System.Logger LOG=System.getLogger(GraviticBlockJournal.class.getName());
    private final Path file;
    private final Function<World,CompletableFuture<Void>> saveWorld;
    private final Map<UUID,Receipt> receipts=new LinkedHashMap<>();
    private final Map<UUID,CompletableFuture<Void>> checkpoints=new HashMap<>();
    private final Set<UUID> materialized=new HashSet<>(),attached=new HashSet<>();
    private final Map<UUID,Long> retries=new HashMap<>();
    private boolean checkingPermission;
    private UUID journal=UUID.randomUUID();
    private BiPredicate<World,Vector3i> moveBlocked=(world,pos)->false;
    void moveBlocked(BiPredicate<World,Vector3i> value){moveBlocked=Objects.requireNonNull(value);}
    GraviticBlockJournal(Path directory){this(directory,w->ChunkSavingSystems.saveChunksInWorld(w.getChunkStore().getStore(),w));}
    GraviticBlockJournal(Path directory,Function<World,CompletableFuture<Void>> saveWorld){
        this.saveWorld=saveWorld;
        file=directory.resolve("gravitic-blocks.json");
        if(Files.exists(file))try(var reader=Files.newBufferedReader(file)){
            var saved=GSON.fromJson(reader,Saved.class);if(saved==null||(saved.version()!=1&&saved.version()!=2)||saved.receipts()==null)throw new IOException("Unsupported gravitic journal");
            if(saved.journal()!=null)journal=saved.journal();
            for(var original:saved.receipts()){
                var r=original!=null&&original.footprint()==null?new Receipt(original.id(),original.owner(),original.world(),original.block(),original.rotation(),original.source(),original.destination(),original.phase(),original.chest(),List.of(new Cell(0,0,0))):original;
                if(r==null||r.id()==null||r.owner()==null||r.world()==null||r.world().isBlank()||r.block()==null||r.block().isBlank()||r.source()==null||r.source().y()<0||r.source().y()>=ChunkUtil.HEIGHT||r.phase()==null||r.rotation()<0||r.rotation()>=RotationTuple.VALUES.length
                        ||r.footprint().isEmpty()||r.footprint().size()>8||r.footprint().stream().anyMatch(c->c==null||Math.abs(c.x())>2||Math.abs(c.z())>2||c.y()!=0)
                        ||(r.phase()==Phase.PLACING&&r.destination()==null)||receipts.putIfAbsent(r.id(),r)!=null)throw new IOException("Invalid gravitic receipt");
                // Other plugins register their native holder components later in setup.
                // Defer native decoding until restoration runs in the initialized world.
                if(r.chest()!=null)org.bson.BsonDocument.parse(r.chest());
            }
        }catch(IOException|RuntimeException ex){throw new IllegalStateException("Cannot load gravitic receipts; original file preserved",ex);}
    }
    static boolean eligible(World world,Vector3i pos){
        pos=origin(world,pos);
        if(pos==null||pos.y<1||pos.y>=ChunkUtil.HEIGHT-1)return false;
        var chunk=WorldAccess.loaded(world,ChunkUtil.indexChunkFromBlock(pos.x,pos.z));if(chunk==null)return false;
        var block=WorldAccess.blockType(chunk,pos);if(block==null)return false;
        if(GraviticChestTransport.chest(block)){
            var container=GraviticChestTransport.container(world,pos);if(container==null||GraviticChestTransport.marker(world,pos)!=null)return false;
            var footprint=GraviticChestTransport.offsets(block,WorldAccess.rotation(chunk,pos.x,pos.y,pos.z));
            if(footprint.isEmpty()||footprint.size()>8)return false;
            for(var offset:footprint){if(offset.y()!=0||Math.abs(offset.x())>2||Math.abs(offset.z())>2)return false;
                var part=WorldAccess.loaded(world,ChunkUtil.indexChunkFromBlock(pos.x+offset.x(),pos.z+offset.z()));
                var point=new Vector3i(pos.x+offset.x(),pos.y,pos.z+offset.z());
                if(part==null||WorldAccess.fluid(part,point.x,point.y,point.z)!=0||!pos.equals(origin(world,point))
                        ||WorldAccess.block(part,point.x,point.y,point.z)!=BlockType.getAssetMap().getIndex(block.getId())
                        ||WorldAccess.rotation(part,point.x,point.y,point.z)!=WorldAccess.rotation(chunk,pos.x,pos.y,pos.z))return false;}
            return true;
        }
        if(block.getDrawType()!=DrawType.Cube||block.getBlockEntity()!=null||block.getBench()!=null||block.getState()!=null||block.getFarming()!=null||block.getSeats()!=null||block.getBeds()!=null)return false;
        var gathering=block.getGathering();if(gathering==null||gathering.getBreaking()==null)return false;
        // Cube-only transport avoids native multi-block/custom furniture state and shape loss.
        if(WorldAccess.filler(chunk,pos.x,pos.y,pos.z)!=0||WorldAccess.fluid(chunk,pos.x,pos.y,pos.z)!=0)return false;
        var bounds=com.hypixel.hytale.server.core.asset.type.blockhitbox.BlockBoundingBoxes.getAssetMap().getAsset(block.getHitboxTypeIndex());
        if(bounds!=null){var box=bounds.get(0).getBoundingBox();if(box.min.x<0||box.min.y<0||box.min.z<0||box.max.x>1||box.max.y>1||box.max.z>1)return false;}
        return !com.hexvane.strangematter.machine.MachineService.IDS.contains(block.getId());
    }
    boolean allowedBreak(World world,Ref<EntityStore> owner,ItemStack item,Vector3i pos){
        pos=origin(world,pos);
        if(pos==null||!world.getGameplayConfig().getWorldConfig().isBlockBreakingAllowed()||!eligible(world,pos)||reserved(world,pos)||moveBlocked.test(world,pos))return false;
        var event=new BreakBlockEvent(item,new Vector3i(pos),world.getBlockType(pos.x,pos.y,pos.z));
        world.getEntityStore().getStore().invoke(owner,event);
        return !event.isCancelled()&&event.getTargetBlock().equals(pos)&&eligible(world,pos);
    }
    UUID begin(World world,UUID owner,Vector3i pos){
        pos=origin(world,pos);if(pos==null)return null;
        var chunk=WorldAccess.loaded(world,ChunkUtil.indexChunkFromBlock(pos.x,pos.z));if(chunk==null||!eligible(world,pos)||reserved(world,pos)||moveBlocked.test(world,pos))return null;
        boolean chest=GraviticChestTransport.chest(WorldAccess.blockType(chunk,pos));
        if(chest)GraviticChestTransport.close(world,pos);
        var type=WorldAccess.blockType(chunk,pos);int rotation=WorldAccess.rotation(chunk,pos.x,pos.y,pos.z);
        String payload=null;if(chest){var holder=GraviticChestTransport.snapshot(world,pos);if(holder==null)return null;payload=GraviticChestTransport.encode(holder);}
        var receipt=new Receipt(UUID.randomUUID(),owner,world.getName(),type.getId(),rotation,new Cell(pos.x,pos.y,pos.z),null,Phase.PREPARED,payload,chest?GraviticChestTransport.offsets(type,rotation):List.of(new Cell(0,0,0)));
        receipts.put(receipt.id(),receipt);try{persist();}catch(RuntimeException ex){receipts.remove(receipt.id());throw ex;}
        attached.add(receipt.id());
        if(chest&&!GraviticChestTransport.detach(world,pos)){attached.remove(receipt.id());return null;}
        if(!WorldAccess.set(chunk,pos.x,pos.y,pos.z,"Empty")||!empty(world,receipt.source(),receipt)){attached.remove(receipt.id());return null;}
        checkpoint(world,receipt.id());return receipt.id();
    }
    boolean ready(UUID id){var r=receipts.get(id);return r!=null&&r.phase()==Phase.HELD;}
    Receipt receipt(UUID id){return receipts.get(id);}
    Ref<EntityStore> visual(World world,UUID id){
        var r=receipts.get(id);if(r==null)return null;var store=world.getEntityStore().getStore();var holder=EntityStore.REGISTRY.newHolder();var rotation=RotationTuple.get(r.rotation());
        holder.addComponent(BlockEntity.getComponentType(),new BlockEntity(r.block()));
        holder.addComponent(TransformComponent.getComponentType(),new TransformComponent(r.source().center(),new Rotation3f((float)rotation.pitch().getRadians(),(float)rotation.yaw().getRadians(),(float)rotation.roll().getRadians())));
        holder.addComponent(BoundingBox.getComponentType(),new BoundingBox(r.chest()==null?BOX:GraviticChestTransport.collider(BlockType.getAssetMap().getAsset(r.block()),r.rotation())));
        var collision=HitboxCollisionConfig.getAssetMap().getAsset("SM_Gravity_Terrain");if(collision!=null)holder.addComponent(HitboxCollision.getComponentType(),new HitboxCollision(collision));
        holder.addComponent(NetworkId.getComponentType(),new NetworkId(world.getEntityStore().takeNextNetworkId()));holder.addComponent(UUIDComponent.getComponentType(),new UUIDComponent(id));
        holder.ensureComponent(EntityModule.get().getVisibleComponentType());holder.ensureComponent(EntityStore.REGISTRY.getNonSerializedComponentType());
        return store.addEntity(holder,AddReason.SPAWN);
    }
    boolean place(World world,UUID id,Ref<EntityStore> owner,Vector3d around){
        var r=receipts.get(id);if(r==null||r.phase()!=Phase.HELD)return false;
        var candidates=destinations(around);candidates.add(r.source());
        for(var cell:candidates){
            if(!empty(world,cell,r)||r.cells(cell).stream().anyMatch(p->reserved(world,p.pos()))||!clearOfBodies(world,cell,r))continue;
            if(owner!=null&&owner.isValid()&&owner.getStore()==world.getEntityStore().getStore()){
                if(!world.getGameplayConfig().getWorldConfig().isBlockPlacementAllowed())continue;
                var itemId=BlockType.getAssetMap().getAsset(r.block()).getItem();
                var event=new PlaceBlockEvent(itemId==null?null:new ItemStack(itemId.getId(),1),cell.pos(),RotationTuple.get(r.rotation()));
                checkingPermission=true;try{world.getEntityStore().getStore().invoke(owner,event);}finally{checkingPermission=false;}
                if(event.isCancelled()||!event.getTargetBlock().equals(cell.pos())||event.getRotation().index()!=r.rotation()||!empty(world,cell,r))continue;
            }else if(!cell.equals(r.source()))continue; // Offline recovery can only undo its previously authorized removal.
            replace(r,r.phase(Phase.PLACING,cell));attached.remove(id);restore(world,receipts.get(id));return true;
        }
        return false;
    }
    static ArrayList<Cell> destinations(Vector3d around){
        var origin=AdvancedGadgetTargeting.cell(around);var result=new ArrayList<Cell>();
        for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)result.add(new Cell(origin.x+dx,origin.y+dy,origin.z+dz));
        result.sort(Comparator.comparingDouble(cell->cell.center().distanceSquared(around)));return result;
    }
    void detach(UUID id){attached.remove(id);}
    void tick(World world){
        for(var r:List.copyOf(receipts.values())){
            if(!r.world().equals(world.getName()))continue;
            var checkpoint=checkpoints.get(r.id());
            if(checkpoint!=null){
                if(!checkpoint.isDone())continue;checkpoints.remove(r.id());
                if(checkpoint.isCompletedExceptionally()){retries.put(r.id(),System.nanoTime()+5_000_000_000L);LOG.log(System.Logger.Level.WARNING,"Gravitic block checkpoint failed; ownership retained");continue;}
                if(r.phase()==Phase.PREPARED){replace(r,r.phase(Phase.HELD,null));r=receipts.get(r.id());}
                else if(r.phase()==Phase.PLACING||r.phase()==Phase.CONSUMED){retire(r);continue;}
            }
            if(System.nanoTime()<retries.getOrDefault(r.id(),0L))continue;
            if(r.phase()==Phase.PREPARED){
                if(attached.contains(r.id())){checkpoint(world,r.id());continue;}
                // A restart before source acknowledgement rolls back at the original cell.
                if(empty(world,r.source(),r)||matches(world,r.source(),r)||repairableChest(world,r.source(),r)){replace(r,r.phase(Phase.PLACING,r.source()));restore(world,receipts.get(r.id()));}
            }else if(r.phase()==Phase.HELD&&!attached.contains(r.id()))place(world,r.id(),null,r.source().center());
            else if(r.phase()==Phase.PLACING)restore(world,r);
            else if(r.phase()==Phase.CONSUMED)checkpoint(world,r.id());
        }
        unlockCommitted(world);
    }
    private void restore(World world,Receipt r){
        if(materialized.contains(r.id())){checkpoint(world,r.id());return;}
        var cell=r.destination();if(cell==null)return;boolean created=false;
        if(!matches(world,cell,r)){
            if(!clearOfBodies(world,cell,r))return;
            if(!empty(world,cell,r)){
                // A crash can checkpoint only one column of a joined chest. Rebuild only
                // provably owned fragments; unrelated construction remains untouched.
                if(!repairableChest(world,cell,r))return;
                if(GraviticChestTransport.container(world,cell.pos())!=null&&!GraviticChestTransport.detach(world,cell.pos()))return;
                for(var p:r.cells(cell)){var part=WorldAccess.loaded(world,ChunkUtil.indexChunkFromBlock(p.x(),p.z()));WorldAccess.set(part,p.x(),p.y(),p.z(),"Empty");}
                if(!empty(world,cell,r))return;
            }
            var chunk=WorldAccess.loaded(world,ChunkUtil.indexChunkFromBlock(cell.x(),cell.z()));
            var type=BlockType.getAssetMap().getAsset(r.block());if(type==null)return;
            WorldAccess.set(chunk,cell.x(),cell.y(),cell.z(),BlockType.getAssetMap().getIndex(r.block()),type,r.rotation(),0,0);
            if(!matches(world,cell,r))return;
            created=true;
        }
        if(r.chest()!=null){
            var marker=GraviticChestTransport.marker(world,cell.pos());
            if(marker==null){
                var existing=GraviticChestTransport.snapshot(world,cell.pos());
                if(existing==null)return;
                // An unmarked original chest is accepted only when its complete saved payload
                // matches. Never overwrite another chest just because its model looks the same.
                boolean original=cell.equals(r.source())&&org.bson.BsonDocument.parse(GraviticChestTransport.encode(existing)).equals(org.bson.BsonDocument.parse(r.chest()));
                if(!created&&!original)return;
                if(!GraviticChestTransport.attach(world,cell.pos(),BlockType.getAssetMap().getAsset(r.block()),r.rotation(),r.chest(),journal,r.id()))return;
            }else if(!journal.equals(marker.journal)||!r.id().equals(marker.receipt))return;
        }
        materialized.add(r.id());checkpoint(world,r.id());
    }
    private boolean repairableChest(World world,Cell cell,Receipt r){
        if(r.chest()==null)return false;
        for(int i=0;i<r.footprint().size();i++){
            var offset=r.footprint().get(i);var p=new Cell(cell.x()+offset.x(),cell.y()+offset.y(),cell.z()+offset.z());
            if(empty(world,p))continue;var chunk=WorldAccess.loaded(world,ChunkUtil.indexChunkFromBlock(p.x(),p.z()));if(chunk==null)return false;
            int filler=com.hypixel.hytale.server.core.util.FillerBlockUtil.pack(offset.x(),offset.y(),offset.z());
            if(WorldAccess.block(chunk,p.x(),p.y(),p.z())!=BlockType.getAssetMap().getIndex(r.block())||WorldAccess.rotation(chunk,p.x(),p.y(),p.z())!=r.rotation()
                    ||WorldAccess.filler(chunk,p.x(),p.y(),p.z())!=filler||WorldAccess.fluid(chunk,p.x(),p.y(),p.z())!=0)return false;
        }
        var marker=GraviticChestTransport.marker(world,cell.pos());
        if(marker!=null)return journal.equals(marker.journal)&&r.id().equals(marker.receipt);
        var existing=GraviticChestTransport.snapshot(world,cell.pos());
        if(existing==null)return empty(world,cell); // Only owned filler fragments remain.
        return cell.equals(r.source())&&org.bson.BsonDocument.parse(GraviticChestTransport.encode(existing)).equals(org.bson.BsonDocument.parse(r.chest()));
    }
    boolean reserved(World world,Vector3i pos){
        if(checkingPermission)return false;
        if(GraviticChestMarker.locked(world,pos))return true;
        for(var r:receipts.values())if(r.world().equals(world.getName())){
            var cell=r.phase()==Phase.PREPARED?r.source():r.phase()==Phase.PLACING?r.destination():null;
            if(cell!=null&&r.cells(cell).stream().anyMatch(p->p.x()==pos.x&&p.y()==pos.y&&p.z()==pos.z))return true;
        }
        return false;
    }
    void environmentBreak(World world,Vector3i pos){
        for(var r:List.copyOf(receipts.values()))if(r.world().equals(world.getName())&&r.phase()==Phase.PLACING&&r.cells(r.destination()).stream().anyMatch(p->p.pos().equals(pos))){
            replace(r,r.phase(Phase.CONSUMED,r.destination()));checkpoints.remove(r.id());materialized.remove(r.id());
        }
    }
    private static boolean empty(World world,Cell cell){
        if(cell.y()<1||cell.y()>=ChunkUtil.HEIGHT-1)return false;var chunk=WorldAccess.loaded(world,ChunkUtil.indexChunkFromBlock(cell.x(),cell.z()));
        return chunk!=null&&WorldAccess.block(chunk,cell.x(),cell.y(),cell.z())==0&&WorldAccess.fluid(chunk,cell.x(),cell.y(),cell.z())==0;
    }
    private static boolean empty(World world,Cell cell,Receipt receipt){return receipt.cells(cell).stream().allMatch(p->empty(world,p));}
    static Vector3i origin(World world,Vector3i pos){
        if(pos==null||WorldAccess.loaded(world,ChunkUtil.indexChunkFromBlock(pos.x,pos.z))==null)return null;
        var p=com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.SimpleBlockInteraction.resolveBaseBlockPosition(world,new com.hypixel.hytale.protocol.BlockPosition(pos.x,pos.y,pos.z));
        return new Vector3i(p.x,p.y,p.z);
    }
    private static boolean matches(World world,Cell cell,Receipt r){
        var chunk=WorldAccess.loaded(world,ChunkUtil.indexChunkFromBlock(cell.x(),cell.z()));if(chunk==null)return false;
        var type=WorldAccess.blockType(chunk,cell.x(),cell.y(),cell.z());
        if(type==null||!r.block().equals(type.getId())||r.rotation()!=WorldAccess.rotation(chunk,cell.x(),cell.y(),cell.z()))return false;
        return r.cells(cell).stream().allMatch(p->cell.pos().equals(origin(world,p.pos()))&&world.getBlock(p.x(),p.y(),p.z())==BlockType.getAssetMap().getIndex(r.block()));
    }
    private static boolean clearOfBodies(World world,Cell cell,Receipt r){
        var store=world.getEntityStore().getStore();var min=new Vector3d(cell.x()+.01,cell.y()+.01,cell.z()+.01);var max=new Vector3d(cell.x()+.99,cell.y()+.99,cell.z()+.99);
        for(var p:r.cells(cell)){min.min(new Vector3d(p.x()+.01,p.y()+.01,p.z()+.01));max.max(new Vector3d(p.x()+.99,p.y()+.99,p.z()+.99));}
        for(var ref:EquipmentQueries.inBox(store,min,max,false)){
            var uuid=store.getComponent(ref,UUIDComponent.getComponentType());if(uuid!=null&&r.id().equals(uuid.getUuid()))continue;
            if(store.getComponent(ref,BoundingBox.getComponentType())!=null)return false;
        }
        return true;
    }
    private void unlockCommitted(World world){
        var type=GraviticChestMarker.getComponentType();if(type==null)return;
        world.getChunkStore().getStore().forEachChunk(type,(chunk,commands)->{
            for(int i=0;i<chunk.size();i++){var marker=chunk.getComponent(i,type);if(!journal.equals(marker.journal)||receipts.containsKey(marker.receipt))continue;
                var container=chunk.getComponent(i,com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock.getComponentType());
                if(container!=null)container.getItemContainer().setGlobalFilter(com.hypixel.hytale.server.core.inventory.container.filter.FilterType.ALLOW_ALL);
                var info=chunk.getComponent(i,com.hypixel.hytale.server.core.modules.block.BlockModule.BlockStateInfo.getComponentType());if(info!=null)info.markNeedsSaving();
                commands.removeComponent(chunk.getReferenceTo(i),type);
            }
        });
    }
    private void checkpoint(World world,UUID id){if(checkpoints.containsKey(id))return;try{checkpoints.put(id,saveWorld.apply(world));}catch(RuntimeException ex){checkpoints.put(id,CompletableFuture.failedFuture(ex));}}
    private void replace(Receipt before,Receipt after){receipts.put(before.id(),after);try{persist();}catch(RuntimeException ex){receipts.put(before.id(),before);throw ex;}}
    private void retire(Receipt r){receipts.remove(r.id());try{persist();}catch(RuntimeException ex){receipts.put(r.id(),r);throw ex;}materialized.remove(r.id());attached.remove(r.id());retries.remove(r.id());}
    private void persist(){
        try{
            Files.createDirectories(file.getParent());var next=file.resolveSibling(file.getFileName()+".tmp");byte[] bytes=GSON.toJson(new Saved(2,journal,List.copyOf(receipts.values()))).getBytes(StandardCharsets.UTF_8);
            try(var channel=FileChannel.open(next,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)){var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}
            try{Files.move(next,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException ex){Files.move(next,file,StandardCopyOption.REPLACE_EXISTING);}
        }catch(IOException ex){throw new UncheckedIOException("Cannot save gravitic block ownership",ex);}
    }
}
