package com.hexvane.strangematter.anomaly;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.events.ChunkPreLoadProcessEvent;
import com.hypixel.hytale.server.core.universe.world.chunk.ChunkColumn;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import org.joml.Vector3d;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.BiConsumer;

/** Server-authoritative field registry. Call tick and world-facing operations on that world's thread. */
public final class AnomalyService {
    @FunctionalInterface public interface GroundingHook { boolean grounded(World world, Vector3d position, double radius); }
    @FunctionalInterface public interface FieldSuppressionHook { boolean suppressed(World world,Vector3d position,AnomalyType type); }
    @FunctionalInterface public interface FirstContactHook { void observed(UUID player,AnomalyRecord anomaly,int researchAward); }
    private static final Gson GSON=new GsonBuilder().setPrettyPrinting().create();
    private final Path saveFile;
    private final Map<UUID,AnomalyRecord> records=new LinkedHashMap<>();
    private final Map<String,Set<Long>> surveyed=new HashMap<>();
    private final Map<String,Double> surveyClocks=new HashMap<>();
    private final Map<String,World> worlds=new HashMap<>();
    private final Map<UUID,TemporalAge> temporalAges=new HashMap<>();
    private final Map<UUID,Set<AnomalyType>> firstContacts=new HashMap<>();
    private final HytaleAnomalyEffects effects=new HytaleAnomalyEffects();
    private final GravityTerrain gravityTerrain;
    private final Random random=new Random();
    private GroundingHook groundingHook=(w,p,r)->false;
    private FieldSuppressionHook suppressionHook=(w,p,t)->false;
    private BiConsumer<World,Vector3d> dischargeHook=(w,p)->{};
    private FirstContactHook firstContactHook=(player,anomaly,award)->{};
    private int firstContactAward;
    private boolean dirty;
    private double saveClock;
    public AnomalyGenerationSettings generationSettings=new AnomalyGenerationSettings();
    public boolean naturalGeneration=true;
    /** Per original 16x16 footprint, not Hytale's larger 32x32 chunk. */
    public int rarity=500;

