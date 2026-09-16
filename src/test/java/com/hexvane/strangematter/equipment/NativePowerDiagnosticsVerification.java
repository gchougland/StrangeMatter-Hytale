package com.hexvane.strangematter.equipment;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.hexvane.strangematter.StrangeMatterConfig;
import com.hexvane.strangematter.anomaly.AnomalyService;
import com.hexvane.strangematter.automation.FactoryComponent;
import com.hexvane.strangematter.automation.FactoryPickup;
import com.hexvane.strangematter.automation.FactoryService;
import com.hexvane.strangematter.machine.EnergyStoragePorts;
import com.hexvane.strangematter.machine.MachineService;
import com.hexvane.strangematter.machine.MachineState;
import com.hexvane.strangematter.machine.PowerDiagnostics;
import com.hexvane.strangematter.machine.ResonantNetwork.Position;
import com.hexvane.strangematter.research.ResearchService;
import com.hexvane.strangematter.util.WorldAccess;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.ChunkFlag;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockComponentSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.ChunkSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import org.bson.BsonInt64;
import org.joml.Vector3i;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import static com.hypixel.hytale.server.core.universe.world.SetBlockSettings.*;

/** Actual native terrain/holders prove the diagnostic observes rather than repairs a network. */
public final class NativePowerDiagnosticsVerification {
    private static final Gson GSON=new Gson();
    private static final int Y=270;
    private static final int RAW=NO_UPDATE_STATE|NO_SEND_PARTICLES|NO_UPDATE_NEIGHBOR_CONNECTIONS|NO_SET_FILLER|NO_BREAK_FILLER|FORCE_CHANGED;
    private record Cell(Vector3i position,int id,int rotation,int filler){}
    private record HolderState(Ref<ChunkStore> ref,FactoryComponent component,String data){}
    private record Snapshot(List<MachineState> identities,String states,Map<String,HolderState> holders,
                            Map<Long,String> chunks,int nativeEntities,String cells,String savedRegistry){}
    private NativePowerDiagnosticsVerification(){}

