package com.hexvane.strangematter.machine;

import java.util.*;
import com.hexvane.strangematter.machine.ResonantNetwork.Position;
import com.hexvane.strangematter.machine.ResonantNetwork.Route;

/** Differential checks against the previous path-copying BFS, without a native world. */
public final class ResonantNetworkVerification {
    private static final String WIRE="SM_Resonant_Conduit",SOURCE="SM_Resonant_Burner",SINK="SM_Reality_Forge";

    public static void main(String[] args){verify();}

    public static void verify(){
        boundaries();aliasesAndTieOrder();terminalAndIdentity();longPath();randomized();
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
}