    public AnomalyService(Path directory) {
        saveFile=directory.resolve("anomalies.json");
        gravityTerrain=new GravityTerrain(directory);
        Path config=directory.resolve("anomaly-generation.json");
        if(Files.exists(config))try(Reader reader=Files.newBufferedReader(config,StandardCharsets.UTF_8)) {
            generationSettings=Objects.requireNonNull(GSON.fromJson(reader,AnomalyGenerationSettings.class),"Empty anomaly generation config");
        }catch(IOException e){throw new UncheckedIOException("Cannot read anomaly generation settings",e);}
        load();
    }
    public com.hypixel.hytale.component.system.tick.EntityTickingSystem<com.hypixel.hytale.server.core.universe.world.storage.EntityStore> gravityInputSystem(){return new GravityField.InputSystem();}
    public com.hypixel.hytale.component.system.tick.EntityTickingSystem<com.hypixel.hytale.server.core.universe.world.storage.EntityStore> gravitySystem(){return new GravityField.MotionSystem();}
    public com.hypixel.hytale.component.system.RefSystem<com.hypixel.hytale.server.core.universe.world.storage.EntityStore> gravityCleanupSystem(){return new GravityField.CleanupSystem();}
    public synchronized boolean terrainReturnProtected(World world,int x,int y,int z){return gravityTerrain.protectedReturn(world,x,y,z);}
    public synchronized void terrainPlayerPlaced(World world,int x,int y,int z){gravityTerrain.placed(world,x,y,z);}
    public synchronized void terrainEnvironmentBreak(World world,int x,int y,int z){gravityTerrain.environmentBreak(world,x,y,z);}
    GravityTerrain terrain(){return gravityTerrain;}
    public synchronized void setGroundingHook(GroundingHook hook) { groundingHook=Objects.requireNonNull(hook); }
    public synchronized void setDischargeHook(BiConsumer<World,Vector3d> hook) { dischargeHook=Objects.requireNonNull(hook); }
    public synchronized void setSuppressionHook(FieldSuppressionHook hook) { suppressionHook=Objects.requireNonNull(hook); }
    /** Source first-contact is an advancement, not research currency: award defaults to zero. */
    public synchronized void setFirstContactHook(FirstContactHook hook) {firstContactHook=Objects.requireNonNull(hook);}
    public synchronized void setFirstContactResearchAward(int amount) {if(amount<0)throw new IllegalArgumentException("Negative research award");firstContactAward=amount;}
    public synchronized Set<AnomalyType> observedTypes(UUID player) {return Set.copyOf(firstContacts.getOrDefault(player,Set.of()));}
    synchronized boolean observeContact(UUID player,AnomalyRecord anomaly) {
        if(!firstContacts.computeIfAbsent(player,id->new HashSet<>()).add(anomaly.type))return false;
        dirty=true;firstContactHook.observed(player,anomaly,firstContactAward);return true;
    }
    public synchronized Collection<AnomalyRecord> all() { return List.copyOf(records.values()); }
    public synchronized Optional<AnomalyRecord> get(UUID id) { return Optional.ofNullable(records.get(id)); }
    public synchronized Optional<AnomalyRecord> nearest(World world,Vector3d p,double radius) { return nearest(world,p,radius,null); }
    public synchronized Optional<AnomalyRecord> nearest(World world,Vector3d p,double radius,AnomalyType type) {
        return records.values().stream().filter(a->a.active() && a.world.equals(world.getName()) && (type==null || a.type==type) && a.distanceSquared(p)<=radius*radius)
            .min(Comparator.comparingDouble(a->a.distanceSquared(p)));
    }
    /** Scanner aim selects a field core, so particle-only anomalies are targetable without a collision entity. */
    public synchronized Optional<AnomalyRecord> inView(World world,Vector3d eye,Vector3d direction,double range) {
        Vector3d ray=new Vector3d(direction).normalize();
        return records.values().stream().filter(a->{
            if(!a.active() || !a.world.equals(world.getName())) return false;
            Vector3d relative=a.position().sub(eye); double along=relative.dot(ray);
            return along>=0 && along<=range && relative.lengthSquared()-along*along<=2.25;
        }).min(Comparator.comparingDouble(a->a.distanceSquared(eye)));
    }
    public synchronized AnomalyRecord spawn(AnomalyType type,World world,Vector3d position,boolean natural) {
        Objects.requireNonNull(type); validate(position);
        worlds.put(world.getName(),world);
        AnomalyRecord a=new AnomalyRecord(UUID.randomUUID(),type,world.getName(),new Vector3d(position),natural);
        records.put(a.id,a);dirty=true; effects.core(world,a);return a;
    }
    public synchronized Optional<CapturedAnomaly> capture(UUID id) {
        AnomalyRecord a=records.get(id);
        if(a==null || !a.active()) return Optional.empty();
        a.contained=true;a.capsuleNonce=UUID.randomUUID();dirty=true;
        World world=worlds.get(a.world);
        if(world!=null) { effects.removeCore(world,a.id);stopGravity(world,a);effects.particle(world,"SM_Anomaly_Capture",a.position()); }
        save();
        return Optional.of(new CapturedAnomaly(a.id,a.type,a.id+":"+a.capsuleNonce));
    }
    /** Single-use bearer token; caller consumes the capsule only when this succeeds. */
    public synchronized Optional<AnomalyRecord> release(String token,World world,Vector3d position) {
        if(token==null) return Optional.empty(); validate(position);
        String[] parts=token.split(":",-1);if(parts.length!=2) return Optional.empty();
        UUID id,nonce;try {id=UUID.fromString(parts[0]);nonce=UUID.fromString(parts[1]);}catch(IllegalArgumentException e){return Optional.empty();}
        AnomalyRecord a=records.get(id);
        if(a==null || !a.contained || !nonce.equals(a.capsuleNonce)) return Optional.empty();
        a.world=world.getName();a.move(position);a.contained=false;a.released=true;a.capsuleNonce=null;a.enabled=true;
        worlds.put(world.getName(),world);dirty=true;effects.core(world,a);effects.particle(world,"SM_Anomaly_Capture",position);save();return Optional.of(a);
    }
    /** Retires exactly one contained identity after its capsule is consumed as a completed crafting ingredient. */
    public synchronized boolean consumeCapsule(String token) {
        if(token==null)return false;
        String[] parts=token.split(":",-1);if(parts.length!=2)return false;
        UUID id,nonce;try{id=UUID.fromString(parts[0]);nonce=UUID.fromString(parts[1]);}catch(IllegalArgumentException e){return false;}
        AnomalyRecord a=records.get(id);
        if(a==null||!a.contained||!nonce.equals(a.capsuleNonce))return false;
        // Contained fields already have no live core; retiring a crafting ingredient needs no foreign-world ECS access.
        records.remove(id);unlink(a);dirty=true;save();return true;
    }
    /** Allows an inventory transaction failure to restore the captured field without changing scan provenance. */
    public synchronized boolean cancelCapture(String token) {
        for(AnomalyRecord a:records.values()) if(a.contained && (a.id+":"+a.capsuleNonce).equals(token)) {
            a.contained=false;a.capsuleNonce=null;dirty=true;save();return true;
        }
        return false;
    }
    public synchronized boolean pair(UUID first,UUID second) {
        var a=records.get(first);var b=records.get(second);
        if(a==null || b==null || a==b || a.type!=AnomalyType.WARP_GATE || b.type!=AnomalyType.WARP_GATE || !a.world.equals(b.world)) return false;
        unlink(a);unlink(b);a.pairedGate=b.id;b.pairedGate=a.id;dirty=true;return true;
    }
    /** Called on the owning world thread when a gun creates or replaces an endpoint. */
    public synchronized void setPortalChannel(UUID id,int channel) {
        if(channel<0||channel>2)throw new IllegalArgumentException("Portal channel must be 0, 1 or 2");
        var a=records.get(id);if(a==null||a.type!=AnomalyType.WARP_GATE||a.portalChannel==channel)return;
        a.portalChannel=channel;a.particles=0;dirty=true;
        var world=worlds.get(a.world);
        if(world!=null){effects.removeCore(world,a.id);if(a.active())effects.core(world,a);}
    }
    private void unlink(AnomalyRecord a) {
        if(a.pairedGate!=null) {var b=records.get(a.pairedGate);if(b!=null && a.id.equals(b.pairedGate)) b.pairedGate=null;}
        a.pairedGate=null;
    }
    public synchronized void setEnabled(UUID id,boolean enabled) {
        var a=records.get(id);if(a==null)return;a.enabled=enabled;dirty=true;
        if(!enabled) {var w=worlds.get(a.world);if(w!=null) {effects.removeCore(w,id);stopGravity(w,a);}}
    }
    public synchronized boolean remove(UUID id) {
        var a=records.remove(id);if(a==null)return false;unlink(a);dirty=true;
        var w=worlds.get(a.world);if(w!=null){effects.removeCore(w,id);stopGravity(w,a);}return true;
    }
    private void stopGravity(World world,AnomalyRecord anomaly){if(anomaly.type==AnomalyType.GRAVITY){effects.removeGravity(world,anomaly.id);gravityTerrain.remove(world,anomaly.id);}}
    public synchronized void tick(World world,double dt) {
        if(!Double.isFinite(dt) || dt<=0)return;dt=Math.min(dt,.25);
        worlds.put(world.getName(),world);effects.cleanup(world);
        effects.tickThoughts(world,dt);
        dirty|=effects.tickAges(world,temporalAges,dt);
        double survey=surveyClocks.getOrDefault(world.getName(),0.0)+dt;
        if(survey>=2) {survey=0;if(naturalGeneration) discover(world);}
        surveyClocks.put(world.getName(),survey);
        var playerPositions=new ArrayList<Vector3d>();var store=world.getEntityStore().getStore();
        for(var player:world.getPlayerRefs()) {var ref=player.getReference();if(ref!=null && ref.isValid()) {var t=store.getComponent(ref,TransformComponent.getComponentType());if(t!=null)playerPositions.add(new Vector3d(t.getPosition()));}}
        var gravityFields=new ArrayList<AnomalyRecord>();
        for(AnomalyRecord a:new ArrayList<>(records.values())) {
            if(!a.world.equals(world.getName()))continue;
            boolean nearby=playerPositions.stream().anyMatch(p->a.distanceSquared(p)<128*128);
            if(!a.active() || !nearby || world.getChunkIfLoaded(ChunkUtil.indexChunkFromBlock((int)Math.floor(a.x),(int)Math.floor(a.z)))==null) {effects.removeCore(world,a.id);continue;}
            effects.core(world,a);a.age+=dt;a.particles-=dt;a.primary-=dt;a.secondary-=dt;a.sound-=dt;
            if(a.particles<=0) {
                effects.particle(world,a.particleId(),a.position());a.particles=1;
                for(var ref:effects.entities(world,a))if(ref.isValid()) {var p=store.getComponent(ref,PlayerRef.getComponentType());if(p!=null)observeContact(p.getUuid(),a);}
            }
            if(a.sound<=0) {effects.sound(world,a);a.sound=a.type==AnomalyType.GRAVITY?6.074535:4;}
            if(suppressionHook.suppressed(world,a.position(),a.type))continue;
            switch(a.type) {
                case GRAVITY -> gravityFields.add(a);
                case TEMPORAL_BLOOM -> {
                    if(a.primary<=0) {effects.crops(world,a,random);a.primary=5;}
                    if(a.secondary<=0) {dirty|=effects.temporalMobs(world,a,temporalAges);a.secondary=10;}
                }
                case ENERGETIC_RIFT -> {
                    if(a.primary<=0) {
                        boolean grounded=groundingHook.grounded(world,a.position(),8)||effects.hasRod(world,a.position(),8);
                        if(!grounded)effects.zap(world,a);
                        if(grounded && a.secondary<=0) {effects.particle(world,"SM_Rift_Discharge",a.position());dischargeHook.accept(world,a.position());a.secondary=10;}
                        a.primary=grounded?.25:2;
                    }
                }
                case THOUGHTWELL -> {
                    if(a.primary<=0) {effects.cognitive(world,a,true,random);a.primary=5;}
                    if(a.secondary<=0) {effects.cognitive(world,a,false,random);a.secondary=10;}
                }
                case ECHOING_SHADOW -> {if(a.primary<=0) {effects.shadow(world,a,random);a.primary=1;dirty=true;}}
                case WARP_GATE -> {if(a.primary<=0) {tickGate(world,a);a.primary=.1;}}
            }
        }
        effects.gravity(world,gravityFields);
        gravityTerrain.tick(world,gravityFields,dt);
        saveClock+=dt;if(saveClock>=30) {saveClock=0;save();}
    }
    private void tickGate(World world,AnomalyRecord a) {
        AnomalyRecord paired=a.pairedGate==null?null:records.get(a.pairedGate);
        if(paired!=null) {
            if(!paired.active() || !paired.world.equals(world.getName())) return;
            long index=ChunkUtil.indexChunkFromBlock((int)Math.floor(paired.x),(int)Math.floor(paired.z));
            if(world.getChunkIfLoaded(index)==null) {
                if(!a.creatingPair && !effects.entities(world,a).isEmpty()) {
                    a.creatingPair=true;world.getChunkAsync(index).whenComplete((chunk,error)->world.execute(()->{synchronized(this){a.creatingPair=false;}}));
                }
            } else effects.teleport(world,a,paired);
            return;
        }
        // Distant pair first, as in the source. Linking is symmetric and preserves identity through capture.
        var existing=records.values().stream().filter(b->b!=a && b.type==AnomalyType.WARP_GATE && b.active() && b.pairedGate==null && b.world.equals(a.world) && a.distanceSquared(b.position())>=500*500)
            .min(Comparator.comparingDouble(b->a.distanceSquared(b.position())));
        if(existing.isPresent()) {pair(a.id,existing.get().id);return;}
        if(a.creatingPair || effects.entities(world,a).isEmpty())return;
        a.creatingPair=true;
        createDistantPair(world,a,0);
    }
    private void createDistantPair(World world,AnomalyRecord a,int attempt) {
        if(attempt>=5) {a.creatingPair=false;a.primary=5;return;}
        Random rng=new Random(a.id.getMostSignificantBits()+attempt*7919L);
        double angle=rng.nextDouble()*Math.PI*2,distance=1000+rng.nextInt(4001);
        int x=(int)Math.floor(a.x+Math.cos(angle)*distance),z=(int)Math.floor(a.z+Math.sin(angle)*distance);
        world.getChunkAsync(ChunkUtil.indexChunkFromBlock(x,z)).whenComplete((chunk,error)->world.execute(()->{
            synchronized(this) {
                if(!a.active() || a.pairedGate!=null) {a.creatingPair=false;return;}
                Vector3d surface=error==null?HytaleAnomalyEffects.safeSurface(world,x,z):null;
                if(surface==null) {createDistantPair(world,a,attempt+1);return;}
                AnomalyRecord partner=spawn(AnomalyType.WARP_GATE,world,surface.add(0,1.5,0),true);
                pair(a.id,partner.id);a.creatingPair=false;save();
            }
        }));
    }
    private void discover(World world) {
        // Native adventure/temporary instances are intentionally excluded; this is the Overworld analogue.
        String worldName=world.getName();
        if(worldName.toLowerCase(Locale.ROOT).contains("instance"))return;
        var seen=surveyed.computeIfAbsent(worldName,k->new HashSet<>());var store=world.getEntityStore().getStore();
        for(var player:world.getPlayerRefs()) {
            var ref=player.getReference();if(ref==null || !ref.isValid())continue;
            var transform=store.getComponent(ref,TransformComponent.getComponentType());if(transform==null)continue;
            int cx=Math.floorDiv((int)Math.floor(transform.getPosition().x),16),cz=Math.floorDiv((int)Math.floor(transform.getPosition().z),16);
            for(int dx=-4;dx<=4;dx++)for(int dz=-4;dz<=4;dz++) {
                int sx=cx+dx,sz=cz+dz;long cell=((long)sx<<32)^(sz&0xffffffffL);
                if(seen.contains(cell) || world.getChunkIfLoaded(ChunkUtil.indexChunkFromBlock(sx*16,sz*16))==null)continue;
                seen.add(cell);dirty=true;
                for(AnomalyType type:AnomalyType.values()) {
                    long seed=world.getWorldConfig().getSeed() ^ mix(cell) ^ mix(type.ordinal()+91871L);
                    Random rng=new Random(seed);
                    int x=sx*16+rng.nextInt(16),z=sz*16+rng.nextInt(16);
                    var chunk=world.getChunkIfLoaded(ChunkUtil.indexChunkFromBlock(x,z));
                    var environment=com.hypixel.hytale.server.core.asset.type.environment.config.Environment.getAssetMap().getAsset(chunk.getBlockChunk().getEnvironment(x,chunk.getHeight(x,z),z));
                    int effectiveRarity=generationSettings.rarity(worldName,environment==null?"":environment.getId(),type,rarity);
                    if(effectiveRarity<=0 || rng.nextInt(effectiveRarity)!=0)continue;
                    Vector3d surface=HytaleAnomalyEffects.safeSurface(world,x,z);
                    if(surface==null || nearest(world,surface,24).isPresent())continue;
                    // Matching density is universal by default; no Minecraft biome was excluded in original config.
                    AnomalyRecord anomaly=spawn(type,world,surface.add(0,type==AnomalyType.WARP_GATE?1.5:1,0),true);
                }
            }
        }
    }
    /** Register this global pre-load hook. It writes raw generation holders before players can ever edit the chunk. */
    public synchronized void onChunkPreLoad(ChunkPreLoadProcessEvent event) {
        if(!naturalGeneration || !event.isNewlyGenerated())return;
        var chunk=event.getChunk();var world=chunk.getWorld();String worldName=world.getName();
        if(worldName.toLowerCase(Locale.ROOT).contains("instance"))return;
        var column=event.getHolder().getComponent(ChunkColumn.getComponentType());
        var holders=column==null?null:column.getSectionHolders();if(holders==null)return;
        var seen=surveyed.computeIfAbsent(worldName,k->new HashSet<>());
        // Four original Minecraft footprints fit inside one native Hytale chunk.
        for(int localX=0;localX<2;localX++)for(int localZ=0;localZ<2;localZ++) {
            int sx=chunk.getX()*2+localX,sz=chunk.getZ()*2+localZ;long cell=((long)sx<<32)^(sz&0xffffffffL);
            if(!seen.add(cell))continue;dirty=true;
            for(AnomalyType type:AnomalyType.values()) {
                long seed=world.getWorldConfig().getSeed()^mix(cell)^mix(type.ordinal()+91871L);Random rng=new Random(seed);
                int x=sx*16+rng.nextInt(16),z=sz*16+rng.nextInt(16),y=chunk.getHeight(x,z)+1;
                if(y<2 || y>315 || chunk.getBlock(x,y,z)!=0 || chunk.getBlock(x,y+1,z)!=0)continue;
                var holder=holders[ChunkUtil.indexSection(y)];
                var fluid=holder==null?null:holder.getComponent(FluidSection.getComponentType());
                if(fluid!=null && fluid.getFluidId(x,y,z)!=0)continue;
                var ground=chunk.getBlockType(x,y-1,z);if(ground==null || ground.getId().contains("Leaves"))continue;
                var environment=com.hypixel.hytale.server.core.asset.type.environment.config.Environment.getAssetMap().getAsset(chunk.getBlockChunk().getEnvironment(x,y,z));
                int effective=generationSettings.rarity(worldName,environment==null?"":environment.getId(),type,rarity);
                if(effective<=0 || rng.nextInt(effective)!=0)continue;
                Vector3d position=new Vector3d(x+.5,y+(type==AnomalyType.WARP_GATE?1.5:1),z+.5);
                if(nearest(world,position,24).isPresent())continue;
                // No live entities or world-store accesses are needed in this asynchronous pre-load event.
                var anomaly=new AnomalyRecord(UUID.randomUUID(),type,worldName,position,true);
                records.put(anomaly.id,anomaly);
                if(generationSettings.terrainPatches)effects.terrainGenerated(chunk,anomaly,rng,generationSettings);
            }
        }
    }
    private static long mix(long v) {v=(v^(v>>>30))*0xbf58476d1ce4e5b9L;v=(v^(v>>>27))*0x94d049bb133111ebL;return v^(v>>>31);}
    private static void validate(Vector3d p) {if(p==null || !Double.isFinite(p.x) || !Double.isFinite(p.y) || !Double.isFinite(p.z) || p.y<0 || p.y>=320)throw new IllegalArgumentException("Anomaly position must be finite and inside build height");}
    public synchronized void stopWorld(World world) {effects.restore(world);gravityTerrain.stopWorld(world);save();worlds.remove(world.getName());}
    public synchronized void save() {
        if(!dirty)return;
        try {
            Files.createDirectories(saveFile.getParent());Path next=saveFile.resolveSibling(saveFile.getFileName()+".tmp");
            try(Writer writer=Files.newBufferedWriter(next,StandardCharsets.UTF_8)) {GSON.toJson(new SaveData(1,new ArrayList<>(records.values()),surveyed,temporalAges,firstContacts),writer);}
            try {Files.move(next,saveFile,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(next,saveFile,StandardCopyOption.REPLACE_EXISTING);}
            dirty=false;
        } catch(IOException e) {throw new UncheckedIOException("Cannot save Strange Matter anomaly identities",e);}
    }
    private void load() {
        if(!Files.exists(saveFile))return;
        try(Reader reader=Files.newBufferedReader(saveFile,StandardCharsets.UTF_8)) {
            SaveData data=GSON.fromJson(reader,SaveData.class);
            if(data==null || data.version!=1 || data.anomalies==null)throw new IOException("Unsupported or invalid anomaly save");
            for(var a:data.anomalies) {if(a.id==null || a.type==null || a.world==null)throw new IOException("Invalid anomaly identity");validate(a.position());if(a.shadowMobs==null)a.shadowMobs=new HashSet<>();if(a.shadowMobPositions==null)a.shadowMobPositions=new HashMap<>();records.put(a.id,a);}
            if(data.surveyed!=null)surveyed.putAll(data.surveyed);
            if(data.temporalAges!=null)temporalAges.putAll(data.temporalAges);
            if(data.firstContacts!=null)firstContacts.putAll(data.firstContacts);
        }catch(IOException e){throw new UncheckedIOException("Cannot load anomaly save; original file preserved",e);}
    }
    private record SaveData(int version,List<AnomalyRecord> anomalies,Map<String,Set<Long>> surveyed,Map<UUID,TemporalAge> temporalAges,Map<UUID,Set<AnomalyType>> firstContacts) {}
}
