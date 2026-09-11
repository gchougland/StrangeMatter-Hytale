package com.hexvane.strangematter.equipment;

import com.hexvane.strangematter.util.WorldAccess;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hexvane.strangematter.anomaly.AnomalyService;
import com.hexvane.strangematter.anomaly.AnomalyType;
import com.hexvane.strangematter.effects.GadgetEffects;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.BlockMaterial;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.component.*;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import org.bson.BsonDocument;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;
import org.joml.Vector3d;
import org.joml.Vector3i;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Moving shots with a write-ahead capsule queue. Native visual entities are disposable presentations. */
final class LaboratoryProjectiles {
    enum Phase { PREPARED, FLIGHT, REFUND }
    enum SaveResult { WAIT, RETRY, FLIGHT, REFUNDED }
    enum LaunchResult { LAUNCHED, ALREADY_QUEUED, INVALID_TOKEN, ITEM_MOVED, STORAGE_FAILED }
    static final class Shot {
        UUID id,owner;String world,token,item;Vector3d position,velocity;AnomalyType type;boolean creative;
        Phase phase=Phase.FLIGHT;double age;
        transient ItemStack capsule;transient Ref<EntityStore> visual;
        transient CompletableFuture<Void> inventorySave;transient boolean reconciled;
        transient long retryAfterNanos,lastSaveErrorNanos;
        transient Throwable saveFailure;
        boolean chrono(){return type==null;}
        boolean persistent(){return token!=null;}
    }
    private record SavedFlights(int version,List<Shot> flights) {}
    private static final Gson GSON=new GsonBuilder().setPrettyPrinting().create();
    private static final JsonWriterSettings BSON_JSON=JsonWriterSettings.builder().outputMode(JsonMode.EXTENDED).build();
    private static final System.Logger LOG=System.getLogger(LaboratoryProjectiles.class.getName());
    private final AnomalyService anomalies;
    private final LaboratoryFields fields;
    private final Map<String,List<Shot>> shots=new HashMap<>();
    private final Set<String> activeTokens=ConcurrentHashMap.newKeySet();
    private final Path file;
    private double checkpoint;

