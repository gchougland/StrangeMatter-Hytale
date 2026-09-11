package com.hexvane.strangematter.machine;

import java.util.*;

/** Bounded six-face network walk. Distance lowers throughput without destroying energy. */
public final class ResonantNetwork {
    public record Position(int x,int y,int z) {
        public List<Position> neighbors(){return List.of(new Position(x+1,y,z),new Position(x-1,y,z),new Position(x,y+1,z),new Position(x,y-1,z),new Position(x,y,z+1),new Position(x,y,z-1));}
    }
    public record Route(MachineState consumer,List<Position> wires) {}
    public static List<Route> routes(MachineState source,Map<Position,MachineState> nodes,int max) {
        record Visit(Position position,Visit previous,int wireCount){
            List<Position> wires(){
                if(wireCount==0)return List.of();
                var path=new Position[wireCount];int index=wireCount;
                for(var wire=previous;wire!=null;wire=wire.previous)path[--index]=wire.position;
                return List.of(path);
            }
        }
        Queue<Visit> todo=new ArrayDeque<>();Set<Position> seen=new HashSet<>();List<Route> result=new ArrayList<>();
        var starts=nodes.entrySet().stream().filter(e->e.getValue()==source).map(Map.Entry::getKey).toList();
        if(starts.isEmpty())starts=List.of(new Position(source.x,source.y,source.z));
        seen.addAll(starts);for(var start:starts)for(var n:start.neighbors())todo.add(new Visit(n,null,0));
        int visited=0;
        while(!todo.isEmpty()&&visited<max){
            var v=todo.remove();if(!seen.add(v.position))continue;
            var node=nodes.get(v.position);if(node==null||!node.enabled)continue;visited++;
            if(MachineService.consumesPower(node.id)){if(result.stream().noneMatch(r->r.consumer()==node))result.add(new Route(node,v.wires()));continue;}
            if(!node.id.equals("SM_Resonant_Conduit"))continue;
            // A queued child shares this accepted conduit predecessor. Copy a
            // complete immutable path only when a distinct consumer is reached.
            for(var n:v.position.neighbors())todo.add(new Visit(n,v,v.wireCount+1));
        }
        return result;
    }
    public static int transfer(MachineState source,Route route,int budget,int capacity,int wireRate,double distancePenalty,Map<Position,Integer> used) {
        int distance=route.wires.size()+1;
        int pathRate=route.wires.isEmpty()?budget:Math.max(1,(int)(wireRate*Math.max(.1,1-distance*distancePenalty)));
        int available=Math.min(Math.min(source.energy,budget),pathRate);
        for(var wire:route.wires)available=Math.min(available,Math.max(0,wireRate-used.getOrDefault(wire,0)));
        int accepted=Math.min(Math.max(0,capacity-route.consumer.energy),available);
        if(accepted<=0)return 0;
        source.energy-=accepted;route.consumer.energy+=accepted;
        for(var wire:route.wires)used.merge(wire,accepted,Integer::sum);
        return accepted;
    }
}
