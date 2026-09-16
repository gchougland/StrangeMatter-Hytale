package com.hexvane.strangematter.automation;

import com.hexvane.strangematter.effects.GadgetEffects;
import com.hexvane.strangematter.util.WorldAccess;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.*;
import org.joml.Vector3d;
import org.joml.Vector3i;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static com.hexvane.strangematter.automation.TubeEndpoints.*;
import static com.hypixel.hytale.server.core.universe.world.SetBlockSettings.*;

/** Loaded-only transport. Flights reserve existing source slots; only the terminal native move owns items. */
public final class TubeService implements AutoCloseable {
    public static final String ID="SM_Gravitic_Tube";
    static final int MAX_NETWORK=128,MAX_ROUTE=64,MAX_FLIGHTS=32,MAX_WORLD_FLIGHTS=128;
    static final double SPEED=3; // Blocks per second, shared by travel time and carrier animation.
    record Node(Position position,Ref<ChunkStore> ref,TubeComponent component){}
    record Target(Node node,int face,Endpoint endpoint,TubeConfiguration config,List<Position> path){}
    record Edge(Position a,Position b){
        static Edge of(Position a,Position b){return key(a).compareTo(key(b))<=0?new Edge(a,b):new Edge(b,a);}
    }
    static final class Flight {
        final UUID id=UUID.randomUUID(),owner;final Address source,destination;final Position fromTube,toTube;final int fromFace,toFace,quantity;
        final long fromRevision,toRevision;final short slot;final String original;final List<Position> route;final List<Vector3d> points;
        final double launched,duration;Ref<EntityStore> carrier;
        Flight(UUID owner,Node from,int face,Endpoint source,Target target,short slot,int amount,String original,double now){
            this.owner=owner;this.source=source.address();destination=target.endpoint.address();fromTube=from.position;toTube=target.node.position;
            fromFace=face;toFace=target.face;fromRevision=from.component.revision();toRevision=target.node.component.revision();this.slot=slot;quantity=amount;this.original=original;route=List.copyOf(target.path);launched=now;
            var points=new ArrayList<Vector3d>();points.add(center(source.position()));for(var p:route)points.add(center(p));points.add(center(target.endpoint.position()));this.points=List.copyOf(points);
            double distance=0;for(int i=1;i<points.size();i++)distance+=points.get(i).distance(points.get(i-1));duration=Math.max(.25,distance/SPEED);
        }
    }
    static final class WorldState {
        final TubeConnectionIndicators indicators=new TubeConnectionIndicators();
        final Map<Position,Node> nodes=new LinkedHashMap<>();final List<Flight> flights=new ArrayList<>();
        final Map<String,Double> nextExtract=new HashMap<>();final Map<Edge,Double> capacity=new HashMap<>();
        final Map<Position,Map<Position,List<Position>>> routes=new HashMap<>();final Map<Position,String> status=new HashMap<>();
        double clock,discover,dispatch,particles;long tie;int shapeCursor,scanCursor,work;
    }
    private final Map<String,WorldState> worlds=new ConcurrentHashMap<>();
    private final TubeEndpoints endpoints;private final TubeTransferLedger ledger;
    public TubeService(Path directory,TubeInventoryProvider factoryProvider){endpoints=new TubeEndpoints(factoryProvider);ledger=new TubeTransferLedger(directory,endpoints);}
    TubeService(Path directory,TubeInventoryProvider factoryProvider,java.util.function.Function<Endpoint,java.util.concurrent.CompletableFuture<Void>> checkpoint){endpoints=new TubeEndpoints(factoryProvider);ledger=new TubeTransferLedger(directory,endpoints,checkpoint);}
    public static void register(IComponentRegistry<ChunkStore> chunks,IComponentRegistry<EntityStore> entities){
        if(TubeComponent.type==null)TubeComponent.type=chunks.registerComponent(TubeComponent.class,"SM_GraviticTube",TubeComponent.CODEC);
        if(TubeEndpointReceipts.type==null)TubeEndpointReceipts.type=chunks.registerComponent(TubeEndpointReceipts.class,"SM_TubeEndpointReceipts",TubeEndpointReceipts.CODEC);
    }
    public void registerSystems(IComponentRegistry<ChunkStore> chunks,IComponentRegistry<EntityStore> entities){TubeEvents.register(this,chunks,entities);}
    public void setInventoryProvider(TubeInventoryProvider provider){endpoints.provider(provider);}
    private WorldState state(World world){return worlds.computeIfAbsent(world.getName(),k->new WorldState());}
    static String key(Position p){return p.x()+":"+p.y()+":"+p.z();}
    static Vector3d center(Position p){return new Vector3d(p.x()+.5,p.y()+.5,p.z()+.5);}
    static boolean tube(World world,Position p){var chunk=WorldAccess.loaded(world,ChunkUtil.indexChunkFromBlock(p.x(),p.z()));if(chunk==null)return false;
        var type=WorldAccess.blockType(chunk,p.x(),p.y(),p.z());return type!=null&&(ID.equals(type.getId())||ID.equals(type.getDefaultStateKey()));}
    private Node node(World world,Position p){
        if(!tube(world,p))return null;var ref=TubeEndpoints.block(world,p);if(ref==null||!ref.isValid())return null;
        var component=ref.getStore().getComponent(ref,TubeComponent.type);return component==null?null:new Node(p,ref,component);
    }
    public void placed(World world,Vector3i position,UUID owner){
        var p=new Position(position);var n=node(world,p);if(n==null)return;
        if(n.component.owner()==null&&owner!=null){n.component.owner(owner);dirty(n);}
        var ws=state(world);ws.nodes.put(p,n);ws.routes.clear();ws.indicators.invalidate();refresh(world,ws,p);
    }
    public void removed(World world,Vector3i position){
        var ws=state(world);var p=new Position(position);ws.nodes.remove(p);ws.routes.clear();ws.indicators.invalidate();
        for(var f:List.copyOf(ws.flights))if(f.route.contains(p)||f.source.position().equals(p)||f.destination.position().equals(p))cancel(ws,f);
        refresh(world,ws,p);
    }
    private static void dirty(Node node){var info=node.ref.getStore().getComponent(node.ref,BlockModule.BlockStateInfo.getComponentType());if(info!=null)info.markNeedsSaving();}
    private void discover(World world,WorldState ws){
        var found=new LinkedHashMap<Position,Node>();
        world.getChunkStore().getStore().forEachChunk(TubeComponent.type,(chunk,commands)->{
            for(int i=0;i<chunk.size()&&found.size()<8192;i++){
                var info=chunk.getComponent(i,BlockModule.BlockStateInfo.getComponentType());var p=new Vector3i();
                if(info!=null&&info.fillWorldPos(p)&&tube(world,new Position(p)))found.put(new Position(p),new Node(new Position(p),chunk.getReferenceTo(i),chunk.getComponent(i,TubeComponent.type)));
            }
        });
        if(!found.keySet().equals(ws.nodes.keySet()))ws.routes.clear();ws.nodes.clear();ws.nodes.putAll(found);
        var shapes=new ArrayList<>(found.keySet());if(!shapes.isEmpty()){
            int budget=Math.min(512,shapes.size());for(int i=0;i<budget;i++)shape(world,ws,shapes.get((ws.shapeCursor+i)%shapes.size()));ws.shapeCursor=(ws.shapeCursor+budget)%shapes.size();
        }
        ws.capacity.entrySet().removeIf(e->e.getValue()<ws.clock-2);ws.nextExtract.entrySet().removeIf(e->e.getValue()<ws.clock-30);
    }
    private void refresh(World world,WorldState ws,Position p){shape(world,ws,p);for(int f=0;f<6;f++)shape(world,ws,p.offset(f));}
    private void shape(World world,WorldState ws,Position p){
        if(!tube(world,p))return;int mask=0;var node=node(world,p);boolean defaultsChanged=false;
        for(int f=0;f<6;f++){
            if(tube(world,p.offset(f))){mask|=1<<f;continue;}
            var ports=endpoints.all(world,p.offset(f),true);if(ports.isEmpty())continue;mask|=1<<f;
            if(node!=null){
                String section=ports.stream().anyMatch(e->e.port().section().equals("input"))?"input":ports.stream().anyMatch(e->e.port().section().equals("storage"))?"storage":null;
                if(section!=null)defaultsChanged|=node.component.defaultInsert(f,section);
            }
        }
        if(defaultsChanged){dirty(node);ws.routes.clear();ws.indicators.invalidate();}
        var chunk=WorldAccess.loaded(world,ChunkUtil.indexChunkFromBlock(p.x(),p.z()));if(chunk==null)return;
        var type=WorldAccess.blockType(chunk,p.x(),p.y(),p.z());var state=String.format(Locale.ROOT,"Connection%02d",mask);
        if(state.equals(type.getCurrentInteractionState())&&WorldAccess.rotation(chunk,p.x(),p.y(),p.z())==0)return;
        var variant=type.getBlockForState(state);if(variant!=null)WorldAccess.set(chunk,p.x(),p.y(),p.z(),BlockType.getAssetMap().getIndex(variant.getId()),variant,0,0,NO_UPDATE_STATE|NO_SEND_PARTICLES|NO_UPDATE_NEIGHBOR_CONNECTIONS|FORCE_CHANGED);
    }
    public void tick(World world,double dt){
        var ws=state(world);ws.clock+=Math.max(0,Math.min(dt,.25));
        // Recovery always runs before dispatch and factories use isRecoveryBlocked for their own ticks.
        ledger.tick(world);
        if((ws.discover-=dt)<=0){ws.discover=1;discover(world,ws);}
        ws.indicators.tick(world,dt,ws.clock,ws.nodes.values(),p->!endpoints.all(world,p,false).isEmpty());
        for(var flight:List.copyOf(ws.flights)){
            if(!valid(world,ws,flight)){cancel(ws,flight);continue;}
            double age=ws.clock-flight.launched;
            if(age>=flight.duration){
                var source=endpoints.resolve(world,flight.source);var destination=endpoints.resolve(world,flight.destination);
                if(source!=null&&destination!=null&&ledger.begin(source,destination,flight.slot,flight.quantity,()->worlds.get(world.getName())==ws&&validStructure(world,flight)))ws.status.put(flight.fromTube,"Saving "+flight.quantity+" item transfer");
                cancel(ws,flight);continue;
            }
            var position=position(flight,age*SPEED);
            boolean visible=world.getPlayerRefs().stream().anyMatch(p->p.getTransform().getPosition().distanceSquared(position)<48*48);
            if(visible){if(flight.carrier==null||!flight.carrier.isValid())flight.carrier=TubeCarrier.spawn(world,TubeStacks.decode(flight.original),position);
                else TubeCarrier.move(flight.carrier,position);}
            else {TubeCarrier.remove(flight.carrier);flight.carrier=null;}
        }
        if((ws.dispatch-=dt)<=0){ws.dispatch=.2;int budget=32;ws.work=4096;var nodes=new ArrayList<>(ws.nodes.values());
            int faces=nodes.size()*6;
            for(int checked=0;checked<Math.min(256,faces)&&budget>0&&ws.work>0;checked++){
                int next=Math.floorMod(ws.scanCursor++,faces);var n=nodes.get(next/6);int face=next%6;
                if(n.component.face(face).mode==TubeConfiguration.Mode.EXTRACT){dispatch(world,ws,n,face);budget--;}
            }
        }
        if((ws.particles-=dt)<=0){ws.particles=.5;for(var flight:ws.flights)if(flight.carrier!=null&&flight.carrier.isValid()){
            var p=position(flight,(ws.clock-flight.launched)*SPEED);GadgetEffects.particle(world,"SM_Tube_Field",p);GadgetEffects.particle(world,"SM_Tube_Transfer",p);
        }}
    }
    static Vector3d position(Flight f,double distance){for(int i=1;i<f.points.size();i++){var a=f.points.get(i-1);var b=f.points.get(i);double length=a.distance(b);if(distance<=length)return new Vector3d(a).lerp(b,length==0?1:Math.max(0,distance/length));distance-=length;}return new Vector3d(f.points.getLast());}
    private boolean valid(World world,WorldState ws,Flight f){
        var from=node(world,f.fromTube);var to=node(world,f.toTube);if(from==null||to==null||from.component.revision()!=f.fromRevision||to.component.revision()!=f.toRevision)return false;
        if(f.route.stream().anyMatch(p->!tube(world,p)))return false;
        var source=endpoints.resolve(world,f.source);var target=endpoints.resolve(world,f.destination);
        if(source==null||target==null||ledger.blocked(source)||ledger.blocked(target)||!endpoints.mayAccess(source,f.owner)||!endpoints.mayAccess(target,f.owner))return false;
        var stack=source.port().inventory().getItemStack(f.slot);if(!Objects.equals(f.original,TubeStacks.encode(stack)))return false;
        var extracting=from.component.face(f.fromFace);var inserting=to.component.face(f.toFace);
        if(extracting.mode!=TubeConfiguration.Mode.EXTRACT||inserting.mode!=TubeConfiguration.Mode.INSERT||!source.port().extractable()||!source.port().acceptsExtract().test(stack)||!extracting.accepts(stack)||!inserting.accepts(stack)||!target.port().acceptsInsert().test(stack))return false;
        if(extracting.count(source.port().inventory())-reservedSource(ws,source,null,extracting)<extracting.leaveBehind)return false;
        return inserting.fillUpTo==0||inserting.count(target.port().inventory())+reservedTarget(ws,target,null,inserting)<=inserting.fillUpTo;
    }
    private boolean validStructure(World world,Flight f){
        var from=node(world,f.fromTube);var to=node(world,f.toTube);
        if(from==null||to==null||from.component.revision()!=f.fromRevision||to.component.revision()!=f.toRevision||f.route.stream().anyMatch(p->!tube(world,p)))return false;
        var source=endpoints.resolve(world,f.source);var destination=endpoints.resolve(world,f.destination);
        return source!=null&&destination!=null&&endpoints.mayAccess(source,f.owner)&&endpoints.mayAccess(destination,f.owner);
    }
    private void dispatch(World world,WorldState ws,Node sourceTube,int face){
        String timer=key(sourceTube.position)+":"+face;if(ws.nextExtract.getOrDefault(timer,0d)>ws.clock)return;ws.nextExtract.put(timer,ws.clock+1);
        if(ws.flights.size()>=MAX_WORLD_FLIGHTS)return;var config=sourceTube.component.face(face);
        if(tube(world,sourceTube.position.offset(face)))return;
        var source=endpoints.resolve(world,sourceTube.position.offset(face),config.section,true);
        if(source==null||!source.port().extractable()){ws.status.put(sourceTube.position,"No extractable container section");return;}
        if(ledger.blocked(source)){ws.status.put(sourceTube.position,ledger.status(world,source.position()));return;}
        if(!ledger.ready(source)){ws.status.put(sourceTube.position,"Saving container identity");return;}
        if(sourceTube.component.owner()==null||!endpoints.mayAccess(source,sourceTube.component.owner())){ws.status.put(sourceTube.position,"Configure this tube before use");return;}
        var paths=paths(world,ws,sourceTube.position);if(paths.isEmpty()){ws.status.put(sourceTube.position,ws.work<=0?"Network busy":"Network exceeds 128 tubes");return;}
        if(ws.flights.stream().filter(f->paths.containsKey(f.fromTube)).count()>=MAX_FLIGHTS)return;
        for(short slot=0;slot<source.port().inventory().getCapacity()&&ws.work>0;slot++){
            ws.work--;
            var stack=source.port().inventory().getItemStack(slot);if(!config.accepts(stack)||!source.port().acceptsExtract().test(stack))continue;
            final short sourceSlot=slot;
            int slotReserved=ws.flights.stream().filter(f->sameEndpoint(source,f.source)&&f.slot==sourceSlot).mapToInt(f->f.quantity).sum();
            int available=Math.min(stack.getQuantity()-slotReserved,config.count(source.port().inventory())-reservedSource(ws,source,null,config)-config.leaveBehind);
            int amount=Math.min(config.batch,available);if(amount<=0)continue;
            var targets=new ArrayList<Target>();
            for(var entry:paths.entrySet()){
                if(entry.getValue().size()>MAX_ROUTE)continue;
                var n=ws.nodes.get(entry.getKey());if(n==null)continue;
                for(int out=0;out<6;out++){if(--ws.work<=0)return;var insert=n.component.face(out);if(insert.mode!=TubeConfiguration.Mode.INSERT||!insert.accepts(stack)||tube(world,n.position.offset(out)))continue;
                    var endpoint=endpoints.resolve(world,n.position.offset(out),insert.section,true);
                    if(endpoint==null||endpoint.port().inventory()==source.port().inventory()||ledger.blocked(endpoint)||!endpoints.mayAccess(endpoint,sourceTube.component.owner())||!endpoint.port().acceptsInsert().test(stack)||!ledger.ready(endpoint))continue;
                    targets.add(new Target(n,out,endpoint,insert,entry.getValue()));
                }
            }
            Collections.rotate(targets,targets.isEmpty()?0:(int)(ws.tie++%targets.size()));targets.sort(Comparator.<Target>comparingInt(t->-t.config.priority).thenComparingInt(t->t.path.size()));
            for(var target:targets){
                int count=Math.min(amount,target.config.batch);if(target.config.fillUpTo>0)count=Math.min(count,target.config.fillUpTo-target.config.count(target.endpoint.port().inventory())-reservedTarget(ws,target.endpoint,null,target.config));
                if(count<=0||!capacity(ws,target.path))continue;
                var staging=TubeStacks.copy(target.endpoint.port().inventory());boolean fits=true;
                for(var prior:ws.flights)if(sameEndpoint(target.endpoint,prior.destination)){
                    var tx=staging.addItemStack(TubeStacks.quantity(TubeStacks.decode(prior.original),prior.quantity),true,false,true);if(!tx.succeeded()){fits=false;break;}}
                if(!fits||!staging.addItemStack(TubeStacks.quantity(stack,count),true,false,true).succeeded())continue;
                if(TubeTransferLedger.plan(source,target.endpoint,slot,count)==null)continue;
                var flight=new Flight(sourceTube.component.owner(),sourceTube,face,source,target,slot,count,TubeStacks.encode(stack),ws.clock);ws.flights.add(flight);
                for(int i=1;i<target.path.size();i++)ws.capacity.put(Edge.of(target.path.get(i-1),target.path.get(i)),ws.clock+1);
                ws.status.put(sourceTube.position,"Moving "+count+" items");return;
            }
        }
        ws.status.put(sourceTube.position,"Waiting for matching items and destination space");
    }
    private Map<Position,List<Position>> paths(World world,WorldState ws,Position from){
        var cached=ws.routes.get(from);if(cached!=null)return cached;
        Map<Position,List<Position>> paths=new LinkedHashMap<>();var queue=new ArrayDeque<Position>();paths.put(from,List.of(from));queue.add(from);
        while(!queue.isEmpty()){
            var p=queue.removeFirst();var route=paths.get(p);for(int f=0;f<6;f++){if(--ws.work<=0)return Map.of();var next=p.offset(f);if(paths.containsKey(next)||!ws.nodes.containsKey(next)||!tube(world,next))continue;
                if(paths.size()>=MAX_NETWORK){ws.routes.put(from,Map.of());return Map.of();}
                var path=new ArrayList<>(route);path.add(next);paths.put(next,List.copyOf(path));queue.add(next);
            }
        }
        if(ws.routes.size()>=64)ws.routes.clear();ws.routes.put(from,paths);return paths;
    }
    private static boolean capacity(WorldState ws,List<Position> route){for(int i=1;i<route.size();i++)if(ws.capacity.getOrDefault(Edge.of(route.get(i-1),route.get(i)),0d)>ws.clock)return false;return true;}
    private static boolean sameEndpoint(Endpoint e,Address a){return e.receipts().owns(a.identity())&&e.port().section().equals(a.section());}
    private static int reservedSource(WorldState ws,Endpoint e,UUID except,TubeConfiguration filter){return ws.flights.stream().filter(f->!f.id.equals(except)&&sameEndpoint(e,f.source)&&filter.accepts(TubeStacks.decode(f.original))).mapToInt(f->f.quantity).sum();}
    private static int reservedTarget(WorldState ws,Endpoint e,UUID except,TubeConfiguration filter){return ws.flights.stream().filter(f->!f.id.equals(except)&&sameEndpoint(e,f.destination)&&filter.accepts(TubeStacks.decode(f.original))).mapToInt(f->f.quantity).sum();}
    private static void cancel(WorldState ws,Flight flight){TubeCarrier.remove(flight.carrier);ws.flights.remove(flight);}
    public boolean isRecoveryBlocked(World world,Vector3i p){return ledger.blocked(world,new Position(p));}
    public void open(PlayerRef player,Store<EntityStore> store,Vector3i position){
        var world=store.getExternalData().getWorld();var n=node(world,new Position(position));if(n==null||!canConfigure(player,store,n))return;
        if(n.component.owner()==null){n.component.owner(player.getUuid());dirty(n);}
        refresh(world,state(world),n.position);
        var entity=store.getComponent(player.getReference(),Player.getComponentType());if(entity!=null)entity.getPageManager().openCustomPage(player.getReference(),store,new TubePage(player,this,world,n));
    }
    boolean canConfigure(PlayerRef player,Store<EntityStore> store,Node n){var ref=player.getReference();if(ref==null||!ref.isValid()||ref.getStore()!=store)return false;
        var transform=store.getComponent(ref,TransformComponent.getComponentType());return transform!=null&&transform.getPosition().distanceSquared(center(n.position))<=64&&(n.component.owner()==null||n.component.owner().equals(player.getUuid()));}
    Node current(World world,Position p){return node(world,p);}
    List<Endpoint> ports(World world,Position p){return endpoints.all(world,p,true);}
    boolean configure(PlayerRef player,Store<EntityStore> store,World world,UUID identity,Position position,int face,TubeConfiguration draft){
        var n=node(world,position);if(n==null||!identity.equals(n.component.id())||!canConfigure(player,store,n))return false;
        draft.validate();n.component.configure(face,draft);dirty(n);state(world).routes.clear();state(world).indicators.invalidate();refresh(world,state(world),position);return true;
    }
    String status(World world,Position p){return state(world).status.getOrDefault(p,"Choose a container face to configure");}
    TubeTransferLedger ledger(){return ledger;}
    int activeFlights(World world){return state(world).flights.size();}
    public void stopWorld(World world){var ws=worlds.remove(world.getName());if(ws!=null)for(var f:List.copyOf(ws.flights))cancel(ws,f);}
    @Override public void close(){ledger.close();}
}