    public static void verify(World world,ResearchService research)throws Exception{
        world.debugAssertInTickingThread();
        require(ChunkUtil.HEIGHT>Y+20,"Diagnostic fixture fits in the native world height");
        require(WorldAccess.loaded(world,ChunkUtil.indexChunk(0,0))!=null&&WorldAccess.loaded(world,ChunkUtil.indexChunk(1,0))!=null,
                "Diagnostic fixture begins with both seam columns already ticking");
        Path path=Files.createTempDirectory("sm-native-power-diagnostic-");
        var cells=new ArrayList<Cell>();
        try(var machines=new MachineService(path,new StrangeMatterConfig(),research,new AnomalyService(path));
            var factory=new FactoryService(machines,research)){
            machines.setFactory(factory);
            try{
                rememberAndClear(world,cells,28,34,Y,Y+3,17,23);
                var first=place(world,machines,30,Y,20,"SM_Rift_Stabilizer");
                var second=place(world,machines,31,Y,19,"SM_Rift_Stabilizer");
                var paused=place(world,machines,31,Y,21,"SM_Resonance_Condenser");
                var storage=place(world,machines,32,Y,20,EnergyStoragePorts.ID);
                // A real block whose placement never entered this service's registry.
                var bridge=new Vector3i(31,Y,20);put(world,bridge,"SM_Resonant_Conduit",0,RAW);
                first.energy=machines.capacity(first);second.energy=machines.capacity(second);paused.enabled=false;paused.energy=0;
                var storageComponent=factory.register(world,storage);var pausedComponent=factory.register(world,paused);
                require(storageComponent!=null&&pausedComponent!=null,"Native storage and condenser own live Factory components");
                storage.energy=34250;storageComponent.data.energy=34250;
                pausedComponent.output.setItemStackForSlot((short)0,new ItemStack("SM_Resonite_Ingot",3).withMetadata("AuditSentinel",new BsonInt64(73)),false);
                first.sentThisTick=7;second.sentThisTick=9;storage.receivedThisTick=16;
                machines.save();
                var before=snapshot(world,machines,cells);
                var missing=PowerDiagnostics.inspect(world,machines,storage.block(),8);
                unchanged(before,snapshot(world,machines,cells),"Missing-wire inspection");
                require(machines.get(world,bridge)==null,"Inspecting a physical wire never silently registers or repairs it");
                require(node(missing,bridge).registry().equals("MISSING")&&has(missing,"PHYSICAL_BLOCK_NOT_REGISTERED"),
                        "Diagnostic explicitly identifies the missing physical conduit registration");
                require(has(missing,"PHYSICAL_ROUTE_MISSING_FROM_REGISTRY")&&has(missing,"STORAGE_ROUTE_UNAVAILABLE"),
                        "Diagnostic explains why physically connected nonfull storage cannot receive from the registered graph");
                var missingRoutes=missing.routes().stream().filter(r->r.sink().equals(position(storage.block()))&&
                        (r.source().equals(position(first.block()))||r.source().equals(position(second.block())))).toList();
                require(missingRoutes.size()==2&&missingRoutes.stream().allMatch(r->!r.registeredPath()&&r.tickingPath()&&r.conduits()==1),
                        "Both full native stabilizers have one-wire physical routes across the chunk seam, absent from the registry");
                require(!node(missing,paused.block()).enabled()&&node(missing,paused.block()).energy()==0&&
                        missing.routes().stream().filter(r->r.sink().equals(position(paused.block()))).allMatch(r->!r.sinkEnabled()),
                        "A paused empty condenser is reported as disabled without becoming demand");
                require(node(missing,storage.block()).factoryHolder().equals("LIVE")&&node(missing,storage.block()).persistedEnergy()==34250,
                        "Diagnostic reports the native authoritative holder reserve without migrating it");
                require(missing.chunks().containsKey("0,0")&&missing.chunks().containsKey("1,0"),"Report includes both actual seam columns");
                var written=PowerDiagnostics.write(machines,missing);verifyJson(written,missing);
                unchanged(before,snapshot(world,machines,cells),"Writing a diagnostic report");

                machines.register(world,bridge,"SM_Resonant_Conduit");
                var rotation=RotationTuple.of(Rotation.Ninety,Rotation.None);
                for(var face:EnergyStoragePorts.Face.values())EnergyStoragePorts.set(storage,face,EnergyStoragePorts.Mode.INPUT);
                EnergyStoragePorts.set(storage,EnergyStoragePorts.Face.TOP,EnergyStoragePorts.Mode.OUTPUT);
                EnergyStoragePorts.set(storage,EnergyStoragePorts.Face.BOTTOM,EnergyStoragePorts.Mode.DISABLED);
                put(world,storage.block(),EnergyStoragePorts.ID,rotation.index(),RAW);
                // Deliberately stale runtime rotation: inspect must read native orientation on
                // its private copy without changing the real machine before its next tick.
                storage.powerRotation=0;storageComponent.data.energyFaces=EnergyStoragePorts.snapshot(storage);machines.save();
                before=snapshot(world,machines,cells);
                var connected=PowerDiagnostics.inspect(world,machines,storage.block(),8);
                unchanged(before,snapshot(world,machines,cells),"Registered rotated-route inspection");
                require(connected.nodes().stream().allMatch(n->n.registry().equals("MATCH"))&&!has(connected,"PHYSICAL_ROUTE_MISSING_FROM_REGISTRY")&&
                        !has(connected,"STORAGE_ROUTE_UNAVAILABLE"),"Restoring the registry yields matching physical and registered routes");
                require(connected.routes().stream().filter(r->r.sink().equals(position(storage.block()))).count()==2&&
                        connected.routes().stream().filter(r->r.sink().equals(position(storage.block()))).allMatch(r->r.registeredPath()&&r.tickingPath()),
                        "Both sources retain real active routes after repair and native storage rotation");
                var storageRow=node(connected,storage.block());
                require(storageRow.rotation()==rotation.index()&&storage.powerRotation==0,"Native orientation is reported without mutating a stale runtime cache");
                var front=new Vector3i(0,0,1);rotation.applyRotationTo(front);
                require(storageRow.faces().stream().anyMatch(f->f.local().equals("FRONT")&&f.world().equals(direction(front))&&f.mode().equals("INPUT"))&&
                        storageRow.faces().stream().anyMatch(f->f.local().equals("TOP")&&f.world().equals("UP")&&f.mode().equals("OUTPUT"))&&
                        storageRow.faces().stream().anyMatch(f->f.local().equals("BOTTOM")&&f.world().equals("DOWN")&&f.mode().equals("DISABLED")),
                        "Diagnostic maps local face settings through native rotation and preserves output/disabled modes");

                var assembler=place(world,machines,28,Y,18,"SM_Pattern_Assembler");
                factory.initializeTier(world,assembler,3);var assemblerComponent=factory.register(world,assembler);
                require(assemblerComponent!=null&&assemblerComponent.tier()==3,"Native assembler fixture owns an actual tier-three Factory component");
                var tierWorking=BlockType.getAssetMap().getAsset(assembler.id).getBlockForState("Tier3Working");
                require(tierWorking!=null,"Native assembler has its tier-three working block variant");
                put(world,assembler.block(),tierWorking.getId(),0,RAW);
                assembler.factoryTier=1;assembler.energy=27123;assemblerComponent.data.energy=27123;machines.save();
                before=snapshot(world,machines,cells);
                var upgraded=PowerDiagnostics.inspect(world,machines,assembler.block(),3);
                unchanged(before,snapshot(world,machines,cells),"Upgraded assembler inspection");
                require(node(upgraded,assembler.block()).capacity()==machines.config.assemblerCapacity+2*machines.config.assemblerTierCapacity&&
                        node(upgraded,assembler.block()).capacity()>machines.config.assemblerCapacity&&assembler.factoryTier==1,
                        "Diagnostic uses the native tier-three capacity without overwriting a stale tier-one runtime cache");
                require(node(upgraded,assembler.block()).physicalVariant().equals(tierWorking.getId())&&node(upgraded,assembler.block()).persistedEnergy()==27123,
                        "Upgraded report retains the native working variant and authoritative stored reserve");

                // Condenser height creates a real native upper filler. Its origin is two
                // cells below the audit anchor, outside radius one, while its filler is inside.
                var boundaryAnchor=new Vector3i(paused.x,Y+2,paused.z);
                var pausedChunk=WorldAccess.inMemory(world,ChunkUtil.indexChunkFromBlock(paused.x,paused.z));
                require(WorldAccess.filler(pausedChunk,paused.x,Y+1,paused.z)!=0,"Boundary fixture includes a real native condenser filler");
                put(world,boundaryAnchor,"SM_Resonant_Conduit",0,RAW);machines.register(world,boundaryAnchor,"SM_Resonant_Conduit");machines.save();
                before=snapshot(world,machines,cells);
                var boundary=PowerDiagnostics.inspect(world,machines,boundaryAnchor,1);
                unchanged(before,snapshot(world,machines,cells),"Filler origin outside radius inspection");
                require(Math.abs(paused.y-boundaryAnchor.y)>boundary.radius()&&node(boundary,paused.block()).registry().equals("MATCH")&&
                        !node(boundary,paused.block()).enabled()&&node(boundary,paused.block()).factoryHolder().equals("LIVE"),
                        "A reachable native filler retains its registered out-of-radius origin, disabled flag and holder");
                require(boundary.nodes().stream().allMatch(n->n.registry().equals("MATCH"))&&!has(boundary,"PHYSICAL_BLOCK_NOT_REGISTERED"),
                        "Audit radius does not manufacture missing registrations for genuine multiblock origins");

                var distant=new Vector3i(-1_000_001,Y,-1_000_003);
                require(WorldAccess.inMemory(world,ChunkUtil.indexChunkFromBlock(distant.x,distant.z))==null,"Negative diagnostic target begins unloaded");
                var saved=machines.register(world,distant,EnergyStoragePorts.ID);saved.energy=913;machines.save();
                before=snapshot(world,machines,cells);
                var unloaded=PowerDiagnostics.inspect(world,machines,distant,32);
                unchanged(before,snapshot(world,machines,cells),"Unloaded negative-coordinate inspection");
                require(machines.get(world,distant)==saved&&saved.energy==913&&WorldAccess.inMemory(world,ChunkUtil.indexChunkFromBlock(distant.x,distant.z))==null,
                        "Unloaded terrain cannot cause loads, registration deletion, or saved energy loss");
                require(unloaded.nodes().isEmpty()&&has(unloaded,"REGISTERED_BLOCK_UNAVAILABLE")&&unloaded.chunks().values().stream().allMatch("UNLOADED"::equals),
                        "Negative unloaded target reports unavailable terrain instead of declaring it empty or disconnected");

                for(int x=0;x<25;x++)for(int y=0;y<21;y++){
                    var stale=machines.register(world,new Vector3i(distant.x+x,distant.y+y,distant.z),EnergyStoragePorts.ID);stale.energy=913+x+y;
                }
                machines.save();before=snapshot(world,machines,cells);
                var manyStale=PowerDiagnostics.inspect(world,machines,distant,128);
                unchanged(before,snapshot(world,machines,cells),"More than 512 stale registrations inspection");
                require(manyStale.nodes().isEmpty()&&manyStale.truncated()&&has(manyStale,"REGISTRY_SEED_LIMIT")&&has(manyStale,"AUDIT_TRUNCATED"),
                        "525 nearby stale registrations explicitly report bounded seed truncation without loading terrain");
                require(manyStale.findings().stream().filter(s->s.startsWith("REGISTERED_BLOCK_UNAVAILABLE")).count()==PowerDiagnostics.MAX_NODES&&
                        manyStale.chunks().values().stream().allMatch("UNLOADED"::equals)&&manyStale.summary().size()<=14,
                        "Unavailable seeds consume the 512-position budget and cannot create an unlimited diagnostic read or summary");

                var small=PowerDiagnostics.inspect(world,machines,bridge,1);
                require(small.truncated()&&has(small,"AUDIT_TRUNCATED")&&small.nodes().size()<=PowerDiagnostics.MAX_NODES,
                        "Radius-limited reports explicitly disclose unexamined terrain");
                rememberAndClear(world,cells,24,39,Y+8,Y+15,4,8);
                for(int x=24;x<=39;x++)for(int y=Y+8;y<=Y+15;y++)for(int z=4;z<=8;z++)put(world,new Vector3i(x,y,z),"SM_Resonant_Conduit",0,RAW);
                machines.save();before=snapshot(world,machines,cells);
                // This radius contains the whole dense graph and excludes the unrelated
                // registered machine footprint, so every counted cell is one conduit.
                var limited=PowerDiagnostics.inspect(world,machines,new Vector3i(31,Y+11,6),8);
                unchanged(before,snapshot(world,machines,cells),"Oversized physical graph inspection");
                require(limited.truncated()&&limited.nodes().size()==PowerDiagnostics.MAX_NODES&&has(limited,"AUDIT_TRUNCATED"),
                        "A real 640-conduit graph stops at the hard 512-cell diagnostic budget");
                require(limited.summary().size()<=14,"In-world summary remains bounded for a large broken network");
                verifyJson(PowerDiagnostics.write(machines,limited),limited);
                unchanged(before,snapshot(world,machines,cells),"Oversized report serialization");
            }finally{
                // Remove fixture-owned native holders before restoring exact saved terrain.
                for(var state:machines.inWorld(world))if(state.x>=28&&state.x<=34&&state.y==Y&&state.z>=17&&state.z<=23)
                    put(world,state.block(),"Empty",0,NO_SEND_PARTICLES|NO_UPDATE_NEIGHBOR_CONNECTIONS);
                factory.cleanup(world);
                for(var cell:cells){var p=cell.position;var chunk=WorldAccess.inMemory(world,ChunkUtil.indexChunkFromBlock(p.x,p.z));
                    WorldAccess.set(chunk,p.x,p.y,p.z,cell.id,BlockType.getAssetMap().getAsset(cell.id),cell.rotation,cell.filler,RAW);}
            }
        }
        System.out.println("NATIVE_POWER_DIAGNOSTICS_VERIFICATION_PASSED: two full native sources, paused condenser, missing seam wire and repaired rotated routes; native tier-three capacity and out-of-radius filler origins; exact registry/energy/holder/inventory/chunk purity; negative unloaded coordinates and 525 stale registrations; bounded radius/512-cell reports and native JSON output.");
    }

