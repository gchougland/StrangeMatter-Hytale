package com.hexvane.strangematter.anomaly;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.BlockEntity;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.hitboxcollision.HitboxCollision;
import com.hypixel.hytale.server.core.modules.entity.hitboxcollision.HitboxCollisionConfig;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.storage.component.ChunkSavingSystems;
import org.joml.Vector3d;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** Small native moving platforms. The durable receipt owns terrain; presentation entities own no drops. */
final class GravityTerrain {
    static final String COLLIDER="SM_Gravity_Terrain";
    private static final Gson GSON=new GsonBuilder().setPrettyPrinting().create();
    private static final System.Logger LOG=System.getLogger(GravityTerrain.class.getName());
    record Cell(int x,int y,int z,String block) {}
    record Receipt(UUID id,UUID anomaly,String world,double cx,double cy,double cz,List<Cell> cells) {}
    record Placed(int x,int y,int z) {}
    record Saved(int version,List<Receipt> terrain,Map<String,Set<Placed>> placed) {}
    private static final class Moving {
        final Receipt receipt;final List<Ref<EntityStore>> parts=new ArrayList<>();
        double age;Vector3d offset=new Vector3d();
        Moving(Receipt receipt){this.receipt=receipt;}
    }
    private final Path file;
    private final Function<World,CompletableFuture<Void>> saveWorld;
    private final Map<UUID,Receipt> receipts=new LinkedHashMap<>();
    private final Map<UUID,Moving> moving=new HashMap<>();
    private final Map<UUID,CompletableFuture<Void>> saving=new HashMap<>();
    private final Set<UUID> returning=new HashSet<>();
    private final Set<UUID> restoredInMemory=new HashSet<>();
    private final Map<UUID,Long> saveRetry=new HashMap<>();
    private final Map<String,Set<Placed>> placed=new HashMap<>();
    private final Map<String,Double> retry=new HashMap<>();

    GravityTerrain(Path directory){this(directory,w->ChunkSavingSystems.saveChunksInWorld(w.getChunkStore().getStore(),w));}
    GravityTerrain(Path directory,Function<World,CompletableFuture<Void>> saveWorld){
        file=directory.resolve("gravity-terrain.json");this.saveWorld=saveWorld;
        if(Files.exists(file))try(var reader=Files.newBufferedReader(file,StandardCharsets.UTF_8)){
            var saved=GSON.fromJson(reader,Saved.class);
            if(saved==null||saved.version()!=1||saved.terrain()==null)throw new IOException("Invalid terrain receipt file");
            for(var r:saved.terrain()){
                if(r.id()==null||r.anomaly()==null||r.world()==null||r.cells()==null||r.cells().isEmpty()||r.cells().size()>5
                        ||!Double.isFinite(r.cx()+r.cy()+r.cz())||receipts.putIfAbsent(r.id(),r)!=null)throw new IOException("Invalid terrain receipt");
                for(var c:r.cells())if(c.y()<1||c.y()>=ChunkUtil.HEIGHT-4||!natural(c.block()))throw new IOException("Invalid terrain block");
            }
            if(saved.placed()!=null)for(var entry:saved.placed().entrySet())if(entry.getKey()!=null&&entry.getValue()!=null)placed.put(entry.getKey(),new HashSet<>(entry.getValue()));
        }catch(IOException|RuntimeException ex){throw new IllegalStateException("Cannot load gravity terrain receipts; original preserved",ex);}
    }

