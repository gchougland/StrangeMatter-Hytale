package com.hexvane.strangematter.anomaly;

import com.hexvane.strangematter.util.WorldAccess;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.events.ChunkPreLoadProcessEvent;
import com.hexvane.strangematter.worldgen.GenerationColumn;
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
    /** Raise a newly ground-placed field once; exact-position spawn remains available for warp bolts. */
    public synchronized AnomalyRecord spawnRaised(AnomalyType type,World world,Vector3d previousCenter,boolean natural) {
        Objects.requireNonNull(type);var a=raisedRecord(type,world.getName(),previousCenter,natural);
        worlds.put(world.getName(),world);records.put(a.id,a);dirty=true;effects.core(world,a);return a;
    }
    private static AnomalyRecord raisedRecord(AnomalyType type,String world,Vector3d previousCenter,boolean natural){
        var position=raisedPosition(previousCenter);
        var a=new AnomalyRecord(UUID.randomUUID(),type,world,position,natural);a.placementLift=position.y-previousCenter.y;return a;
    }
    private static Vector3d raisedPosition(Vector3d previousCenter){
        validate(previousCenter);
        // A capsule at the build ceiling must remain recoverable instead of throwing after impact.
        return new Vector3d(previousCenter.x,Math.min(Math.nextDown((double)ChunkUtil.HEIGHT),previousCenter.y+1),previousCenter.z);
    }
    public synchronized Optional<CapturedAnomaly> capture(UUID id) {
        AnomalyRecord a=records.get(id);
        if(!capturable(a)) return Optional.empty();
        a.contained=true;a.capsuleNonce=UUID.randomUUID();dirty=true;
        World world=worlds.get(a.world);
        if(world!=null) { effects.removeCore(world,a.id);stopGravity(world,a);effects.particle(world,"SM_Anomaly_Capture",a.position()); }
        save();
        return Optional.of(new CapturedAnomaly(a.id,a.type,a.id+":"+a.capsuleNonce));
    }
    /** Gun-created endpoints remain owned by their gun; relocated ordinary gates keep channel zero. */
    public synchronized boolean canCapture(UUID id) { return capturable(records.get(id)); }
    private static boolean capturable(AnomalyRecord a) {
        return a!=null && a.active() && (a.type!=AnomalyType.WARP_GATE || a.portalChannel==0);
    }
    /** Single-use bearer token; caller consumes the capsule only when this succeeds. */
    public synchronized Optional<AnomalyRecord> release(String token,World world,Vector3d position) {
        return releaseAt(token,world,position,0);
    }
    /** Capsule impact places its existing identity one block above the former landing center. */
    public synchronized Optional<AnomalyRecord> releaseRaised(String token,World world,Vector3d previousCenter) {
        if(token==null)return Optional.empty();var position=raisedPosition(previousCenter);
        return releaseAt(token,world,position,position.y-previousCenter.y);
    }
    private Optional<AnomalyRecord> releaseAt(String token,World world,Vector3d position,double placementLift) {
        if(token==null) return Optional.empty(); validate(position);
        String[] parts=token.split(":",-1);if(parts.length!=2) return Optional.empty();
        UUID id,nonce;try {id=UUID.fromString(parts[0]);nonce=UUID.fromString(parts[1]);}catch(IllegalArgumentException e){return Optional.empty();}
        AnomalyRecord a=records.get(id);
        if(a==null || !a.contained || !nonce.equals(a.capsuleNonce)) return Optional.empty();
        a.world=world.getName();a.move(position);a.placementLift=placementLift;a.contained=false;a.released=true;a.capsuleNonce=null;a.enabled=true;
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
        records.remove(id);suppressedSources.remove(id);unlink(a);dirty=true;save();return true;
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
        if(!compatibleGates(a,b)) return false;
        unlink(a);unlink(b);a.pairedGate=b.id;b.pairedGate=a.id;dirty=true;return true;
    }
    /** Gun endpoints only link explicitly to the opposite gun channel; ordinary anomalies link to each other. */
    private static boolean compatibleGates(AnomalyRecord a,AnomalyRecord b) {
        if(a==null || b==null || a==b || a.type!=AnomalyType.WARP_GATE || b.type!=AnomalyType.WARP_GATE || !a.world.equals(b.world))return false;
        return a.portalChannel==0 ? b.portalChannel==0
            : (a.portalChannel==1 && b.portalChannel==2) || (a.portalChannel==2 && b.portalChannel==1);
    }
    /** Called on the owning world thread when a gun creates or replaces an endpoint. */
    public synchronized void setPortalChannel(UUID id,int channel) {
        if(channel<0||channel>2)throw new IllegalArgumentException("Portal channel must be 0, 1 or 2");
        var a=records.get(id);if(a==null||a.type!=AnomalyType.WARP_GATE||a.portalChannel==channel)return;
        a.portalChannel=channel;a.particles=0;dirty=true;
        var world=worlds.get(a.world);
        if(a.pairedGate!=null && !compatibleGates(a,records.get(a.pairedGate)))unlink(a);
        a.creatingPair=false;
        if(world!=null){gateLoads.cancel(world,a.id);effects.removeCore(world,a.id);if(a.active())effects.core(world,a);}
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
        var a=records.remove(id);if(a==null)return false;suppressedSources.remove(id);unlink(a);dirty=true;
        var w=worlds.get(a.world);if(w!=null){effects.removeCore(w,id);stopGravity(w,a);}return true;
    }
    private void stopGravity(World world,AnomalyRecord anomaly){if(anomaly.type==AnomalyType.GRAVITY){effects.removeGravity(world,anomaly.id);gravityTerrain.remove(world,anomaly.id);}}
    public synchronized void tick(World world,double dt) {
        if(!Double.isFinite(dt) || dt<=0)return;dt=Math.min(dt,.25);
        worlds.put(world.getName(),world);effects.cleanup(world,dt);
        dirty|=effects.tickAges(world,temporalAges,dt);
        double survey=surveyClocks.getOrDefault(world.getName(),0.0)+dt;
        if(survey>=2) {survey=0;if(naturalGeneration) discover(world);}
        surveyClocks.put(world.getName(),survey);
        var playerPositions=new ArrayList<Vector3d>();var store=world.getEntityStore().getStore();
        for(var player:world.getPlayerRefs()) {var ref=player.getReference();if(ref!=null && ref.isValid()) {var t=store.getComponent(ref,TransformComponent.getComponentType());if(t!=null)playerPositions.add(new Vector3d(t.getPosition()));}}
        var gravityFields=new ArrayList<AnomalyRecord>();
        for(AnomalyRecord a:new ArrayList<>(records.values())) {
            if(!a.world.equals(world.getName()))continue;
            boolean suppressed=a.active()&&suppressed(world,a);
            if(suppressed){
                dirty|=effects.suppress(world,a);
                if(suppressedSources.add(a.id)){
                    gateLoads.cancel(world,a.id);a.creatingPair=false;stopGravity(world,a);
                }
            }else if(suppressedSources.remove(a.id)){
                // Removing the final overlapping nullifier resumes normal field schedules promptly.
                a.primary=0;a.secondary=0;
            }
            boolean nearby=playerPositions.stream().anyMatch(p->a.distanceSquared(p)<128*128);
            if(!a.active() || !nearby || WorldAccess.loaded(world,ChunkUtil.indexChunkFromBlock((int)Math.floor(a.x),(int)Math.floor(a.z)))==null) {effects.removeCore(world,a.id);continue;}
            effects.core(world,a);a.age+=dt;a.particles-=dt;a.primary-=dt;a.secondary-=dt;a.sound-=dt;
            if(a.particles<=0) {
                effects.particle(world,a.particleId(),a.position());a.particles=1;
                for(var ref:effects.entities(world,a))if(ref.isValid()) {var p=store.getComponent(ref,PlayerRef.getComponentType());if(p!=null)observeContact(p.getUuid(),a);}
            }
            if(a.sound<=0) {effects.sound(world,a);a.sound=a.type==AnomalyType.GRAVITY?6.074535:4;}
            if(suppressed)continue;
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
        // Reconcile suppressed cognitive sources before they can emit another private cue.
        effects.tickThoughts(world,dt);
        effects.gravity(world,gravityFields);
        gravityTerrain.tick(world,gravityFields,dt);
        saveClock+=dt;if(saveClock>=30) {saveClock=0;save();}
    }
    private final WarpLandingLoads gateLoads=new WarpLandingLoads();
    private final Set<UUID> suppressedSources=new HashSet<>();
    private boolean suppressed(World world,AnomalyRecord source){return suppressionHook.suppressed(world,source.position(),source.type);}
    private void tickGate(World world,AnomalyRecord a) {
        if(suppressed(world,a))return;
        var candidate=a.pairedGate==null?null:records.get(a.pairedGate);
        if(a.pairedGate!=null && (!compatibleGates(a,candidate) || !a.id.equals(candidate.pairedGate))) {
            unlink(a);dirty=true;a.creatingPair=false;gateLoads.cancel(world,a.id);
        }
        final AnomalyRecord paired=a.pairedGate==null?null:candidate;
        if(paired!=null) {
            if(!paired.active() || !paired.world.equals(world.getName()) || suppressed(world,paired)) return;
            if(a.creatingPair || effects.entities(world,a).stream().noneMatch(ref->WarpGateTeleport.eligible(world.getEntityStore().getStore(),ref)))return;
            if(gateLoads.ready(world,paired.position()))effects.teleport(world,a,paired);
            else {
                var sourcePosition=a.position();var targetPosition=paired.position();
                a.creatingPair=gateLoads.request(world,a.id,targetPosition,success->{synchronized(this){
                    a.creatingPair=false;
                    if(!gateRequestCurrent(world,a,sourcePosition,paired,targetPosition))return;
                    // Never teleport from a completion: the next tick rechecks bodies, pairing and chunks.
                    a.primary=success?0:5;
                }});
            }
            return;
        }
        // A single Warp Gun endpoint waits for the other shot from its own gun.
        if(a.portalChannel!=0)return;
        // Distant pair first, as in the source. Linking is symmetric and preserves identity through capture.
        var existing=records.values().stream().filter(b->b!=a && b.type==AnomalyType.WARP_GATE && b.portalChannel==0 && b.active() && b.pairedGate==null && b.world.equals(a.world) && !suppressed(world,b) && a.distanceSquared(b.position())>=500*500)
            .min(Comparator.comparingDouble(b->a.distanceSquared(b.position())));
        if(existing.isPresent()) {pair(a.id,existing.get().id);return;}
        if(a.creatingPair || effects.entities(world,a).isEmpty())return;
        a.creatingPair=true;
        createDistantPair(world,a,0);
    }
    private void createDistantPair(World world,AnomalyRecord a,int attempt) {
        if(a.portalChannel!=0 || suppressed(world,a)){a.creatingPair=false;return;}
        if(attempt>=5) {a.creatingPair=false;a.primary=5;return;}
        Random rng=new Random(a.id.getMostSignificantBits()+attempt*7919L);
        double angle=rng.nextDouble()*Math.PI*2,distance=1000+rng.nextInt(4001);
        int x=(int)Math.floor(a.x+Math.cos(angle)*distance),z=(int)Math.floor(a.z+Math.sin(angle)*distance);
        var sourcePosition=a.position();var targetPosition=new Vector3d(x+.5,0,z+.5);
        boolean started=gateLoads.request(world,a.id,targetPosition,success->{
            synchronized(this) {
                if(!gateRequestCurrent(world,a,sourcePosition,null,null)) {a.creatingPair=false;return;}
                if(!success){a.creatingPair=false;a.primary=5;return;}
                Vector3d surface=gateLoads.ready(world,targetPosition)?HytaleAnomalyEffects.safeSurface(world,x,z):null;
                if(surface==null) {createDistantPair(world,a,attempt+1);return;}
                if(suppressionHook.suppressed(world,new Vector3d(surface).add(0,2.5,0),AnomalyType.WARP_GATE)){a.creatingPair=false;a.primary=5;return;}
                AnomalyRecord partner=spawnRaised(AnomalyType.WARP_GATE,world,surface.add(0,1.5,0),true);
                pair(a.id,partner.id);a.creatingPair=false;save();
            }
        });
        if(!started){a.creatingPair=false;a.primary=5;}
    }
    private boolean gateRequestCurrent(World world,AnomalyRecord source,Vector3d position,AnomalyRecord target,Vector3d targetPosition){
        if(records.get(source.id)!=source || !source.active() || suppressed(world,source) || worlds.get(source.world)!=world
                || !source.world.equals(world.getName()) || !source.position().equals(position))return false;
        if(target==null)return source.portalChannel==0 && source.pairedGate==null;
        return records.get(target.id)==target && compatibleGates(source,target) && target.active() && !suppressed(world,target) && target.world.equals(source.world)
                && target.id.equals(source.pairedGate) && source.id.equals(target.pairedGate) && target.position().equals(targetPosition);
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
                if(seen.contains(cell) || WorldAccess.loaded(world,ChunkUtil.indexChunkFromBlock(sx*16,sz*16))==null)continue;
                seen.add(cell);dirty=true;
                for(AnomalyType type:AnomalyType.values()) {
                    long seed=world.getWorldConfig().getSeed() ^ mix(cell) ^ mix(type.ordinal()+91871L);
                    Random rng=new Random(seed);
                    int x=sx*16+rng.nextInt(16),z=sz*16+rng.nextInt(16);
                    var chunk=WorldAccess.loaded(world,ChunkUtil.indexChunkFromBlock(x,z));
                    var environment=com.hypixel.hytale.server.core.asset.type.environment.config.Environment.getAssetMap().getAsset(WorldAccess.column(chunk).getEnvironment(x,chunk.getHeight(x,z),z));
                    int effectiveRarity=generationSettings.rarity(worldName,environment==null?"":environment.getId(),type,rarity);
                    if(effectiveRarity<=0 || rng.nextInt(effectiveRarity)!=0)continue;
                    Vector3d surface=HytaleAnomalyEffects.safeSurface(world,x,z);
                    if(surface==null || nearest(world,surface,24).isPresent())continue;
                    // Matching density is universal by default; no Minecraft biome was excluded in original config.
                    AnomalyRecord anomaly=spawnRaised(type,world,surface.add(0,type==AnomalyType.WARP_GATE?1.5:1,0),true);
                }
            }
        }
    }
    /** Fresh section holders only, before publication and player edits. */
    public synchronized void generate(GenerationColumn terrain) {
        if(!naturalGeneration)return;
        var chunk=terrain.chunk;var world=chunk.getWorld();String worldName=world.getName();
        if(worldName.toLowerCase(Locale.ROOT).contains("instance"))return;
        var seen=surveyed.computeIfAbsent(worldName,k->new HashSet<>());
        // Four original Minecraft footprints fit inside one native Hytale chunk.
        for(int localX=0;localX<2;localX++)for(int localZ=0;localZ<2;localZ++) {
            int sx=chunk.getX()*2+localX,sz=chunk.getZ()*2+localZ;long cell=((long)sx<<32)^(sz&0xffffffffL);
            if(!seen.add(cell))continue;dirty=true;
            for(AnomalyType type:AnomalyType.values()) {
                long seed=world.getWorldConfig().getSeed()^mix(cell)^mix(type.ordinal()+91871L);Random rng=new Random(seed);
                int x=sx*16+rng.nextInt(16),z=sz*16+rng.nextInt(16),y=terrain.height(x,z)+1;
                if(y<2 || y>315 || terrain.block(x,y,z)!=0 || terrain.block(x,y+1,z)!=0)continue;
                if(terrain.fluid(x,y,z)!=0||terrain.fluid(x,y+1,z)!=0)continue;
                var ground=terrain.type(x,y-1,z);if(ground==null || ground.getId().contains("Leaves"))continue;
                var environment=com.hypixel.hytale.server.core.asset.type.environment.config.Environment.getAssetMap().getAsset(terrain.environment(x,y,z));
                int effective=generationSettings.rarity(worldName,environment==null?"":environment.getId(),type,rarity);
                if(effective<=0 || rng.nextInt(effective)!=0)continue;
                Vector3d position=new Vector3d(x+.5,y+(type==AnomalyType.WARP_GATE?1.5:1),z+.5);
                if(nearest(world,position,24).isPresent())continue;
                // No live entities or world-store accesses are needed in this asynchronous pre-load event.
                var anomaly=raisedRecord(type,worldName,position,true);
                records.put(anomaly.id,anomaly);
                if(generationSettings.terrainPatches)effects.terrainGenerated(terrain,anomaly,rng,generationSettings);
            }
        }
    }
    private static long mix(long v) {v=(v^(v>>>30))*0xbf58476d1ce4e5b9L;v=(v^(v>>>27))*0x94d049bb133111ebL;return v^(v>>>31);}
    private static void validate(Vector3d p) {if(p==null || !Double.isFinite(p.x) || !Double.isFinite(p.y) || !Double.isFinite(p.z) || p.y<0 || p.y>=320)throw new IllegalArgumentException("Anomaly position must be finite and inside build height");}
    public synchronized void stopWorld(World world) {gateLoads.clear(world);for(var a:records.values())if(a.world.equals(world.getName())){a.creatingPair=false;suppressedSources.remove(a.id);}effects.restore(world);gravityTerrain.stopWorld(world);save();worlds.remove(world.getName());}
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
            for(var a:data.anomalies) {if(a.id==null || a.type==null || a.world==null||!Double.isFinite(a.placementLift)||a.placementLift<0||a.placementLift>1)throw new IOException("Invalid anomaly identity");validate(a.position());if(a.shadowMobs==null)a.shadowMobs=new HashSet<>();if(a.shadowMobPositions==null)a.shadowMobPositions=new HashMap<>();records.put(a.id,a);}
            if(data.surveyed!=null)surveyed.putAll(data.surveyed);
            if(data.temporalAges!=null)temporalAges.putAll(data.temporalAges);
            if(data.firstContacts!=null)firstContacts.putAll(data.firstContacts);
        }catch(IOException e){throw new UncheckedIOException("Cannot load anomaly save; original file preserved",e);}
        // Earlier versions let automatic pairing claim gun endpoints. Repair only invalid links;
        // unlink preserves an unrelated valid pair when an old record has a one-way reference.
        for(var a:records.values())if(a.pairedGate!=null) {
            var b=records.get(a.pairedGate);
            if(!compatibleGates(a,b) || !a.id.equals(b.pairedGate)){unlink(a);dirty=true;}
        }
        save();
    }
    private record SaveData(int version,List<AnomalyRecord> anomalies,Map<String,Set<Long>> surveyed,Map<UUID,TemporalAge> temporalAges,Map<UUID,Set<AnomalyType>> firstContacts) {}
}
