package com.hexvane.strangematter.machine;

import com.google.gson.GsonBuilder;
import com.hexvane.strangematter.automation.FactoryPickup;
import com.hexvane.strangematter.automation.FactoryComponent;
import com.hexvane.strangematter.util.WorldAccess;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.ChunkFlag;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockComponentSection;
import com.hypixel.hytale.server.core.util.FillerBlockUtil;
import org.joml.Vector3i;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static com.hexvane.strangematter.machine.ResonantNetwork.Position;

/** Read physical terrain and compare it with registration. Never load chunks or change power. */
public final class PowerDiagnostics {
    public static final int MAX_NODES=512;
    private static final Set<String> SOURCES=Set.of(EnergyStoragePorts.ID,"SM_Resonant_Burner","SM_Rift_Stabilizer","SM_Paradoxical_Energy_Cell");
    private static final String[] DIRECTIONS={"EAST","WEST","UP","DOWN","SOUTH","NORTH"};
    public record Face(String local,String world,String mode,String neighbor){}
    public record Node(Position position,String block,String physicalVariant,String chunk,String publication,
                       String registry,boolean enabled,int energy,int capacity,int inputPerSecond,int outputPerSecond,
                       int receivedLastTick,int sentLastTick,String factoryHolder,Integer persistedEnergy,
                       boolean transferBlocked,int rotation,List<Face> faces){}
    public record RouteCheck(Position source,Position sink,int conduits,String outputFace,String inputFace,
                             boolean registeredPath,boolean tickingPath,boolean sourceEnabled,boolean sinkEnabled){}
    public record Report(String capturedAt,String world,Position origin,int radius,int nodeLimit,boolean truncated,
                         Map<String,String> chunks,List<Node> nodes,List<RouteCheck> routes,List<String> findings){
        public List<String> summary(){
            long missing=nodes.stream().filter(n->!n.registry.equals("MATCH")).count();
            var lines=new ArrayList<String>();
            lines.add("Power audit: "+nodes.size()+" blocks, "+chunks.size()+" chunks, "+routes.size()+" physical supply routes; "+missing+" registry mismatches.");
            for(var node:nodes)if(!node.block.equals("SM_Resonant_Conduit")){
                lines.add(node.block.replace("SM_","")+" at "+coordinates(node.position)+": "+node.energy+"/"+node.capacity+" RE; "+(node.enabled?"enabled":"disabled")+"; in "+node.inputPerSecond+", out "+node.outputPerSecond+" RE/s; "+node.publication);
                if(lines.size()>=9)break;
            }
            findings.stream().limit(5).forEach(lines::add);
            if(findings.isEmpty())lines.add("No topology mismatch found in this snapshot. The report includes face settings and recent transfer counters.");
            return List.copyOf(lines);
        }
    }
    private record Cell(Position position,Position origin,String id,String variant,String publication,int rotation){}
    private PowerDiagnostics(){}
    public static Report inspect(World world,MachineService service,Vector3i anchor,int radius){
        world.debugAssertInTickingThread();
        if(radius<1||radius>128)throw new IllegalArgumentException("Power audit radius must be from 1 to 128 blocks.");
        var origin=new Position(anchor.x(),anchor.y(),anchor.z());
        var registered=new LinkedHashMap<Position,MachineState>();
        boolean seedLimit=false;
        for(var state:service.inWorld(world))if(MachineService.powerNode(state.id)&&within(origin,new Position(state.x,state.y,state.z),radius)){
            if(registered.size()<MAX_NODES)registered.put(new Position(state.x,state.y,state.z),state);else seedLimit=true;
        }
        var chunks=new TreeMap<String,String>();var cells=new HashMap<Position,Cell>();
        var queue=new ArrayDeque<Position>();queue.add(origin);queue.addAll(registered.keySet());
        var seen=new HashSet<Position>();var physical=new LinkedHashMap<Position,MachineState>();
        var copies=new LinkedHashMap<Position,MachineState>();var nodeRows=new LinkedHashMap<Position,Node>();var findings=new LinkedHashSet<String>();
        boolean truncated=seedLimit;
        while(!queue.isEmpty()){
            var p=queue.remove();if(!within(origin,p,radius)||!seen.add(p))continue;
            var cell=cells.computeIfAbsent(p,k->read(world,k,chunks));
            if(!MachineService.powerNode(cell.id)){
                if(registered.containsKey(p))findings.add("REGISTERED_BLOCK_UNAVAILABLE at "+coordinates(p)+": "+cell.publication+" / "+cell.variant+". Registration must survive unpublished sections.");
                continue;
            }
            if(physical.size()>=MAX_NODES){truncated=true;break;}
            var state=copies.get(cell.origin);
            if(state==null){
                // A visible footprint cell can be inside the radius while its registered
                // origin is outside it. Registration identity has no spatial audit limit.
                var saved=service.get(world,vector(cell.origin));state=new MachineState(world.getName(),vector(cell.origin),cell.id);
                var component=nativeComponent(world,cell.origin);
                if(saved!=null&&saved.id.equals(cell.id)){
                    state.enabled=saved.enabled;state.energy=saved.energy;state.energyFaces=saved.energyFaces==null?null:saved.energyFaces.clone();
                    state.incomingRate=saved.incomingRate;state.outgoingRate=saved.outgoingRate;state.receivedThisTick=saved.receivedThisTick;state.sentThisTick=saved.sentThisTick;
                }else if(component!=null){state.energy=component.data.energy;state.energyFaces=component.data.energyFaces==null?null:component.data.energyFaces.clone();}
                state.factoryTier=component!=null?component.tier():saved!=null?saved.factoryTier:1;
                state.powerRotation=cell.rotation;copies.put(cell.origin,state);
                String registry=saved==null?"MISSING":saved.id.equals(cell.id)?"MATCH":"DIFFERENT: "+saved.id;
                if(!registry.equals("MATCH"))findings.add("PHYSICAL_BLOCK_NOT_REGISTERED at "+coordinates(cell.origin)+": "+cell.id+" ("+registry+").");
                var holder=BlockModule.getBlockEntity(world,cell.origin.x(),cell.origin.y(),cell.origin.z());
                String publication=holder!=null?"LIVE":component!=null?"PARKED":"NONE";
                boolean blocked=saved!=null&&service.factory()!=null&&service.factory().blocked(world,saved);
                if(blocked)findings.add("TRANSFER_RECOVERY_PENDING at "+coordinates(cell.origin)+"; native item/parcel recovery currently blocks transfer.");
                var faces=new ArrayList<Face>();
                if(EnergyStoragePorts.storage(state.id))for(var face:EnergyStoragePorts.Face.values()){
                    int side=EnergyStoragePorts.worldFace(state,face);var neighbor=cell.origin.neighbors().get(side);var other=cells.computeIfAbsent(neighbor,k->read(world,k,chunks));
                    faces.add(new Face(face.name(),DIRECTIONS[side],EnergyStoragePorts.mode(state,face).name(),coordinates(neighbor)+": "+other.variant+" / "+other.publication));
                }
                nodeRows.put(cell.origin,new Node(cell.origin,cell.id,cell.variant,chunkKey(cell.origin),cell.publication,registry,state.enabled,state.energy,service.capacity(state),state.incomingRate,state.outgoingRate,state.receivedThisTick,state.sentThisTick,publication,component==null?null:component.data.energy,blocked,state.powerRotation,List.copyOf(faces)));
            }
            physical.put(p,state);
            for(var adjacent:p.neighbors()){
                if(!within(origin,adjacent,radius)){truncated=true;continue;}
                var other=cells.computeIfAbsent(adjacent,k->read(world,k,chunks));
                if(other.publication.equals("UNLOADED")||other.publication.equals("SECTION_NOT_PUBLISHED"))findings.add("UNAVAILABLE_BOUNDARY at "+coordinates(adjacent)+": "+other.publication+". This audit did not load it.");
                else if(MachineService.powerNode(other.id))queue.add(adjacent);
            }
        }
        var logical=new HashMap<Position,MachineState>();
        for(var entry:physical.entrySet()){var s=entry.getValue();var actual=service.get(world,s.block());if(actual!=null&&actual.id.equals(s.id))logical.put(entry.getKey(),s);}
        var active=new HashMap<Position,MachineState>();
        for(var entry:physical.entrySet())if(ticking(world,entry.getKey())&&ticking(world,new Position(entry.getValue().x,entry.getValue().y,entry.getValue().z)))active.put(entry.getKey(),entry.getValue());
        var checks=new ArrayList<RouteCheck>();var physicalSupply=new HashSet<Position>();var registeredSupply=new HashSet<Position>();
        for(var entry:copies.entrySet())if(SOURCES.contains(entry.getValue().id)){
            var source=entry.getValue();var full=ResonantNetwork.topology(source,physical,service.config.maxNetworkSize);
            var recorded=ResonantNetwork.topology(source,logical,service.config.maxNetworkSize);
            var activeTargets=new HashSet<Position>();
            if(active.containsValue(source))for(var route:ResonantNetwork.topology(source,active,service.config.maxNetworkSize).routes())activeTargets.add(new Position(route.consumer().x,route.consumer().y,route.consumer().z));
            if(full.limited()||recorded.limited())findings.add("ROUTE_SEARCH_LIMIT at "+coordinates(entry.getKey())+": maxNetworkSize="+service.config.maxNetworkSize+".");
            var recordedTargets=new HashSet<Position>();for(var route:recorded.routes())recordedTargets.add(new Position(route.consumer().x,route.consumer().y,route.consumer().z));
            for(var route:full.routes()){
                var sink=route.consumer();var at=new Position(sink.x,sink.y,sink.z);boolean registeredPath=logical.containsValue(source)&&recordedTargets.contains(at);
                boolean tickingPath=activeTargets.contains(at);
                checks.add(new RouteCheck(entry.getKey(),at,route.wires().size(),direction(route.sourceFace()),direction(route.consumerFace()),registeredPath,tickingPath,source.enabled,sink.enabled));
                if(source.enabled&&source.energy>0&&sink.enabled){physicalSupply.add(at);if(registeredPath&&tickingPath)registeredSupply.add(at);}
                if(registeredPath&&!tickingPath)findings.add("NON_TICKING_ROUTE: "+coordinates(entry.getKey())+" -> "+coordinates(at)+" has resident terrain that is not currently active. It cannot transfer until native chunk activation.");
                if(!registeredPath)findings.add("PHYSICAL_ROUTE_MISSING_FROM_REGISTRY: "+coordinates(entry.getKey())+" -> "+coordinates(at)+".");
            }
        }
        for(var node:nodeRows.values())if(EnergyStoragePorts.storage(node.block)&&node.enabled&&node.energy<node.capacity){
            if(!registeredSupply.contains(node.position))findings.add((physicalSupply.contains(node.position)?"STORAGE_ROUTE_UNAVAILABLE":"NO_CHARGED_SOURCE_ROUTE")+" at "+coordinates(node.position)+". Check registry mismatches, world-facing inputs, and chunk publication.");
            else if(node.inputPerSecond==0)findings.add("NO_RECENT_STORAGE_INPUT at "+coordinates(node.position)+": a charged source has a route, but recent input is zero. Inspect producer/output limits, shared conduit demand, and recovery blockers; repeat after one second.");
        }
        if(nodeRows.isEmpty())findings.add("NO_POWER_BLOCKS_FOUND: aim at a machine/conduit or use explicit coordinates.");
        if(seedLimit)findings.add("REGISTRY_SEED_LIMIT: only the first "+MAX_NODES+" nearby registered positions were used to seed this audit. Aim at the affected installation or reduce the radius.");
        if(truncated)findings.add("AUDIT_TRUNCATED: radius="+radius+", physical cell limit="+MAX_NODES+". Unexamined terrain is not proof of disconnection.");
        return new Report(Instant.now().toString(),world.getName(),origin,radius,MAX_NODES,truncated,Collections.unmodifiableMap(chunks),List.copyOf(nodeRows.values()),List.copyOf(checks),List.copyOf(findings));
    }
    public static Path write(MachineService service,Report report)throws IOException{
        var directory=service.dataDirectory().resolve("power-diagnostics");Files.createDirectories(directory);
        var file=directory.resolve("power-"+Instant.now().toEpochMilli()+"-"+UUID.randomUUID().toString().substring(0,8)+".json");
        Files.writeString(file,new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n",StandardOpenOption.CREATE_NEW);return file;
    }
    private static Cell read(World world,Position p,Map<String,String> chunks){
        if(p.y()<0||p.y()>=ChunkUtil.HEIGHT)return new Cell(p,p,"","OUTSIDE_WORLD","OUTSIDE_WORLD",0);
        var chunk=WorldAccess.inMemory(world,ChunkUtil.indexChunkFromBlock(p.x(),p.z()));String status=chunk==null?"UNLOADED":chunk.is(ChunkFlag.TICKING)?"TICKING":"RESIDENT_NOT_TICKING";
        chunks.put(chunkKey(p),status);
        if(chunk==null)return new Cell(p,p,"","UNKNOWN",status,0);
        if(WorldAccess.section(chunk,p.y())==null)return new Cell(p,p,"","UNKNOWN","SECTION_NOT_PUBLISHED",0);
        if(chunk.is(ChunkFlag.TICKING)&&WorldAccess.tickingSection(chunk,p.y())==null)status="SECTION_NOT_TICKING";
        var type=WorldAccess.blockType(chunk,p.x(),p.y(),p.z());int filler=WorldAccess.filler(chunk,p.x(),p.y(),p.z());
        var origin=new Position(p.x()-FillerBlockUtil.unpackX(filler),p.y()-FillerBlockUtil.unpackY(filler),p.z()-FillerBlockUtil.unpackZ(filler));
        var root=WorldAccess.inMemory(world,ChunkUtil.indexChunkFromBlock(origin.x(),origin.z()));
        if(root==null||WorldAccess.section(root,origin.y())==null)return new Cell(p,origin,"",type==null?"UNKNOWN":type.getId(),"SECTION_NOT_PUBLISHED",0);
        var base=WorldAccess.blockType(root,origin.x(),origin.y(),origin.z());
        return new Cell(p,origin,MachineService.baseId(base),type==null?"UNKNOWN":type.getId(),status,WorldAccess.rotation(root,origin.x(),origin.y(),origin.z()));
    }
    private static FactoryComponent nativeComponent(World world,Position p){
        var live=FactoryPickup.component(world,vector(p));if(live!=null||FactoryComponent.getComponentType()==null)return live;
        var ref=world.getChunkStore().getChunkSectionReferenceAtBlock(p.x(),p.y(),p.z());if(ref==null||!ref.isValid())return null;
        var section=ref.getStore().getComponent(ref,BlockComponentSection.getComponentType());if(section==null)return null;
        var holder=section.getBlockHolder(ChunkUtil.indexBlock(p.x(),p.y(),p.z()));
        return holder==null?null:holder.getComponent(FactoryComponent.getComponentType());
    }
    private static boolean ticking(World world,Position p){
        var chunk=WorldAccess.loaded(world,ChunkUtil.indexChunkFromBlock(p.x(),p.z()));return chunk!=null&&WorldAccess.tickingSection(chunk,p.y())!=null;
    }
    private static boolean within(Position origin,Position p,int radius){return Math.abs((long)p.x()-origin.x())<=radius&&Math.abs((long)p.y()-origin.y())<=radius&&Math.abs((long)p.z()-origin.z())<=radius;}
    private static String chunkKey(Position p){return Math.floorDiv(p.x(),32)+","+Math.floorDiv(p.z(),32);}
    private static Vector3i vector(Position p){return new Vector3i(p.x(),p.y(),p.z());}
    private static String coordinates(Position p){return p.x()+","+p.y()+","+p.z();}
    private static String direction(int face){return face<0?"ANY":DIRECTIONS[face];}
}
