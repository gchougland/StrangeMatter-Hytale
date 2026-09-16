package com.hexvane.strangematter.anomaly;

import com.hexvane.strangematter.equipment.NativePlayerFixture;
import com.hexvane.strangematter.util.WorldAccess;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.Position;
import com.hypixel.hytale.protocol.TeleportAck;
import com.hypixel.hytale.protocol.packets.player.ClientMovement;
import com.hypixel.hytale.protocol.packets.player.ClientTeleport;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.player.ChunkTracker;
import com.hypixel.hytale.server.core.modules.entity.teleport.PendingTeleport;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.WorldConfig;
import com.hypixel.hytale.server.core.universe.world.chunk.ChunkFlag;
import com.hypixel.hytale.server.core.universe.world.storage.component.ChunkUnloadingSystem;
import com.hypixel.hytale.server.core.universe.world.events.ChunkPreLoadProcessEvent;
import com.hypixel.hytale.server.core.universe.world.worldgen.provider.FlatWorldGenProvider;
import org.joml.Vector3d;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Real cold terrain, native unloading, service contact and repeated native teleport/ACK cycles. */
public final class NativeNaturalGateVerification {
    private volatile AnomalyService service;
    private volatile com.hexvane.strangematter.worldgen.GenerationCoordinator generation;
    private final Set<Long> generated=ConcurrentHashMap.newKeySet();