    LaboratoryProjectiles(AnomalyService anomalies,LaboratoryFields fields,Path directory) {
        this.anomalies=anomalies;this.fields=fields;file=directory.resolve("capsule-flights.json");
        for(Shot shot:readFlights(file)){shots.computeIfAbsent(shot.world,k->new ArrayList<>()).add(shot);activeTokens.add(shot.token);}
    }
    static String encodeCapsule(ItemStack capsule){return ItemStack.CODEC.encode(capsule,new com.hypixel.hytale.codec.ExtraInfo()).asDocument().toJson(BSON_JSON);}
    static ItemStack decodeCapsule(String encoded){return ItemStack.CODEC.decode(BsonDocument.parse(encoded),new com.hypixel.hytale.codec.ExtraInfo());}
    static List<Shot> readFlights(Path file) {
        if(!Files.exists(file))return List.of();
        try(Reader reader=Files.newBufferedReader(file,StandardCharsets.UTF_8)) {
            SavedFlights saved=GSON.fromJson(reader,SavedFlights.class);
            if(saved==null||saved.version!=1||saved.flights==null)throw new IOException("Invalid capsule flight ledger");
            Set<UUID> ids=new HashSet<>();Set<String> tokens=new HashSet<>();
            for(Shot shot:saved.flights) {
                if(shot.id==null||shot.owner==null||shot.world==null||shot.type==null||shot.phase==null||shot.token==null||shot.item==null||!finite(shot.position)||!finite(shot.velocity)||!Double.isFinite(shot.age)||shot.age<0||!ids.add(shot.id)||!tokens.add(shot.token))throw new IOException("Invalid capsule flight identity");
                var item=BsonDocument.parse(shot.item);
                if(!shot.type.capsuleItemId().equals(item.getString("Id").getValue())||item.getNumber("Quantity").intValue()!=1||!shot.token.equals(item.getDocument("Metadata").getString("SMAnomaly").getValue()))throw new IOException("Capsule payload does not match its saved identity");
                String[] token=shot.token.split(":",-1);if(token.length!=2)throw new IOException("Malformed capsule nonce");UUID.fromString(token[0]);UUID.fromString(token[1]);
            }
            return saved.flights;
        }catch(IOException e){throw new UncheckedIOException("Cannot load capsule flights; original file preserved",e);}
        catch(RuntimeException e){throw new IllegalStateException("Invalid capsule flight ledger; original file preserved",e);}
    }
    static void writeFlights(Path file,Collection<Shot> flights) {
        try {
            Files.createDirectories(file.getParent());Path next=file.resolveSibling(file.getFileName()+".tmp");
            try(Writer writer=Files.newBufferedWriter(next,StandardCharsets.UTF_8)){GSON.toJson(new SavedFlights(1,flights.stream().filter(Shot::persistent).toList()),writer);}
            try{Files.move(next,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException e){Files.move(next,file,StandardCopyOption.REPLACE_EXISTING);}
        }catch(IOException e){throw new UncheckedIOException("Cannot persist capsule flight identities",e);}
    }
    private static boolean finite(Vector3d p){return p!=null&&Double.isFinite(p.x)&&Double.isFinite(p.y)&&Double.isFinite(p.z);}
    private List<Shot> all(){return shots.values().stream().flatMap(Collection::stream).toList();}
    private void save(){writeFlights(file,all());}
    boolean inFlight(String token){return token!=null&&activeTokens.contains(token);}
    boolean validCapsule(ItemStack capsule,boolean creative) {
        String token=capsule.getFromMetadataOrNull("SMAnomaly",Codec.STRING);
        if(token==null)return creative&&type(capsule)!=null;
        return tokenValid(token,type(capsule));
    }
    private boolean tokenValid(String token,AnomalyType type) {
        if(type==null||token==null)return false;
        try {
            String[] parts=token.split(":",-1);if(parts.length!=2)return false;
            UUID id=UUID.fromString(parts[0]),nonce=UUID.fromString(parts[1]);
            return anomalies.get(id).filter(a->a.contained&&nonce.equals(a.capsuleNonce)&&a.type==type).isPresent();
        }catch(IllegalArgumentException e){return false;}
    }
    private static AnomalyType type(ItemStack capsule){for(var type:AnomalyType.values())if(type.capsuleItemId().equals(capsule.getItemId()))return type;return null;}

    /** Queue persistence precedes consumption; native required inventory persistence precedes flight. */
    synchronized LaunchResult launchCapsule(World world,PlayerRef owner,Vector3d eye,Vector3d direction,ItemContainer container,short slot,ItemStack capsule,boolean creative) {
        if(!validCapsule(capsule,creative))return LaunchResult.INVALID_TOKEN;
        String token=capsule.getFromMetadataOrNull("SMAnomaly",Codec.STRING);if(inFlight(token))return LaunchResult.ALREADY_QUEUED;
        if(slot<0||slot>=container.getCapacity()||!capsule.equals(container.getItemStack(slot)))return LaunchResult.ITEM_MOVED;
        Shot shot=new Shot();shot.id=UUID.randomUUID();shot.owner=owner.getUuid();shot.world=world.getName();shot.position=throwOrigin(world,eye,direction);shot.velocity=new Vector3d(direction).mul(20).add(0,2,0);
        shot.capsule=capsule.withQuantity(1);shot.token=token;shot.type=type(capsule);shot.creative=creative;shot.reconciled=true;
        shot.item=encodeCapsule(shot.capsule);shot.phase=requiresInventoryAcknowledgement(shot)?Phase.PREPARED:Phase.FLIGHT;
        if(shot.persistent()&&!activeTokens.add(token))return LaunchResult.ALREADY_QUEUED;
        shots.computeIfAbsent(shot.world,k->new ArrayList<>()).add(shot);
        if(shot.persistent())try{save();}catch(RuntimeException e){shots.get(shot.world).remove(shot);activeTokens.remove(token);LOG.log(System.Logger.Level.ERROR,"Capsule launch queue could not be persisted",e);return LaunchResult.STORAGE_FAILED;}
        if(!creative&&!removeExact(container,slot,capsule,1)){retire(shot);return LaunchResult.ITEM_MOVED;}
        if(requiresInventoryAcknowledgement(shot))forceInventorySave(world,owner,shot);
        GadgetEffects.use(world,"SM_Capsule_Throw",EquipmentQueries.handheldOrigin(eye,direction));
        return LaunchResult.LAUNCHED;
    }
    static boolean requiresInventoryAcknowledgement(Shot shot){return shot.persistent()&&!shot.creative;}
    /** Keep the visible hand offset from skipping a wall when throwing at point-blank range. */
    static Vector3d throwOrigin(World world,Vector3d eye,Vector3d direction){
        var muzzle=EquipmentQueries.handheldOrigin(eye,direction);var result=new Vector3d(eye);
        for(int i=1;i<=8;i++){
            var point=new Vector3d(eye).lerp(muzzle,i/8.0);var pos=block(point);
            if(pos.y<0||pos.y>=ChunkUtil.HEIGHT||WorldAccess.loaded(world,ChunkUtil.indexChunkFromBlock(pos.x,pos.z))==null)break;
            var block=world.getBlockType(pos.x,pos.y,pos.z);if(block==null||block.getMaterial()!=BlockMaterial.Empty)break;
            result.set(point);
        }
        return result;
    }
    /** Atomic expected-value replacement removes a unique payload without a stackability predicate. */
    static boolean removeExact(ItemContainer container,short slot,ItemStack expected,int quantity){
        if(quantity<=0||expected==null||expected.getQuantity()<quantity||slot<0||slot>=container.getCapacity())return false;
        boolean[] removed={false};
        // Native replaceItemStackInSlot checks stackability, not quantity equality. Its callback
        // alternative holds the container write lock while we compare the complete expected value.
        container.replaceAll((currentSlot,current)->{
            if(currentSlot!=slot||!expected.equals(current))return current;
            removed[0]=true;return current.withQuantity(current.getQuantity()-quantity);
        });
        return removed[0];
    }
    synchronized String status(String token){
        if(token==null)return "Ready to throw";
        for(Shot shot:all())if(token.equals(shot.token))return shot.phase==Phase.REFUND?"Recovering to inventory":shot.phase==Phase.PREPARED?"Securing capsule inventory save":"Capsule in flight";
        return "Ready to throw";
    }
    synchronized void chrono(World world,UUID owner,Vector3d eye,Vector3d direction) {
        Shot shot=new Shot();shot.id=UUID.randomUUID();shot.owner=owner;shot.world=world.getName();shot.position=new Vector3d(eye);shot.velocity=new Vector3d(direction).mul(30);shot.reconciled=true;
        shots.computeIfAbsent(shot.world,k->new ArrayList<>()).add(shot);
    }
    private void visual(World world,Shot shot) {
        if(shot.visual!=null&&shot.visual.isValid())return;
        String modelId=shot.chrono()?"SM_Temporal_Bloom_Core":"SM_Capsule_Projectile_"+shot.type.capsuleSuffix;float scale=shot.chrono()?.25f:1;
        var store=world.getEntityStore().getStore();var asset=ModelAsset.getAssetMap().getAsset(modelId);if(asset==null)return;
        var model=Model.createScaledModel(asset,scale);var holder=EntityStore.REGISTRY.newHolder();
        holder.addComponent(TransformComponent.getComponentType(),new TransformComponent(new Vector3d(shot.position),new Rotation3f()));
        holder.addComponent(ModelComponent.getComponentType(),new ModelComponent(model));holder.addComponent(BoundingBox.getComponentType(),new BoundingBox(model.getBoundingBox()));
        holder.addComponent(HeadRotation.getComponentType(),new HeadRotation(new Rotation3f()));holder.addComponent(UUIDComponent.getComponentType(),new UUIDComponent(UUID.randomUUID()));
        holder.addComponent(NetworkId.getComponentType(),new NetworkId(store.getExternalData().takeNextNetworkId()));
        holder.addComponent(Intangible.getComponentType(),Intangible.INSTANCE);holder.addComponent(Invulnerable.getComponentType(),Invulnerable.INSTANCE);
        holder.addComponent(EntityStore.REGISTRY.getNonSerializedComponentType(),NonSerialized.get());shot.visual=store.addEntity(holder,AddReason.SPAWN);
    }
    private void forceInventorySave(World world,PlayerRef owner,Shot shot) {
        var ref=owner.getReference();var store=world.getEntityStore().getStore();
        if(ref==null||!ref.isValid()||ref.getStore()!=store)return;
        var player=store.getComponent(ref,Player.getComponentType());if(player==null)return;
        if(System.nanoTime()<shot.retryAfterNanos)return;
        shot.inventorySave=com.hexvane.strangematter.util.PlayerInventoryPersistence.save(world,owner);
    }
    private void reconcile(World world) {
        var store=world.getEntityStore().getStore();
        for(Shot shot:all()) {
            if(!shot.persistent())continue;
            if(!tokenValid(shot.token,shot.type)) {
                if(shot.visual!=null&&shot.visual.isValid()&&!shot.world.equals(world.getName()))continue;
                if(shot.world.equals(world.getName()))removeVisual(store,shot);
                retire(shot);continue;
            }
            if(shot.inventorySave!=null) {
                SaveResult result=pollInventorySave(shot);
                if(result==SaveResult.WAIT)continue;
                if(result==SaveResult.RETRY) {
                    long now=System.nanoTime();shot.retryAfterNanos=now+5_000_000_000L;
                    if(shot.lastSaveErrorNanos==0||now-shot.lastSaveErrorNanos>=30_000_000_000L){shot.lastSaveErrorNanos=now;LOG.log(System.Logger.Level.WARNING,"Capsule inventory save failed; its durable flight remains queued for retry: "+shot.id,shot.saveFailure);}
                    continue;
                }
                if(result==SaveResult.REFUNDED){retire(shot);continue;}
                save();
            }
            if(shot.reconciled&&shot.phase==Phase.FLIGHT)continue;
            // Creative throws change no native inventory. Their persisted nonce queue is sufficient;
            // waiting on a needless full player save used to stall legitimate creative playtesting.
            if(shot.creative&&shot.phase!=Phase.REFUND){shot.phase=Phase.FLIGHT;shot.reconciled=true;save();continue;}
            PlayerRef owner=null;for(var player:world.getPlayerRefs())if(shot.owner.equals(player.getUuid())){owner=player;break;}
            if(owner==null)continue;var ref=owner.getReference();if(ref==null||!ref.isValid())continue;
            var inventory=InventoryComponent.getCombined(store,ref,InventoryComponent.BACKPACK_STORAGE_HOTBAR);if(inventory==null)continue;
            if(shot.capsule==null)shot.capsule=decodeCapsule(shot.item);
            if(shot.phase==Phase.REFUND) {
                boolean present=false;
                for(short slot=0;slot<inventory.getCapacity();slot++) {
                    var item=inventory.getItemStack(slot);
                    if(sameToken(item,shot.token)&&shot.type.capsuleItemId().equals(item.getItemId())){present=true;break;}
                }
                if(!present&&!shot.creative) {
                    if(!inventory.canAddItemStack(shot.capsule,true,false)||!inventory.addItemStack(shot.capsule,true,false,false).succeeded())continue;
                    owner.sendMessage(Message.raw("Your containment capsule was recovered after its flight."));
                }
            }else if(!shot.creative) {
                // An older inventory save can still contain the consumed capsule. The queue represents that throw.
                boolean failed=false;
                for(short slot=0;slot<inventory.getCapacity();slot++) {
                    var item=inventory.getItemStack(slot);if(!sameToken(item,shot.token)||!shot.type.capsuleItemId().equals(item.getItemId()))continue;
                    if(!removeExact(inventory,slot,item,item.getQuantity())){failed=true;break;}
                }
                if(failed)continue;
                shot.phase=Phase.PREPARED;
            }
            shot.reconciled=true;forceInventorySave(world,owner,shot);
        }
    }
    static boolean sameToken(ItemStack item,String token){return item!=null&&!item.isEmpty()&&token.equals(item.getFromMetadataOrNull("SMAnomaly",Codec.STRING));}
    static SaveResult pollInventorySave(Shot shot) {
        if(shot.inventorySave==null||!shot.inventorySave.isDone())return SaveResult.WAIT;
        if(shot.inventorySave.isCompletedExceptionally()){
            try{shot.inventorySave.join();}catch(RuntimeException failure){shot.saveFailure=failure.getCause()!=null?failure.getCause():failure;}
            shot.inventorySave=null;shot.reconciled=false;return SaveResult.RETRY;
        }
        shot.saveFailure=null;shot.retryAfterNanos=0;
        shot.inventorySave=null;
        if(shot.phase==Phase.REFUND)return SaveResult.REFUNDED;
        shot.phase=Phase.FLIGHT;shot.reconciled=true;return SaveResult.FLIGHT;
    }
    synchronized void tick(World world,double dt) {
        reconcile(world);
        var list=shots.get(world.getName());if(list==null)return;var store=world.getEntityStore().getStore();
        for(Shot shot:new ArrayList<>(list)) {
            if(shot.phase!=Phase.FLIGHT||!shot.reconciled||shot.inventorySave!=null)continue;
            var origin=block(shot.position);
            if(WorldAccess.loaded(world,ChunkUtil.indexChunkFromBlock(origin.x,origin.z))==null)continue;
            visual(world,shot);shot.age+=dt;boolean done=false;
            int steps=Math.max(1,(int)Math.ceil(shot.velocity.length()*Math.min(dt,.25)/.15));double step=Math.min(dt,.25)/steps;
            for(int i=0;i<steps&&!done;i++) {
                var next=new Vector3d(shot.velocity).mul(step).add(shot.position);var pos=block(next);
                if(shot.age>10||pos.y<0||pos.y>=ChunkUtil.HEIGHT||WorldAccess.loaded(world,ChunkUtil.indexChunkFromBlock(pos.x,pos.z))==null){refund(shot);done=true;break;}
                var type=world.getBlockType(pos.x,pos.y,pos.z);
                if(type!=null&&type.getMaterial()!=BlockMaterial.Empty) {
                    var impact=shot.chrono()?block(shot.position):new Vector3i(pos).add(0,1,0);
                    var landing=impact.y<ChunkUtil.HEIGHT?world.getBlockType(impact.x,impact.y,impact.z):null;
                    if(landing==null||landing.getMaterial()!=BlockMaterial.Empty)impact=block(shot.position);
                    impact(world,shot,impact);done=true;break;
                }
                if(shot.chrono()&&hitsLiving(store,shot,next)){impact(world,shot,pos);done=true;break;}
                shot.position.set(next);if(!shot.chrono())shot.velocity.y-=12*step;
            }
            if(done){removeVisual(store,shot);continue;}
            if(shot.visual!=null&&shot.visual.isValid()) {
                var transform=store.getComponent(shot.visual,TransformComponent.getComponentType());if(transform!=null){transform.setPosition(new Vector3d(shot.position));transform.setRotation(new Rotation3f((float)(shot.age*8),(float)Math.atan2(-shot.velocity.x,-shot.velocity.z),.18f));}
            }
            if(shot.chrono())GadgetEffects.particle(world,"SM_Chrono_Trail",shot.position);
        }
        checkpoint+=dt;if(checkpoint>=.25){checkpoint=0;if(all().stream().anyMatch(Shot::persistent))save();}
    }
    private boolean hitsLiving(Store<EntityStore> store,Shot shot,Vector3d position) {
        for(var ref:EquipmentQueries.inBox(store,new Vector3d(position).sub(.12,.12,.12),new Vector3d(position).add(.12,.12,.12),false)) {
            if(!ref.isValid())continue;var player=store.getComponent(ref,PlayerRef.getComponentType());if(player!=null&&shot.owner.equals(player.getUuid()))continue;
            if(store.getComponent(ref,Player.getComponentType())!=null||store.getComponent(ref,NPCEntity.getComponentType())!=null)return true;
        }
        return false;
    }
    private void impact(World world,Shot shot,Vector3i position) {
        if(shot.chrono()){fields.temporal(world,position);GadgetEffects.use(world,"SM_Chrono_Impact",new Vector3d(position).add(.5,.5,.5));retire(shot);return;}
        boolean success;
        if(shot.token==null){anomalies.spawnRaised(shot.type,world,new Vector3d(position).add(.5,.5,.5),false);success=true;}
        else success=anomalies.releaseRaised(shot.token,world,new Vector3d(position).add(.5,.5,.5)).isPresent();
        if(success){GadgetEffects.use(world,"SM_Capsule_Impact",new Vector3d(position).add(.5,.5,.5));retire(shot);}else if(!tokenValid(shot.token,shot.type))retire(shot);else refund(shot);
    }
    private void refund(Shot shot) {
        if(shot.chrono()||!shot.persistent()||shot.creative){retire(shot);return;}
        shot.phase=Phase.REFUND;shot.reconciled=false;shot.inventorySave=null;save();
    }
    private void retire(Shot shot) {
        var list=shots.get(shot.world);if(list!=null){list.remove(shot);if(list.isEmpty())shots.remove(shot.world);}
        if(shot.persistent()) {
            try{save();}catch(RuntimeException e){shots.computeIfAbsent(shot.world,k->new ArrayList<>()).add(shot);throw e;}
            activeTokens.remove(shot.token);
        }
    }
    private static Vector3i block(Vector3d p){return new Vector3i((int)Math.floor(p.x),(int)Math.floor(p.y),(int)Math.floor(p.z));}
    private static void removeVisual(Store<EntityStore> store,Shot shot){if(shot.visual!=null&&shot.visual.isValid())store.removeEntity(shot.visual,RemoveReason.REMOVE);shot.visual=null;}
    synchronized void cleanup(World world) {
        var list=shots.get(world.getName());if(list==null)return;
        for(Shot shot:new ArrayList<>(list)){removeVisual(world.getEntityStore().getStore(),shot);if(!shot.persistent())retire(shot);}
        save(); // No joins on the world thread: unresolved inventory saves reconcile from the durable queue next load.
    }
}
