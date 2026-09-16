package com.hexvane.strangematter;

import com.hexvane.strangematter.anomaly.AnomalyVerification;
import com.hexvane.strangematter.machine.*;
import com.hexvane.strangematter.research.*;
import org.joml.Vector3i;
import java.util.*;

/** Deterministic integration checks of progression, save boundaries and energy conservation. */
public final class GameplayVerification {
    public static void main(String[] args) throws Exception {
        try { verify(); }
        catch(Exception|Error failure) {
            // The native log manager is installed before server handlers exist in this
            // headless runner; keep assertion failures visible in Gradle's captured output.
            failure.printStackTrace(System.out);throw failure;
        }
    }
    private static void verify() throws Exception {
        PluginDataPathsVerification.main(new String[0]);
        EnergyBalanceVerification.verify();
        com.hexvane.strangematter.diagnostics.WorldStallDiagnosticsVerification.verify();
        network(); recipes();
        ResearchVerification.main(new String[0]);
        ResearchProgressionVerification.main(new String[0]);
        ResearchPointsVerification.main(new String[0]);
        com.hexvane.strangematter.ui.LivePageVerification.main(new String[0]);
        AnomalyVerification.main(new String[0]);
        com.hexvane.strangematter.equipment.EquipmentVerification.main(new String[0]);
        com.hexvane.strangematter.equipment.EquipmentGeometryVerification.main(new String[0]);
        com.hexvane.strangematter.equipment.CapsuleFlightVerification.main(new String[0]);
        com.hexvane.strangematter.worldgen.ScientistVerification.main(new String[0]);
        System.out.println("PASS: 20,000 energy scenarios, cyclic network traversal, all forge gates/costs, research and anomaly persistence.");
    }
    private static MachineState node(String id,int x,int y,int z){return new MachineState("test",new Vector3i(x,y,z),id);}
    private static void network(){
        var random=new Random(0x535452414E47454CL);
        for(int trial=0;trial<20000;trial++) {
            var source=node("SM_Resonant_Burner",0,0,0);var sink=node("SM_Resonance_Condenser",4,0,0);
            int capacity=1+random.nextInt(100000),rate=1+random.nextInt(10000),budget=random.nextInt(12000);
            source.energy=random.nextInt(100000);sink.energy=random.nextInt(capacity+1);
            int beforeSource=source.energy,beforeSink=sink.energy;
            var wires=new ArrayList<ResonantNetwork.Position>();var used=new HashMap<ResonantNetwork.Position,Integer>();
            for(int i=0,n=random.nextInt(40);i<n;i++){var pos=new ResonantNetwork.Position(i,0,0);wires.add(pos);used.put(pos,random.nextInt(rate+1));}
            var prior=new HashMap<>(used);
            double loss=trial%2==0?.05:random.nextDouble()*.2;
            int debit=ResonantNetwork.transfer(source,new ResonantNetwork.Route(sink,wires),budget,capacity,rate,loss,used);
            check(source.energy>=0&&source.energy<=beforeSource,"Source remains bounded");
            check(sink.energy>=beforeSink&&sink.energy<=capacity,"Consumer remains bounded");
            check(debit==beforeSource-source.energy&&debit<=budget,"Transfer respects generation budget");
            check(sink.energy-beforeSink==debit,"Wires conserve energy");
            for(var wire:wires)check(used.get(wire)<=rate&&used.get(wire)-prior.get(wire)==debit,"Shared conduit capacity");
        }
        var source=node("SM_Resonant_Burner",0,0,0);var sink=node("SM_Resonance_Condenser",3,0,0);
        var nodes=new HashMap<ResonantNetwork.Position,MachineState>();
        for(int[] p:new int[][]{{1,0,0},{2,0,0},{1,0,1},{2,0,1}})nodes.put(new ResonantNetwork.Position(p[0],p[1],p[2]),node("SM_Resonant_Conduit",p[0],p[1],p[2]));
        nodes.put(new ResonantNetwork.Position(3,0,0),sink);
        var routes=ResonantNetwork.routes(source,nodes,64);
        check(routes.size()==1&&routes.getFirst().wires().size()==2,"Cycle resolves once by shortest path");
        check(ResonantNetwork.routes(source,nodes,1).isEmpty(),"Network search obeys size cap");
        source.energy=1000;sink.energy=0;
        check(ResonantNetwork.transfer(source,routes.getFirst(),1000,1000,500,.05,new HashMap<>())==425&&sink.energy==425,"Two conduits retain source three-edge throughput penalty");
        nodes.get(new ResonantNetwork.Position(2,0,0)).enabled=false;
        nodes.put(new ResonantNetwork.Position(3,0,1),node("SM_Resonant_Conduit",3,0,1));
        var detour=ResonantNetwork.routes(source,nodes,64);
        check(detour.size()==1&&detour.getFirst().wires().size()==4,"Disabled shortest path reroutes through connected active conduits");
        for(String id:List.of("SM_Reality_Forge","SM_Resonant_Separator","SM_Flux_Furnace","SM_Pattern_Assembler")){
            var consumer=node(id,2,1,0);var wire=node("SM_Resonant_Conduit",1,1,0);
            var ports=new HashMap<ResonantNetwork.Position,MachineState>();
            ports.put(new ResonantNetwork.Position(0,0,0),source);
            ports.put(new ResonantNetwork.Position(0,1,0),source);
            ports.put(new ResonantNetwork.Position(1,1,0),wire);
            ports.put(new ResonantNetwork.Position(2,1,0),consumer);
            ports.put(new ResonantNetwork.Position(1,2,0),consumer);
            var reachable=ResonantNetwork.routes(source,ports,64);
            check(reachable.size()==1&&reachable.getFirst().consumer()==consumer&&reachable.getFirst().wires().size()==1,"One consumer per multiblock, with power entering the generator's upper face: "+id);
            source.energy=1000;consumer.energy=0;
            check(ResonantNetwork.transfer(source,reachable.getFirst(),500,73,500,.05,new HashMap<>())==73&&source.energy==927,"Each factory obeys its own capacity: "+id);
        }
    }
    private static void recipes() throws Exception {
        var recipes=ForgeRecipe.load();check(recipes.size()==18,"Original forge recipes, automation, battery pack and two powered weapons");
        Set<String> ids=new HashSet<>();
        for(var recipe:recipes){
            check(ids.add(recipe.id)&&recipe.quantity>0,"Unique productive recipe");
            check(ResearchCatalog.get(recipe.research)!=null,"Recipe has existing research gate: "+recipe.id);
            check(recipe.totalCost().values().stream().allMatch(n->n>0),"Positive material costs");
            int expected=recipe.ingredients.values().stream().mapToInt(n->n).sum()+recipe.shards.values().stream().mapToInt(n->n).sum();
            check(recipe.totalCost().values().stream().mapToInt(n->n).sum()==expected,"Both ingredients and additional shard costs preserved");
        }
        check(ids.containsAll(Set.of("chrono_blister","containment_capsule","echo_vacuum","echoform_imprinter","graviton_hammer","hoverboard","levitation_pad","resonance_condenser","rift_stabilizer","stasis_projector","warp_gun","anomaly_nullifier")),"Original forge recipe identities preserved beside the new device");
        check(ids.containsAll(Set.of("resonant_separator","flux_furnace","pattern_assembler")),"All powered production machines are obtainable");
        check(ResearchCatalog.nodes().size()==33,"Original research, automation and three gadget discoveries");
        for(var node:ResearchCatalog.nodes())for(String pre:node.prerequisites())check(ResearchCatalog.get(pre)!=null,"Research prerequisite exists");
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