    private static void rememberAndClear(World world,List<Cell> saved,int minX,int maxX,int minY,int maxY,int minZ,int maxZ){
        for(int x=minX;x<=maxX;x++)for(int y=minY;y<=maxY;y++)for(int z=minZ;z<=maxZ;z++){
            var p=new Vector3i(x,y,z);var chunk=WorldAccess.loaded(world,ChunkUtil.indexChunkFromBlock(x,z));
            require(chunk!=null&&WorldAccess.section(chunk,y)!=null,"Fixture edits only already active loaded sections");
            var section=world.getChunkStore().getChunkSectionReferenceAtBlock(x,y,z);var components=section.getStore().getComponent(section,BlockComponentSection.getComponentType());
            require(components==null||!components.hasBlockComponents(ChunkUtil.indexBlock(x,y,z)),"Fixture spare terrain has no pre-existing block holder");
            saved.add(new Cell(p,WorldAccess.block(chunk,x,y,z),WorldAccess.rotation(chunk,x,y,z),WorldAccess.filler(chunk,x,y,z)));
            put(world,p,"Empty",0,RAW);
        }
    }
    private static MachineState place(World world,MachineService machines,int x,int y,int z,String id){var p=new Vector3i(x,y,z);put(world,p,id,0,NO_SEND_PARTICLES|NO_UPDATE_NEIGHBOR_CONNECTIONS);return machines.register(world,p,id);}
    private static void put(World world,Vector3i p,String id,int rotation,int flags){
        var chunk=WorldAccess.inMemory(world,ChunkUtil.indexChunkFromBlock(p.x,p.z));var type=BlockType.getAssetMap().getAsset(id);
        WorldAccess.set(chunk,p.x,p.y,p.z,BlockType.getAssetMap().getIndex(id),type,rotation,0,flags);
    }
    private static Snapshot snapshot(World world,MachineService machines,List<Cell> cells)throws Exception{
        var states=machines.inWorld(world);var data=new StringBuilder();var holders=new LinkedHashMap<String,HolderState>();
        for(var state:states){data.append(GSON.toJson(state)).append('|').append(state.factoryTier).append('|').append(state.powerRotation)
                .append('|').append(state.incomingRate).append('|').append(state.outgoingRate).append('|').append(state.receivedThisTick).append('|').append(state.sentThisTick)
                .append('|').append(state.powerRouteLimited).append('|').append(Arrays.toString(state.sentLastSecond)).append('|').append(Arrays.toString(state.receivedLastSecond));
            var ref=BlockModule.getBlockEntity(world,state.x,state.y,state.z);var component=FactoryPickup.component(world,state.block());
            holders.put(state.key(),new HolderState(ref,component,component==null?null:FactoryComponent.CODEC.encode(component,new ExtraInfo()).asDocument().toJson()));
        }
        var chunks=new TreeMap<Long,String>();for(long index:world.getChunkStore().getChunkIndexes().toLongArray()){
            var chunk=WorldAccess.inMemory(world,index);if(chunk==null)continue;var flags=new StringBuilder();for(var flag:ChunkFlag.values())flags.append(flag).append('=').append(chunk.is(flag)).append(';');
            var ref=world.getChunkStore().getChunkSectionReference(chunk.getX(),Math.floorDiv(Y,ChunkUtil.SIZE),chunk.getZ());
            if(ref!=null&&ref.isValid()){var section=ref.getStore().getComponent(ref,ChunkSection.getComponentType());flags.append("section=").append(ref).append(';').append(section.needsSaving()).append(';').append(section.isSaving());}
            chunks.put(index,flags.toString());
        }
        var terrain=new StringBuilder();for(var cell:cells){var p=cell.position;var chunk=WorldAccess.inMemory(world,ChunkUtil.indexChunkFromBlock(p.x,p.z));terrain.append(WorldAccess.block(chunk,p.x,p.y,p.z)).append(',').append(WorldAccess.rotation(chunk,p.x,p.y,p.z)).append(',').append(WorldAccess.filler(chunk,p.x,p.y,p.z)).append(';');}
        return new Snapshot(List.copyOf(states),data.toString(),holders,chunks,world.getChunkStore().getStore().getEntityCount(),terrain.toString(),Files.readString(machines.dataDirectory().resolve("machines.json")));
    }
    private static void unchanged(Snapshot expected,Snapshot actual,String label){
        require(expected.identities.equals(actual.identities)&&expected.states.equals(actual.states),label+" preserves machine identity, saved state, power and runtime counters");
        require(expected.holders.equals(actual.holders),label+" preserves native holder/component identity, typed inventories and all serialized Factory data");
        require(expected.chunks.equals(actual.chunks)&&expected.nativeEntities==actual.nativeEntities&&expected.cells.equals(actual.cells),label+" preserves chunk publication/ticking/save flags, block data and native entity count");
        require(expected.savedRegistry.equals(actual.savedRegistry),label+" leaves the durable machine registry byte-identical");
    }
    private static void verifyJson(Path file,PowerDiagnostics.Report report)throws Exception{
        require(file.getParent().getFileName().toString().equals("power-diagnostics")&&Files.size(file)<2_000_000,"Diagnostic writes a bounded standalone report in its own directory");
        var json=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        require(json.getAsJsonArray("nodes").size()==report.nodes().size()&&json.getAsJsonArray("routes").size()==report.routes().size()&&
                json.get("truncated").getAsBoolean()==report.truncated()&&json.get("nodeLimit").getAsInt()==PowerDiagnostics.MAX_NODES,
                "Saved JSON retains exact native diagnostic evidence and truncation metadata");
    }
    private static PowerDiagnostics.Node node(PowerDiagnostics.Report report,Vector3i p){return report.nodes().stream().filter(n->n.position().equals(position(p))).findFirst().orElseThrow();}
    private static boolean has(PowerDiagnostics.Report report,String finding){return report.findings().stream().anyMatch(s->s.startsWith(finding));}
    private static Position position(Vector3i p){return new Position(p.x,p.y,p.z);}
    private static String direction(Vector3i p){return p.x==1?"EAST":p.x==-1?"WEST":p.y==1?"UP":p.y==-1?"DOWN":p.z==1?"SOUTH":"NORTH";}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