    synchronized void tick(World world,List<AnomalyRecord> fields,double dt){
        Set<UUID> active=new HashSet<>();for(var field:fields)active.add(field.id);
        for(var m:List.copyOf(moving.values()))if(m.receipt.world().equals(world.getName())){
            if(!active.contains(m.receipt.anomaly())||m.parts.stream().anyMatch(r->!r.isValid())
                    ||m.receipt.cells().stream().anyMatch(c->chunk(world,c.x(),c.z())==null))detach(world,m);
            else animate(world,m,dt);
        }
        recover(world);
        double clock=retry.getOrDefault(world.getName(),0d)-dt;retry.put(world.getName(),clock);
        if(clock>0)return;retry.put(world.getName(),8d);
        if(HitboxCollisionConfig.getAssetMap().getAsset(COLLIDER)==null)return;
        for(var a:fields){
            // A relocated field gives zero G without repeatedly excavating the player's laboratory.
            if(!a.natural||a.released||receipts.values().stream().anyMatch(r->r.anomaly().equals(a.id)))continue;
            if(receipts.values().stream().filter(r->r.world().equals(world.getName())).count()>=48)break;
            for(int arm=0;arm<3;arm++){
                double angle=arm*Math.PI*2/3+(a.id.getLeastSignificantBits()&1023)*Math.PI/512;
                int x=(int)Math.floor(a.x+Math.cos(angle)*3.8),z=(int)Math.floor(a.z+Math.sin(angle)*3.8);
                var cells=findPatch(world,a,x,z);if(cells.isEmpty())continue;
                var receipt=new Receipt(UUID.randomUUID(),a.id,world.getName(),a.x,a.y,a.z,cells);
                lift(world,receipt);
            }
        }
    }
    private List<Cell> findPatch(World world,AnomalyRecord a,int x,int z){
        for(int y=Math.min(ChunkUtil.HEIGHT-5,(int)Math.floor(a.y)+1);y>=Math.max(2,(int)Math.floor(a.y)-7);y--){
            List<Cell> cells=new ArrayList<>();boolean valid=true;
            for(int dx=0;dx<2;dx++)for(int dz=0;dz<2;dz++){
                var chunk=chunk(world,x+dx,z+dz);
                if(chunk==null){valid=false;continue;}
                var block=chunk.getBlockType(x+dx,y,z+dz);
                if(block==null||!natural(block.getId())||placed.getOrDefault(world.getName(),Set.of()).contains(new Placed(x+dx,y,z+dz))
                        ||block.getBlockEntity()!=null||chunk.getFiller(x+dx,y,z+dz)!=0
                        ||chunk.getRotationIndex(x+dx,y,z+dz)!=0||chunk.getFluidId(x+dx,y,z+dz)!=0){valid=false;continue;}
                for(int above=1;above<=4;above++)if(!empty(world,x+dx,y+above,z+dz)){valid=false;break;}
                if(reserved(world.getName(),x+dx,y,z+dz)){valid=false;continue;}
                cells.add(new Cell(x+dx,y,z+dz,block.getId()));
            }
            if(valid&&cells.size()==4&&!nearConstruction(world,x,y,z))return List.copyOf(cells);
        }
        return List.of();
    }
    private boolean reserved(String world,int x,int y,int z){return receipts.values().stream().filter(r->r.world().equals(world))
            .flatMap(r->r.cells().stream()).anyMatch(c->c.x()==x&&c.y()==y&&c.z()==z);}
    static boolean natural(String block){return AnomalyTerrain.soil(block)||AnomalyTerrain.rock(block)
            ||"SM_Anomalous_Grass".equals(block)||"SM_Anomalous_Dirt".equals(block);}
    private boolean nearConstruction(World world,int x,int y,int z){
        var edits=placed.getOrDefault(world.getName(),Set.of());
        for(int dx=-2;dx<=3;dx++)for(int dy=-2;dy<=3;dy++)for(int dz=-2;dz<=3;dz++){
            if(edits.contains(new Placed(x+dx,y+dy,z+dz)))return true;
            var source=chunk(world,x+dx,z+dz);if(source==null||y+dy<1||y+dy>=ChunkUtil.HEIGHT)continue;
            var block=source.getBlockType(x+dx,y+dy,z+dz);if(block==null)continue;String id=block.getId();
            if(id.startsWith("SM_")&&!natural(id)&&!id.endsWith("_Ore"))return true;
            for(String part:id.split("_"))if(Set.of("Planks","Brick","Bricks","Stairs","Half","Quarter","ThreeQuarter","Wall","Fence","Roof","Beam","Pillar","Tile","Tiles","Smooth","Ornate","Furniture","Door","Trapdoor","Lantern","Lamp","Workbench","Chest").contains(part))return true;
        }
        return false;
    }
    synchronized void placed(World world,int x,int y,int z){
        var edits=placed.computeIfAbsent(world.getName(),k->new HashSet<>());var cell=new Placed(x,y,z);
        if(edits.add(cell))try{persist();}catch(RuntimeException ex){edits.remove(cell);throw ex;}
    }
    synchronized boolean protectedReturn(World world,int x,int y,int z){
        return receipts.values().stream().filter(r->r.world().equals(world.getName())&&returning.contains(r.id()))
                .anyMatch(r->r.cells().stream().anyMatch(c->c.x()==x&&c.y()==y&&c.z()==z));
    }
    /** Native environmental destruction is not cancellable. Its cell has now been consumed. */
    synchronized void environmentBreak(World world,int x,int y,int z){
        for(var r:List.copyOf(receipts.values())){
            if(!r.world().equals(world.getName())||!returning.contains(r.id()))continue;
            var retained=r.cells().stream().filter(c->c.x()!=x||c.y()!=y||c.z()!=z).toList();
            if(retained.size()==r.cells().size())continue;
            if(retained.isEmpty())receipts.remove(r.id());
            else receipts.put(r.id(),new Receipt(r.id(),r.anomaly(),r.world(),r.cx(),r.cy(),r.cz(),retained));
            try{persist();}catch(RuntimeException ex){receipts.put(r.id(),r);throw ex;}
            // The old save may have captured the block before destruction. A fresh save must
            // acknowledge the post-event terrain before this remaining receipt can retire.
            saving.remove(r.id());saveRetry.remove(r.id());
            if(retained.isEmpty()){returning.remove(r.id());restoredInMemory.remove(r.id());}
        }
    }
    private void lift(World world,Receipt receipt){
        receipts.put(receipt.id(),receipt);
        try{persist();}catch(RuntimeException ex){receipts.remove(receipt.id());throw ex;}
        var m=new Moving(receipt);moving.put(receipt.id(),m);
        try{
            for(var c:receipt.cells()){
                var source=chunk(world,c.x(),c.z());
                if(source==null||!c.block().equals(source.getBlockType(c.x(),c.y(),c.z()).getId()))throw new IllegalStateException("Terrain changed before lift");
                source.setBlock(c.x(),c.y(),c.z(),"Empty");
                if(!empty(world,c.x(),c.y(),c.z()))throw new IllegalStateException("Terrain could not be lifted");
                m.parts.add(spawnBlock(world,c));
            }
        }catch(RuntimeException ex){detach(world,m);LOG.log(System.Logger.Level.WARNING,"Floating terrain could not be created; restoration receipt retained",ex);}
    }
    static Ref<EntityStore> spawnBlock(World world,Cell c){
        var store=world.getEntityStore().getStore();var holder=EntityStore.REGISTRY.newHolder();
        holder.addComponent(BlockEntity.getComponentType(),new BlockEntity(c.block()));
        holder.addComponent(TransformComponent.getComponentType(),new TransformComponent(new Vector3d(c.x()+.5,c.y()+.5,c.z()+.5),new Rotation3f()));
        holder.addComponent(BoundingBox.getComponentType(),new BoundingBox(new Box(-.5,-.5,-.5,.5,.5,.5)));
        holder.addComponent(HitboxCollision.getComponentType(),new HitboxCollision(Objects.requireNonNull(HitboxCollisionConfig.getAssetMap().getAsset(COLLIDER))));
        holder.addComponent(NetworkId.getComponentType(),new NetworkId(world.getEntityStore().takeNextNetworkId()));
        holder.addComponent(UUIDComponent.getComponentType(),new UUIDComponent(UUID.randomUUID()));
        holder.ensureComponent(EntityModule.get().getVisibleComponentType());
        holder.ensureComponent(EntityStore.REGISTRY.getNonSerializedComponentType());
        // Omitting Velocity excludes the native falling-block physics tick. HitboxCollision supplies
        // solid collision and native entity anchoring; no player teleport or artificial floor is used.
        return store.addEntity(holder,AddReason.SPAWN);
    }
    private void animate(World world,Moving m,double dt){
        m.age+=dt;var r=m.receipt;
        double eased=Math.min(1,m.age/4);eased=eased*eased*(3-2*eased);
        double angle=Math.max(0,m.age-4)*.045;
        double px=r.cells().stream().mapToDouble(c->c.x()+.5).average().orElse(r.cx()),pz=r.cells().stream().mapToDouble(c->c.z()+.5).average().orElse(r.cz());
        double rx=px-r.cx(),rz=pz-r.cz();
        var target=new Vector3d(rx*(Math.cos(angle)-1)-rz*Math.sin(angle),(2.3+.35*Math.sin(m.age*.65))*eased,rx*Math.sin(angle)+rz*(Math.cos(angle)-1));
        var step=new Vector3d(target).sub(m.offset);double maxStep=1.1*Math.min(.25,dt);
        if(step.length()>maxStep)step.normalize(maxStep);
        target.set(m.offset).add(step);
        for(int sample=1;sample<=6;sample++)if(!clear(world,r,new Vector3d(step).mul(sample/6d).add(m.offset)))return;
        m.offset.set(target);var store=world.getEntityStore().getStore();
        for(int i=0;i<m.parts.size();i++){
            var c=r.cells().get(i);var transform=store.getComponent(m.parts.get(i),TransformComponent.getComponentType());
            if(transform!=null)transform.setPosition(new Vector3d(c.x()+.5,c.y()+.5,c.z()+.5).add(target));
        }
    }
    private static boolean clear(World world,Receipt receipt,Vector3d offset){
        for(var c:receipt.cells())for(int x=(int)Math.floor(c.x()+offset.x+.02);x<=Math.floor(c.x()+offset.x+.98);x++)
            for(int y=(int)Math.floor(c.y()+offset.y+.02);y<=Math.floor(c.y()+offset.y+.98);y++)
                for(int z=(int)Math.floor(c.z()+offset.z+.02);z<=Math.floor(c.z()+offset.z+.98);z++)if(!empty(world,x,y,z))return false;
        return true;
    }
    synchronized void remove(World world,UUID anomaly){
        for(var m:List.copyOf(moving.values()))if(m.receipt.world().equals(world.getName())&&m.receipt.anomaly().equals(anomaly))detach(world,m);
        recover(world);
    }
    synchronized void stopWorld(World world){
        for(var m:List.copyOf(moving.values()))if(m.receipt.world().equals(world.getName()))detach(world,m);
        recover(world);retry.remove(world.getName());
    }
    private void detach(World world,Moving m){
        moving.remove(m.receipt.id());
        for(var ref:m.parts)if(ref.isValid())world.getEntityStore().getStore().removeEntity(ref,RemoveReason.REMOVE);
    }
    private void recover(World world){
        List<UUID> restored=new ArrayList<>();
        for(var r:List.copyOf(receipts.values())){
            if(!r.world().equals(world.getName())||moving.containsKey(r.id()))continue;
            var future=saving.get(r.id());
            if(future!=null){
                if(!future.isDone())continue;
                saving.remove(r.id());
                if(!future.isCompletedExceptionally()){
                    receipts.remove(r.id());try{persist();}catch(RuntimeException ex){receipts.put(r.id(),r);throw ex;}
                    returning.remove(r.id());restoredInMemory.remove(r.id());saveRetry.remove(r.id());continue;
                }
                LOG.log(System.Logger.Level.WARNING,"Terrain save failed; gravity restoration receipt retained for retry");
                saveRetry.put(r.id(),System.nanoTime()+5_000_000_000L);
            }
            if(restoredInMemory.contains(r.id())){
                // These cells have already been restored in this process. A failed I/O attempt
                // must only repeat the checkpoint, never replenish blocks a later event consumed.
                if(System.nanoTime()>=saveRetry.getOrDefault(r.id(),0L))restored.add(r.id());
                continue;
            }
            boolean complete=true;
            // Validate the entire patch before restoring any cell. A blocked return cannot
            // replenish the other cells repeatedly while somebody mines the restored surface.
            for(var c:r.cells()){
                var source=chunk(world,c.x(),c.z());var block=source==null?null:source.getBlockType(c.x(),c.y(),c.z());
                if(source==null||(block==null||!c.block().equals(block.getId()))&&!empty(world,c.x(),c.y(),c.z())){complete=false;break;}
            }
            if(!complete)continue;
            returning.add(r.id());
            for(var c:r.cells()){
                var source=chunk(world,c.x(),c.z());
                if(source==null){complete=false;continue;}
                var block=source.getBlockType(c.x(),c.y(),c.z());
                if(block!=null&&c.block().equals(block.getId()))continue;
                // Never overwrite a construction occupying the old hole. Keep the receipt and
                // retry when the source cell becomes empty, including after a server restart.
                if(!empty(world,c.x(),c.y(),c.z())){complete=false;continue;}
                source.setBlock(c.x(),c.y(),c.z(),c.block());
                if(!c.block().equals(source.getBlockType(c.x(),c.y(),c.z()).getId()))complete=false;
            }
            if(complete){restoredInMemory.add(r.id());restored.add(r.id());}
        }
        if(!restored.isEmpty()){
            CompletableFuture<Void> future;
            try{future=saveWorld.apply(world);}catch(RuntimeException ex){future=CompletableFuture.failedFuture(ex);}
            for(var id:restored)saving.put(id,future);
        }
    }
    private static WorldChunk chunk(World world,int x,int z){return world.getChunkIfLoaded(ChunkUtil.indexChunkFromBlock(x,z));}
    private static boolean empty(World world,int x,int y,int z){
        if(y<1||y>=ChunkUtil.HEIGHT)return false;var chunk=chunk(world,x,z);
        return chunk!=null&&chunk.getBlock(x,y,z)==0&&chunk.getFluidId(x,y,z)==0;
    }
    synchronized List<Receipt> receipts(){return List.copyOf(receipts.values());}
    synchronized List<Ref<EntityStore>> parts(){return moving.values().stream().flatMap(m->m.parts.stream()).toList();}
    private void persist(){
        try{
            Files.createDirectories(file.getParent());var next=file.resolveSibling(file.getFileName()+".tmp");
            byte[] bytes=GSON.toJson(new Saved(1,List.copyOf(receipts.values()),placed)).getBytes(StandardCharsets.UTF_8);
            try(var channel=FileChannel.open(next,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)){
                var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
            }
            try{Files.move(next,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException ex){Files.move(next,file,StandardCopyOption.REPLACE_EXISTING);}
        }catch(IOException ex){throw new UncheckedIOException("Cannot save gravity terrain receipt",ex);}
    }
}