    public void onPreLoad(ChunkPreLoadProcessEvent event) {
        var current=service;if(current==null)return;
        var coordinator=generation;if(coordinator!=null)coordinator.column(event);
        if(event.isNewlyGenerated())generated.add(ChunkUtil.indexChunk(event.getChunk().getX(),event.getChunk().getZ()));
    }
    public void onSectionPreLoad(com.hypixel.hytale.server.core.universe.world.events.ChunkSectionPreLoadProcessEvent event){var coordinator=generation;if(coordinator!=null)coordinator.section(event);}
    public CompletableFuture<Void> verifyAsync(Path directory) {
        // Regular fixtures use one-layer y=0 terrain. Native gate landings require
        // supported terrain above the build-floor exclusion. Keep native unloading on.
        var config=new WorldConfig();
        config.setWorldGenProvider(new FlatWorldGenProvider(FlatWorldGenProvider.DEFAULT_TINT,new FlatWorldGenProvider.Layer[]{
            new FlatWorldGenProvider.Layer(0,8,null,"Rock_Stone"),new FlatWorldGenProvider.Layer(8,9,null,"Soil_Grass")
        }));
        config.setSpawningNPC(false);config.setIsSpawnMarkersEnabled(false);config.setBlockTicking(false);config.setCanUnloadChunks(true);
        String name="sm_gate_verification_"+UUID.randomUUID().toString().replace("-","");
        return Universe.get().makeWorld(name,Universe.get().validateWorldPath(name),config)
            .thenCompose(world->WorldAccess.load(world,ChunkUtil.indexChunk(0,0)).thenComposeAsync(ignored->verifyOnWorld(world,directory),world));
    }
    private final class Run {
        final World world;final Path directory;final CompletableFuture<Void> result=new CompletableFuture<>();
        final AtomicLong clock=new AtomicLong();
        NativePlayerFixture player;AnomalyRecord source,target;int probes,trips,parked;boolean pairingObserved;
        final long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(40);
        Run(World world,Path directory){this.world=world;this.directory=directory;}
        void install()throws Exception{
            service=new AnomalyService(directory);
            // Only advance the personal cooldown clock; native chunk futures, entity
            // systems, client movement, teleport packets and ACK processing stay real.
            var effects=AnomalyService.class.getDeclaredField("effects");effects.setAccessible(true);effects.set(service,new HytaleAnomalyEffects(clock::get));
            generation=new com.hexvane.strangematter.worldgen.GenerationCoordinator(service,null);
            service.generationSettings.terrainPatches=false;service.rarity=Integer.MAX_VALUE;
        }
        void begin()throws Exception{
            install();
            source=service.spawnRaised(AnomalyType.WARP_GATE,world,new Vector3d(20.5,10.5,20.5),false);
            player=NativePlayerFixture.create(world,"ColdGateRoundTrips",new Vector3d(20.5,9,20.5));
            player.player().handleClientReady(false);player.store().tick(.05f);
            player.packets().packets.clear();
            require(WarpGateTeleport.eligible(player.store(),player.ref()),"The real joined player is eligible before its first gate contact");
            require(world.getWorldConfig().canUnloadChunks(),"Actual native chunk parking/unloading remains enabled");
            // Exercise command-spawned gate behavior through its regular contact tick;
            // no private createDistantPair call or manufactured completion is involved.
            service.tick(world,.05);
            require(source.pairedGate==null&&source.creatingPair,"A contacting native player starts deferred preparation of a command-spawned gate");
            pairingObserved=true;
        }
        void poll(){
            if(result.isDone())return;
            try{
                probes++;service.tick(world,.05);
                if(target==null&&source.pairedGate!=null){
                    target=service.get(source.pairedGate).orElseThrow();
                    require(target.natural&&target.active()&&source.id.equals(target.pairedGate)&&!source.creatingPair,"Cold generation finishes with a natural, symmetric persistent destination");
                    require(source.position().distance(target.position())>900,"Spawned gate prepares genuinely distant terrain");
                    require(new WarpLandingLoads().ready(world,target.position()),"All destination columns are simultaneously ready when pairing finishes");
                    require(generated.containsAll(WarpLandingLoads.area(target.position())),"Native pre-load hooks run for every generated landing column");
                }
                var teleports=player.packets().ofType(ClientTeleport.class);
                if(teleports.size()>trips){
                    require(teleports.size()==trips+1,"One service crossing queues exactly one native teleport");
                    var packet=teleports.getLast();var destination=(trips&1)==0?target:source;
                    require(destination!=null,"The service establishes its route before teleporting");
                    var p=packet.modelTransform.position;var arrived=new Vector3d(p.x,p.y,p.z);
                    require(arrived.distance(destination.position())<9&&arrived.y==9,"The native teleport arrives at supported terrain beside the correct endpoint");
                    acknowledge(player,packet);trips++;
                    require(player.owner().getTeleportAckTracker().isEmpty()&&player.store().getComponent(player.ref(),PendingTeleport.getComponentType())==null,"The real client ACK fully clears native teleport state on trip "+trips);
                    walkInto(player,destination);
                    int before=player.packets().ofType(ClientTeleport.class).size();
                    service.tick(world,.25);
                    require(player.packets().ofType(ClientTeleport.class).size()==before,"Immediate return contact is rejected during the personal cooldown");
                    clock.addAndGet(5_000_000_000L);
                    if(trips==2)restartAndPark();
                    if(trips==4){
                        require(pairingObserved&&parked>0&&probes>1,"The fixture exercised asynchronous pairing, native parking and responsive world tasks");
                        System.out.println("NATIVE_NATURAL_GATE_VERIFICATION_PASSED: command-spawned contact creates real distant terrain; "+generated.size()+" pre-load columns; "+probes+" responsive probes; four service crossings with native movement and ACKs; natural endpoint return; persisted pair reload; "+parked+" columns parked by native unloading and recovered for repeated travel.");
                        result.complete(null);return;
                    }
                }
                require(System.nanoTime()<deadline,"Native cold gate round trips complete without an indefinite preparing state; trips="+trips+", sourcePreparing="+source.creatingPair+", targetPreparing="+(target!=null&&target.creatingPair));
                CompletableFuture.delayedExecutor(20,TimeUnit.MILLISECONDS).execute(()->{
                    try{world.execute(this::poll);}catch(RuntimeException stopped){result.completeExceptionally(stopped);}
                });
            }catch(Throwable failure){result.completeExceptionally(failure);}
        }
        void restartAndPark()throws Exception{
            var first=source.id;var second=target.id;
            // Release service leases as shutdown does and reload the actual saved
            // identities. The player is back home, over 900 blocks from target.
            service.stopWorld(world);install();source=service.get(first).orElseThrow();target=service.get(second).orElseThrow();
            require(first.equals(target.pairedGate)&&second.equals(source.pairedGate),"The same route survives anomaly persistence reload");
            var tracker=player.store().getComponent(player.ref(),ChunkTracker.getComponentType());tracker.clear();
            player.store().tick(.05f);
            for(long index:WarpLandingLoads.area(target.position())){
                var chunk=WorldAccess.inMemory(world,index);require(chunk!=null,"Prepared destination remains resident before native parking");
                require(!chunk.shouldKeepLoaded(),"World stop releases every temporary destination residency lease");
                // Move only the native idle clock forward, then let the stock unloading
                // system perform its own COLD -> NonTicking lifecycle transition.
                chunk.pollActiveTimer(Integer.MAX_VALUE);
            }
            new ChunkUnloadingSystem().tick(.5f,0,world.getChunkStore().getStore());
            for(long index:WarpLandingLoads.area(target.position())){
                var chunk=WorldAccess.inMemory(world,index);if(chunk!=null&&!chunk.is(ChunkFlag.TICKING))parked++;
            }
            require(parked>0&&!new WarpLandingLoads().ready(world,target.position()),"The real native unload system parks the distant landing area before its next trip");
            walkInto(player,source);
        }
        void close(){
            if(player!=null)player.close();
            if(service!=null){service.stopWorld(world);service=null;}
            generation=null;
        }
    }
    private CompletableFuture<Void> verifyOnWorld(World world,Path directory) {
        var run=new Run(world,directory.resolve("natural-gate-regression"));
        try{run.begin();world.execute(run::poll);}catch(Throwable failure){run.result.completeExceptionally(failure);}
        return run.result.whenCompleteAsync((ignored,error)->run.close(),world);
    }
    private static void acknowledge(NativePlayerFixture player,ClientTeleport teleport){
        var movement=new ClientMovement();movement.teleportAck=new TeleportAck(teleport.teleportId);
        var p=teleport.modelTransform.position;movement.absolutePosition=new Position((float)p.x,(float)p.y,(float)p.z);receive(player,movement);
    }
    private static void walkInto(NativePlayerFixture player,AnomalyRecord gate){
        var movement=new ClientMovement();movement.absolutePosition=new Position((float)gate.x,9,(float)gate.z);receive(player,movement);
        player.store().tick(.05f);
        var body=player.store().getComponent(player.ref(),TransformComponent.getComponentType());
        require(body.getPosition().distance(new Vector3d(gate.x,9,gate.z))<.001,"Native client movement enters the gate at ground level");
    }
    private static void receive(NativePlayerFixture player,ClientMovement movement){
        var bytes=MemorySegment.ofArray(new byte[movement.computeSize()]);movement.serialize(bytes,0);
        player.packets().handle(ClientMovement.toObject(bytes));player.world().consumeTaskQueue();
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
