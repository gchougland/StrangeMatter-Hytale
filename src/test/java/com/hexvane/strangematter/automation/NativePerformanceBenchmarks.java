package com.hexvane.strangematter.automation;

import com.google.gson.GsonBuilder;
import com.hexvane.strangematter.diagnostics.Benchmark;
import com.hexvane.strangematter.machine.*;
import com.hexvane.strangematter.research.*;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.MaterialQuantity;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.universe.world.World;
import org.joml.Vector3i;
import java.nio.file.*;
import java.util.*;
import static com.hexvane.strangematter.automation.TubeEndpoints.*;

/** Runs only in the disposable diagnostics world with the installed native assets and API. */
public final class NativePerformanceBenchmarks {
    public static void run(World world, ResearchService research, MachineService machines, Path directory) throws Exception {
        world.getEntityStore().getStore().assertThread();
        var results=new ArrayList<Benchmark.Result>();
        for(int count:new int[]{32,128}){
            var nodes=new LinkedHashMap<ResonantNetwork.Position,MachineState>();
            var source=new MachineState(world.getName(),new Vector3i(0,200,0),"SM_Resonant_Burner");nodes.put(new ResonantNetwork.Position(0,200,0),source);
            for(int x=1;x<count;x++)nodes.put(new ResonantNetwork.Position(x,200,0),new MachineState(world.getName(),new Vector3i(x,200,0),x==count-1?"SM_Reality_Forge":"SM_Resonant_Conduit"));
            results.add(Benchmark.measure("power_routes_"+count,"One uncached route walk through a straight connected power network",8,()->{
                var routes=ResonantNetwork.routes(source,nodes,128);if(routes.size()!=1)throw new AssertionError("Benchmark power route missing");return routes.getFirst().wires().size();}));
        }
        for(short capacity:new short[]{36,108}){
            var inventory=new SimpleItemContainer(capacity);
            for(short slot=0;slot<capacity;slot++)inventory.setItemStackForSlot(slot,new ItemStack(slot%2==0?"Wood_Softwood_Planks":"Ingredient_Bar_Copper",25),false);
            var cost=List.of(new MaterialQuantity(null,"Wood_Planks",null,35,null),new MaterialQuantity("Ingredient_Bar_Copper",null,null,35,null));
            results.add(Benchmark.measure("ingredients_"+capacity,"One real native reservation plan, including resource matching and a container snapshot",8,()->{
                var plan=FactoryInventory.plan(inventory,cost,s->true,false);if(plan==null)throw new AssertionError("Benchmark ingredients missing");return plan.removals().size();}));
        }
        UUID player=UUID.fromString("d0d2e83c-e88c-4aaa-8db3-ab62ce9d8b7c");research.unlock(player,"all",true);
        for(int i=0;i<64;i++)research.scan(player,"benchmark_scan_"+i,ResearchType.values()[i%ResearchType.values().length],1);
        results.add(Benchmark.measure("research_profile","One full profile read with all topics unlocked and 64 scan records",8,()->research.profile(player).unlocked().size()));
        transferPlans(world,results);
        machines.setFactory(new FactoryService(machines,research));
        for(int count:new int[]{16,64}){
            var placed=new ArrayList<MachineState>();
            try {
                for(int i=0;i<count;i++){
                    var p=new Vector3i(2+(i%16)*4,180,2+(i/16)*4);
                    String id=i%4==0?"SM_Resonant_Burner":i%4==1?"SM_Resonance_Condenser":"SM_Resonant_Conduit";
                    world.setBlock(p.x,p.y-1,p.z,"Rock_Stone");world.setBlock(p.x,p.y,p.z,id);
                    var machine=machines.register(world,p,id);machine.fuelTicks=1_000_000;placed.add(machine);
                }
                results.add(Benchmark.measure("machine_tick_"+count,"One 50 ms simulation step: loaded native machines, factory inventory registration, presentation sync and periodic state saves; quarter burning, quarter idle condensers, half conduits",1,()->{
                    machines.tick(world,.05);return placed.getFirst().energy;}));
            } finally {
                machines.cleanupPresentation(world);
                for(var machine:placed){world.setBlock(machine.x,machine.y,machine.z,"Empty");machines.removed(world,machine.block());}
            }
        }
        idleTubes(world,directory,results);
        var report=new LinkedHashMap<String,Object>();report.put("schema",1);report.put("createdUtc",java.time.Instant.now().toString());
        report.put("java",System.getProperty("java.runtime.version"));report.put("os",System.getProperty("os.name"));report.put("arch",System.getProperty("os.arch"));
        report.put("processors",Runtime.getRuntime().availableProcessors());report.put("cpu",System.getenv("PROCESSOR_IDENTIFIER"));report.put("maximumHeapBytes",Runtime.getRuntime().maxMemory());
        report.put("scope","Server components in a loaded flat world with no connected clients. Includes periodic machine state saves. Excludes startup, terrain generation, network round trips, client rendering, GUI interaction, and durable tube checkpoint throughput.");
        report.put("results",results);Files.writeString(Path.of("performance-report.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));
    }
    private static void transferPlans(World world,List<Benchmark.Result> results){
        var endpoints=new TubeEndpoints(null);var source=new Position(2,240,2);var chest=new Position(12,240,2);var furnace=new Position(22,240,2);
        for(var p:List.of(source,chest,furnace))world.setBlock(p.x(),p.y()-1,p.z(),"Rock_Stone");
        world.setBlock(source.x(),source.y(),source.z(),"SM_Resonite_Chest");world.setBlock(chest.x(),chest.y(),chest.z(),"SM_Resonite_Chest");world.setBlock(furnace.x(),furnace.y(),furnace.z(),"Bench_Furnace");
        var from=Objects.requireNonNull(endpoints.resolve(world,source,"storage",true));
        from.port().inventory().setItemStackForSlot((short)0,new ItemStack("Ingredient_Charcoal",25),false);
        for(var target:List.of(Objects.requireNonNull(endpoints.resolve(world,chest,"storage",true)),Objects.requireNonNull(endpoints.resolve(world,furnace,"fuel",true)))){
            target.port().inventory().setItemStackForSlot((short)0,new ItemStack("Ingredient_Charcoal",5),false);
            results.add(Benchmark.measure("tube_plan_"+target.port().section(),"One metadata preserving transfer plan into an occupied native container; excludes mutation and disk checkpoint",4,()->{
                var plan=TubeTransferLedger.plan(from,target,(short)0,5);if(plan==null)throw new AssertionError("Benchmark transfer did not fit");return plan.afterDestination().size();}));
        }
        for(var p:List.of(source,chest,furnace))world.setBlock(p.x(),p.y(),p.z(),"Empty");
    }
    private static void idleTubes(World world,Path directory,List<Benchmark.Result> results) throws Exception {
        try(var tubes=new TubeService(directory.resolve("benchmark-tubes"),null)){
            UUID owner=UUID.fromString("d0d2e83c-e88c-4aaa-8db3-ab62ce9d8b7c");
            for(int x=2;x<18;x++)for(int z=2;z<10;z++){world.setBlock(x,220,z,TubeService.ID);tubes.placed(world,new Vector3i(x,220,z),owner);}
            results.add(Benchmark.measure("tube_tick_128_idle","One 50 ms simulation step over 128 connected native tubes with no transfers or viewers; includes periodic discovery and connection refresh",1,()->{
                tubes.tick(world,.05);return tubes.activeFlights(world);}));
            tubes.stopWorld(world);
            for(int x=2;x<18;x++)for(int z=2;z<10;z++)world.setBlock(x,220,z,"Empty");
        }
    }
    private NativePerformanceBenchmarks() {}
}
