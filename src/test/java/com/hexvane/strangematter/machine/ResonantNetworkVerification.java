package com.hexvane.strangematter.machine;

import java.util.*;
import com.hexvane.strangematter.machine.ResonantNetwork.Position;
import com.hexvane.strangematter.machine.ResonantNetwork.Route;

/** Differential checks against the previous path-copying BFS, without a native world. */
public final class ResonantNetworkVerification {
    private static final String WIRE="SM_Resonant_Conduit",SOURCE="SM_Resonant_Burner",SINK="SM_Reality_Forge";

    public static void main(String[] args){verify();}

    public static void verify(){
        boundaries();aliasesAndTieOrder();terminalAndIdentity();longPath();randomized();storagePorts();distribution();
        System.out.println("PASS: predecessor routes match the original BFS in 12,000 randomized walks and explicit boundary cases.");
    }

    private static MachineState node(String id,Position p){
        var result=new MachineState();result.world="network-test";result.id=id;result.x=p.x();result.y=p.y();result.z=p.z();return result;
    }
    private static Position pos(int x,int y,int z){return new Position(x,y,z);}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}

    /** Exact old implementation retained as a behavioral oracle, including seen-on-pop and its cap. */
    private static List<Route> oldRoutes(MachineState source,Map<Position,MachineState> nodes,int max){
        record Visit(Position position,List<Position> wires){}
        Queue<Visit> todo=new ArrayDeque<>();Set<Position> seen=new HashSet<>();List<Route> result=new ArrayList<>();
        var starts=nodes.entrySet().stream().filter(e->e.getValue()==source).map(Map.Entry::getKey).toList();
        if(starts.isEmpty())starts=List.of(new Position(source.x,source.y,source.z));
        seen.addAll(starts);for(var start:starts)for(var n:start.neighbors())todo.add(new Visit(n,List.of()));
        int visited=0;
        while(!todo.isEmpty()&&visited<max){
            var v=todo.remove();if(!seen.add(v.position))continue;
            var node=nodes.get(v.position);if(node==null||!node.enabled)continue;visited++;
            if(MachineService.consumesPower(node.id)){if(result.stream().noneMatch(r->r.consumer()==node))result.add(new Route(node,v.wires));continue;}
            if(!node.id.equals(WIRE))continue;
            var wires=new ArrayList<>(v.wires);wires.add(v.position);
            for(var n:v.position.neighbors())todo.add(new Visit(n,List.copyOf(wires)));
        }
        return result;
    }

    private static List<Route> compare(MachineState source,Map<Position,MachineState> nodes,int max,String scenario){
        var expected=oldRoutes(source,nodes,max);var actual=ResonantNetwork.routes(source,nodes,max);
        check(actual.size()==expected.size(),scenario+": consumer count at cap "+max);
        for(int i=0;i<actual.size();i++){
            check(actual.get(i).consumer()==expected.get(i).consumer(),scenario+": consumer identity or BFS tie order at "+i);
            check(actual.get(i).wires().equals(expected.get(i).wires()),scenario+": exact shortest route at "+i);
        }
        return actual;
    }

    private static void boundaries(){
        var source=node(SOURCE,pos(0,0,0));var sink=node(SINK,pos(-1,0,0));
        var nodes=new LinkedHashMap<Position,MachineState>();nodes.put(pos(-1,0,0),sink);
        for(int cap:new int[]{Integer.MIN_VALUE,-1,0})check(compare(source,nodes,cap,"nonpositive cap").isEmpty(),"No visits at nonpositive cap");
        var direct=compare(source,nodes,1,"unmapped source fallback");
        check(direct.size()==1&&direct.getFirst().wires().isEmpty(),"Adjacent consumer uses no conduit");
        var obstacle=node("SM_Resonite_Block",pos(1,0,0));nodes.put(pos(1,0,0),obstacle);
        check(compare(source,nodes,1,"enabled terminal obstacle").isEmpty(),"Enabled nonconduit counts toward cap");
        obstacle.enabled=false;
        check(compare(source,nodes,1,"disabled obstacle").size()==1,"Disabled nodes do not consume visit allowance");
        source.enabled=false;nodes.put(pos(0,0,0),source);
        check(compare(source,nodes,1,"disabled source alias").size()==1,"Source enabled state is not a traversal filter");
        compare(source,Map.of(),128,"empty network");
    }

    private static void aliasesAndTieOrder(){
        var source=node(SOURCE,pos(0,0,0));var sink=node(SINK,pos(1,0,1));
        var nodes=new LinkedHashMap<Position,MachineState>();nodes.put(pos(0,0,0),source);
        nodes.put(pos(1,0,0),node(WIRE,pos(1,0,0)));nodes.put(pos(0,0,1),node(WIRE,pos(0,0,1)));nodes.put(pos(1,0,1),sink);
        check(compare(source,nodes,128,"equal length face routes").getFirst().wires().equals(List.of(pos(1,0,0))),"+X wins before +Z at equal distance");
        for(boolean reverse:new boolean[]{false,true}){
            nodes.clear();var starts=reverse?List.of(pos(0,2,0),pos(0,0,0)):List.of(pos(0,0,0),pos(0,2,0));
            for(var start:starts)nodes.put(start,source);
            for(int y:new int[]{0,2}){nodes.put(pos(1,y,0),node(WIRE,pos(1,y,0)));nodes.put(pos(2,y,0),sink);}
            var result=compare(source,nodes,128,"source alias insertion order "+reverse);
            check(result.size()==1&&result.getFirst().wires().equals(List.of(pos(1,starts.getFirst().y(),0))),"Source aliases preserve map encounter order and deduplicate shared consumer");
        }
        nodes.clear();nodes.put(pos(10,0,0),source);nodes.put(pos(11,0,0),sink);
        check(compare(source,nodes,128,"alias replaces origin fallback").size()==1,"Mapped source aliases need not include source coordinates");
    }

    private static void terminalAndIdentity(){
        var source=node(SOURCE,pos(0,0,0));var first=node(SINK,pos(1,0,0));var blocked=node(SINK,pos(3,0,0));
        var nodes=new LinkedHashMap<Position,MachineState>();nodes.put(pos(0,0,0),source);nodes.put(pos(1,0,0),first);
        nodes.put(pos(2,0,0),node(WIRE,pos(2,0,0)));nodes.put(pos(3,0,0),blocked);
        var result=compare(source,nodes,128,"terminal consumer");check(result.size()==1&&result.getFirst().consumer()==first,"No traversal through a consumer");
        nodes.put(pos(-1,0,0),first);nodes.put(pos(0,1,0),node(SINK,pos(1,0,0)));
        result=compare(source,nodes,128,"identity deduplication");check(result.size()==2,"Aliases deduplicate by reference; distinct same-coordinate states remain separate");
        first.enabled=false;check(compare(source,nodes,128,"disabled shared consumer").size()==1,"Disabled consumers remain terminal and do not deliver routes");
    }

    private static void longPath(){
        var source=node(SOURCE,pos(0,0,0));var nodes=new LinkedHashMap<Position,MachineState>();nodes.put(pos(0,0,0),source);
        for(int x=1;x<128;x++)nodes.put(pos(x,0,0),node(x==127?SINK:WIRE,pos(x,0,0)));
        check(compare(source,nodes,126,"128-node path below cap").isEmpty(),"Cap excludes final consumer");
        var wires=compare(source,nodes,127,"128-node path exact cap").getFirst().wires();
        check(wires.size()==126&&wires.getFirst().equals(pos(1,0,0))&&wires.getLast().equals(pos(126,0,0)),"Predecessors materialize source-to-consumer order");
        try{wires.add(pos(5,5,5));throw new AssertionError("Route wire list is mutable");}catch(UnsupportedOperationException expected){}
        nodes.clear();check(wires.size()==126,"Published route is independent of later node-map mutation");
    }

    private static void randomized(){
        var random=new Random(0x5052454445434553L);
        for(int trial=0;trial<1200;trial++){
            var source=node(SOURCE,pos(0,0,0));source.enabled=random.nextBoolean();
            var nodes=new LinkedHashMap<Position,MachineState>();var positions=new ArrayList<Position>();
            for(int x=-3;x<=3;x++)for(int y=-1;y<=1;y++)for(int z=-3;z<=3;z++)positions.add(pos(x,y,z));
            Collections.shuffle(positions,random);var consumers=new ArrayList<MachineState>();
            for(var p:positions){
                if(random.nextInt(100)<15)continue;
                int choice=random.nextInt(100);MachineState value;
                if(choice<3&&trial%4!=0)value=source;
                else if(choice<16){
                    if(!consumers.isEmpty()&&random.nextBoolean())value=consumers.get(random.nextInt(consumers.size()));
                    else{value=node(SINK,p);value.enabled=random.nextInt(5)!=0;consumers.add(value);}
                }else{value=node(choice<92?WIRE:"SM_Resonite_Block",p);value.enabled=random.nextInt(8)!=0;}
                nodes.put(p,value);
            }
            // A dense lattice supplies cycles and duplicate frontier entries.
            // Every fourth map omits source aliases to exercise coordinate fallback.
            if(trial%2==0&&trial%4!=0)nodes.put(pos(0,0,0),source);
            Map<Position,MachineState> graph=trial%3==0?new HashMap<>(nodes):nodes;
            for(int cap:new int[]{-1,0,1,2,3,8,32,64,128,Integer.MAX_VALUE})compare(source,graph,cap,"random graph "+trial);
        }
    }
    private static void storagePorts(){
        for(var rotation:com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple.VALUES)for(var face:EnergyStoragePorts.Face.values()){
            var storage=node(EnergyStoragePorts.ID,pos(0,0,0));storage.powerRotation=rotation.index();
            for(var other:EnergyStoragePorts.Face.values())EnergyStoragePorts.set(storage,other,EnergyStoragePorts.Mode.DISABLED);
            var adjacent=pos(0,0,0).neighbors().get(EnergyStoragePorts.worldFace(storage,face));
            var generator=node(SOURCE,adjacent);var nodes=Map.of(pos(0,0,0),storage,adjacent,generator);
            check(ResonantNetwork.routes(generator,nodes,64).isEmpty(),"Disabled rotated storage face rejects input");
            EnergyStoragePorts.set(storage,face,EnergyStoragePorts.Mode.INPUT);
            var input=ResonantNetwork.routes(generator,nodes,64);check(input.size()==1&&input.getFirst().consumer()==storage,"Every native rotation maps each local input to the correct world face");
            EnergyStoragePorts.set(storage,face,EnergyStoragePorts.Mode.OUTPUT);
            check(ResonantNetwork.routes(generator,nodes,64).isEmpty(),"Output face never receives power");
            var sink=node(SINK,adjacent);var output=ResonantNetwork.routes(storage,Map.of(pos(0,0,0),storage,adjacent,sink),64);
            check(output.size()==1&&output.getFirst().consumer()==sink,"Every native rotation maps local output correctly");
            EnergyStoragePorts.set(storage,face,EnergyStoragePorts.Mode.INPUT);
            check(ResonantNetwork.routes(storage,Map.of(pos(0,0,0),storage,adjacent,sink),64).isEmpty(),"Input face never sends power");
        }
        var source=node(SOURCE,pos(0,0,0));var storage=node(EnergyStoragePorts.ID,pos(1,0,1));
        EnergyStoragePorts.set(storage,EnergyStoragePorts.Face.BACK,EnergyStoragePorts.Mode.DISABLED);
        var nodes=new LinkedHashMap<Position,MachineState>();nodes.put(pos(0,0,0),source);nodes.put(pos(1,0,0),node(WIRE,pos(1,0,0)));nodes.put(pos(0,0,1),node(WIRE,pos(0,0,1)));nodes.put(pos(1,0,1),storage);
        var route=ResonantNetwork.routes(source,nodes,64);
        check(route.size()==1&&route.getFirst().wires().equals(List.of(pos(0,0,1))),"An earlier disabled approach cannot hide another enabled input face");
        storage=node(EnergyStoragePorts.ID,pos(1,0,0));EnergyStoragePorts.set(storage,EnergyStoragePorts.Face.RIGHT,EnergyStoragePorts.Mode.OUTPUT);
        var behind=node(SINK,pos(3,0,0));nodes.clear();nodes.put(pos(0,0,0),source);nodes.put(pos(1,0,0),storage);nodes.put(pos(2,0,0),node(WIRE,pos(2,0,0)));nodes.put(pos(3,0,0),behind);
        route=ResonantNetwork.routes(source,nodes,64);
        check(route.size()==1&&route.getFirst().consumer()==storage,"Storage is a terminal endpoint, never a bypass conduit");
        System.out.println("PASS: all six energy storage faces across all64 native rotations, disabled approaches and terminal storage routing.");
    }
    private static void distribution(){
        for(int tick=0;tick<6;tick++){
            var a=node(SOURCE,pos(0,0,0));var b=node(SOURCE,pos(1,0,0));var x=node(SINK,pos(0,0,2));var y=node(SINK,pos(1,0,2));a.energy=b.energy=10;
            var supplies=List.of(new ResonantNetwork.Supply(a,List.of(new Route(x,List.of()),new Route(y,List.of())),10),new ResonantNetwork.Supply(b,List.of(new Route(x,List.of())),10));
            long sent=ResonantNetwork.distribute(supplies,List.of(new ResonantNetwork.Demand(x,10,10,0),new ResonantNetwork.Demand(y,10,10,0)),500,0,tick);
            check(sent==20&&x.energy==10&&y.energy==10&&a.energy==0&&b.energy==0,"Residual reassignment cannot strand an exclusive source behind a flexible source's earlier share");
        }
        // The residual chain must release and reuse a saturated common conduit atomically.
        var a=node(SOURCE,pos(0,0,0));var b=node(SOURCE,pos(1,0,0));var x=node(SINK,pos(0,0,2));var y=node(SINK,pos(1,0,2));a.energy=b.energy=10;var shared=pos(0,0,1);
        var supplies=List.of(new ResonantNetwork.Supply(a,List.of(new Route(x,List.of(shared)),new Route(y,List.of())),10),new ResonantNetwork.Supply(b,List.of(new Route(x,List.of(shared))),10));
        check(ResonantNetwork.distribute(supplies,List.of(new ResonantNetwork.Demand(x,10,10,0),new ResonantNetwork.Demand(y,10,10,0)),10,0,0)==20&&x.energy==10&&y.energy==10,"Signed residual wire usage preserves a saturated path while filling the independent sink");
        var sources=new ArrayList<MachineState>();var offers=new ArrayList<ResonantNetwork.Supply>();var storage=node(EnergyStoragePorts.ID,pos(0,0,2));
        for(int i=0;i<3;i++){var source=node("SM_Rift_Stabilizer",pos(i,0,0));source.energy=1200;sources.add(source);offers.add(new ResonantNetwork.Supply(source,List.of(new Route(storage,List.of(shared),-1,5)),1000));}
        check(ResonantNetwork.distribute(offers,List.of(new ResonantNetwork.Demand(storage,60000,20,1)),500,0,0)==20,"All generators share the storage's one input allowance");
        var sent=sources.stream().mapToInt(s->s.sentThisTick).summaryStatistics();check(sent.getMin()>=6&&sent.getMax()-sent.getMin()<=1,"Three suppliers fairly share one receiving endpoint instead of filling only the first supplier's route");
        var work=node(SINK,pos(0,0,3));storage.energy=0;offers.clear();for(var source:sources){source.energy=2;source.sentThisTick=0;offers.add(new ResonantNetwork.Supply(source,List.of(new Route(work,List.of(shared)),new Route(storage,List.of(shared),-1,5)),1000));}
        check(ResonantNetwork.distribute(offers,List.of(new ResonantNetwork.Demand(work,2,1000,0),new ResonantNetwork.Demand(storage,60000,20,1)),6,0,0)==6&&work.energy==2&&storage.energy==4,"Work gets its current tick and storage gets every surplus RE through a constrained common wire");
        var lone=node(SOURCE,pos(0,0,0));var recipients=List.of(node(SINK,pos(1,0,0)),node(SINK,pos(2,0,0)),node(SINK,pos(3,0,0)));
        for(int tick=0;tick<3;tick++){lone.energy=1;ResonantNetwork.distribute(List.of(new ResonantNetwork.Supply(lone,recipients.stream().map(s->new Route(s,List.of())).toList(),1)),recipients.stream().map(s->new ResonantNetwork.Demand(s,100,100,0)).toList(),500,0,tick);}
        check(recipients.stream().allMatch(s->s.energy==1),"Rotating integer remainders prevent starvation with only one available RE");
        var disabled=node(SINK,pos(1,0,0));disabled.enabled=false;var graph=Map.of(pos(0,0,0),lone,pos(1,0,0),disabled);
        var cached=ResonantNetwork.topology(lone,graph,64);check(cached.routes().size()==1,"Physical topology caches a disabled endpoint for immediate later enable");lone.energy=10;
        var offer=List.of(new ResonantNetwork.Supply(lone,cached.routes(),10));var demand=List.of(new ResonantNetwork.Demand(disabled,10,10,0));
        check(ResonantNetwork.distribute(offer,demand,500,0,0)==0,"Disabled cached sink receives no RE");disabled.enabled=true;check(ResonantNetwork.distribute(offer,demand,500,0,0)==10,"The same cached route supplies a re-enabled sink immediately");
        var chain=new LinkedHashMap<Position,MachineState>();chain.put(pos(0,0,0),lone);for(int i=1;i<=4;i++)chain.put(pos(i,0,0),node(i==4?SINK:WIRE,pos(i,0,0)));
        check(ResonantNetwork.topology(lone,chain,3).limited()&&!ResonantNetwork.topology(lone,chain,4).limited(),"A truncated bounded search is explicitly diagnosed; an exact-cap complete search is not");
        flowOracle();
        System.out.println("POWER_DISTRIBUTION_VERIFICATION_PASSED: residual source reassignment, signed shared-wire capacities, fair sources/sinks, tiny supplies, work/surplus priorities and disabled cached endpoints.");
    }
    private static void flowOracle(){
        var random=new Random(0x504F574552464C4FL);
        for(int trial=0;trial<1000;trial++){
            int producers=1+random.nextInt(4),consumers=1+random.nextInt(4),terminal=producers+consumers+1;var residual=new int[terminal+1][terminal+1];
            var sources=new ArrayList<MachineState>();var sinks=new ArrayList<MachineState>();var demands=new ArrayList<ResonantNetwork.Demand>();var supplies=new ArrayList<ResonantNetwork.Supply>();
            for(int i=0;i<producers;i++){var source=node(SOURCE,pos(i,0,0));source.energy=random.nextInt(21);sources.add(source);residual[0][i+1]=source.energy;}
            for(int j=0;j<consumers;j++){var sink=node(SINK,pos(j,0,2));sinks.add(sink);int capacity=random.nextInt(21);demands.add(new ResonantNetwork.Demand(sink,capacity,capacity,0));residual[producers+j+1][terminal]=capacity;}
            for(int i=0;i<producers;i++){var routes=new ArrayList<Route>();for(int j=0;j<consumers;j++)if(random.nextBoolean()){routes.add(new Route(sinks.get(j),List.of()));residual[i+1][producers+j+1]=1000;}supplies.add(new ResonantNetwork.Supply(sources.get(i),routes,sources.get(i).energy));}
            int expected=0;for(;;){var previous=new int[terminal+1];Arrays.fill(previous,-1);previous[0]=0;var queue=new ArrayDeque<Integer>();queue.add(0);
                while(!queue.isEmpty()&&previous[terminal]<0){int at=queue.remove();for(int next=0;next<=terminal;next++)if(previous[next]<0&&residual[at][next]>0){previous[next]=at;queue.add(next);}}
                if(previous[terminal]<0)break;int amount=Integer.MAX_VALUE;for(int at=terminal;at!=0;at=previous[at])amount=Math.min(amount,residual[previous[at]][at]);
                for(int at=terminal;at!=0;at=previous[at]){residual[previous[at]][at]-=amount;residual[at][previous[at]]+=amount;}expected+=amount;
            }
            long actual=ResonantNetwork.distribute(supplies,demands,500,0,trial);
            check(actual==expected,"Independent max-flow oracle matches fair residual distribution on random bipartite graph "+trial+": "+actual+" / "+expected);
            for(var demand:demands)check(demand.consumer().energy<=demand.target()&&demand.consumer().energy==demand.consumer().receivedThisTick,"Oracle graph preserves all sink limits and incoming accounting");
            for(var supply:supplies)check(supply.source().energy>=0&&supply.source().energy+supply.source().sentThisTick==supply.budget(),"Oracle graph preserves source energy and output accounting");
        }
    }
}
