package com.hexvane.strangematter.machine;

import java.util.*;

/** Bounded six-face network walk. Distance lowers throughput without destroying energy. */
public final class ResonantNetwork {
    public record Position(int x,int y,int z) {
        public List<Position> neighbors(){return List.of(new Position(x+1,y,z),new Position(x-1,y,z),new Position(x,y+1,z),new Position(x,y-1,z),new Position(x,y,z+1),new Position(x,y,z-1));}
    }
    public record Route(MachineState consumer,List<Position> wires,int sourceFace,int consumerFace) {
        public Route(MachineState consumer,List<Position> wires){this(consumer,wires,-1,-1);}
    }
    public static List<Route> routes(MachineState source,Map<Position,MachineState> nodes,int max) {
        return search(source,nodes,max,false).routes;
    }
    /** Cache physical endpoints even while disabled; demand and enabled state are checked live. */
    public static List<Route> topologyRoutes(MachineState source,Map<Position,MachineState> nodes,int max) {
        return topology(source,nodes,max).routes;
    }
    public record Search(List<Route> routes,boolean limited){}
    public static Search topology(MachineState source,Map<Position,MachineState> nodes,int max){return search(source,nodes,max,true);}
    private static Search search(MachineState source,Map<Position,MachineState> nodes,int max,boolean physical) {
        record Visit(Position position,Visit previous,int wireCount,int sourceFace,int arrivalFace){
            List<Position> wires(){
                if(wireCount==0)return List.of();
                var path=new Position[wireCount];int index=wireCount;
                for(var wire=previous;wire!=null;wire=wire.previous)path[--index]=wire.position;
                return List.of(path);
            }
        }
        Queue<Visit> todo=new ArrayDeque<>();Set<Position> seen=new HashSet<>(),counted=new HashSet<>();List<Route> result=new ArrayList<>();
        var starts=nodes.entrySet().stream().filter(e->e.getValue()==source).map(Map.Entry::getKey).toList();
        if(starts.isEmpty())starts=List.of(new Position(source.x,source.y,source.z));
        seen.addAll(starts);for(var start:starts){var neighbors=start.neighbors();for(int face=0;face<neighbors.size();face++)
            if(EnergyStoragePorts.permits(source,face,EnergyStoragePorts.Mode.OUTPUT))todo.add(new Visit(neighbors.get(face),null,0,face,face^1));}
        int visited=0;
        while(!todo.isEmpty()&&visited<max){
            var v=todo.remove();
            var node=nodes.get(v.position);
            boolean directional=node!=null&&EnergyStoragePorts.storage(node.id);
            if(!directional&&!seen.add(v.position)||directional&&starts.contains(v.position))continue;
            if(node==null||node==source||!node.enabled&&!(physical&&MachineService.consumesPower(node.id)))continue;
            if(counted.add(v.position))visited++;
            if(MachineService.consumesPower(node.id)){
                // A rejected approach must not hide a later route into a different, enabled face.
                if(EnergyStoragePorts.permits(node,v.arrivalFace,EnergyStoragePorts.Mode.INPUT)&&result.stream().noneMatch(r->r.consumer()==node))
                    result.add(new Route(node,v.wires(),v.sourceFace,v.arrivalFace));
                continue;
            }
            if(!node.id.equals("SM_Resonant_Conduit"))continue;
            // A queued child shares this accepted conduit predecessor. Copy a
            // complete immutable path only when a distinct consumer is reached.
            var neighbors=v.position.neighbors();for(int face=0;face<neighbors.size();face++)todo.add(new Visit(neighbors.get(face),v,v.wireCount+1,v.sourceFace,face^1));
        }
        var origins=starts;
        boolean limited=visited>=max&&todo.stream().anyMatch(v->{var node=nodes.get(v.position);return node!=null&&!counted.contains(v.position)&&!origins.contains(v.position)&&(node.enabled||physical&&MachineService.consumesPower(node.id));});
        return new Search(List.copyOf(result),limited);
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
    public record Supply(MachineState source,List<Route> routes,int budget){}
    /** Target is an absolute reserve; maxInput is shared across every supplier and priority. */
    public record Demand(MachineState consumer,int target,int maxInput,int priority){}
    private record Link(Supply supply,Route route,Demand demand,int rate){}
    /** Progressive fair shares preserve wire, endpoint and path limits across all suppliers. */
    public static long distribute(List<Supply> supplies,List<Demand> demands,int wireRate,double distancePenalty,int tick){
        var outgoing=new IdentityHashMap<MachineState,Integer>();var incoming=new IdentityHashMap<MachineState,Integer>();
        var used=new HashMap<Position,Integer>();var pathUsed=new IdentityHashMap<Route,Integer>();long total=0;
        for(int priority:demands.stream().mapToInt(Demand::priority).distinct().sorted().toArray()){
            var wanted=new IdentityHashMap<MachineState,Demand>();for(var demand:demands)if(demand.priority==priority)wanted.put(demand.consumer,demand);
            var links=new ArrayList<Link>();
            for(var supply:supplies)for(var route:supply.routes){var demand=wanted.get(route.consumer);if(demand==null)continue;
                int rate=route.wires.isEmpty()?supply.budget:Math.max(1,(int)(wireRate*Math.max(.1,1-(route.wires.size()+1)*distancePenalty)));
                links.add(new Link(supply,route,demand,rate));
            }
            if(links.isEmpty())continue;
            Collections.rotate(links,-Math.floorMod(tick,links.size()));
            var flow=new IdentityHashMap<Link,Integer>();
            // Each round fills at least one constraint or distributes its integer remainder.
            // The hard bound keeps custom large networks from monopolizing the world tick.
            for(int round=0;round<64;round++){
                var active=new ArrayList<Link>();var sourceCount=new IdentityHashMap<MachineState,Integer>();var sinkCount=new IdentityHashMap<MachineState,Integer>();var wireCount=new HashMap<Position,Integer>();
                for(var link:links){if(available(link,outgoing,incoming,used,pathUsed,wireRate)<=0)continue;active.add(link);sourceCount.merge(link.supply.source,1,Integer::sum);sinkCount.merge(link.demand.consumer,1,Integer::sum);for(var wire:link.route.wires)wireCount.merge(wire,1,Integer::sum);}
                if(active.isEmpty())break;
                var quotas=new IdentityHashMap<Link,Integer>();
                for(var link:active){var source=link.supply.source;var sink=link.demand.consumer;
                    int share=Math.max(1,Math.min(source.energy,link.supply.budget-outgoing.getOrDefault(source,0))/sourceCount.get(source));
                    share=Math.min(share,Math.max(1,Math.min(link.demand.target-sink.energy,link.demand.maxInput-incoming.getOrDefault(sink,0))/sinkCount.get(sink)));
                    for(var wire:link.route.wires)share=Math.min(share,Math.max(1,(wireRate-used.getOrDefault(wire,0))/wireCount.get(wire)));
                    quotas.put(link,share);
                }
                boolean progressed=false;
                for(var link:active){var source=link.supply.source;var sink=link.demand.consumer;int share=quotas.get(link);
                    int amount=Math.min(share,available(link,outgoing,incoming,used,pathUsed,wireRate));if(amount<=0)continue;
                    source.energy-=amount;sink.energy+=amount;source.sentThisTick+=amount;sink.receivedThisTick+=amount;
                    outgoing.merge(source,amount,Integer::sum);incoming.merge(sink,amount,Integer::sum);pathUsed.merge(link.route,amount,Integer::sum);
                    flow.merge(link,amount,Integer::sum);
                    for(var wire:link.route.wires)used.merge(wire,amount,Integer::sum);
                    if(EnergyStoragePorts.storage(source.id))source.active=true;if(EnergyStoragePorts.storage(sink.id))sink.active=true;
                    total+=amount;progressed=true;
                }
                if(!progressed)break;
            }
            // Reassign this priority's committed shares when a flexible supplier occupied the
            // only route available to another supplier. Higher-priority work is never reversed.
            var residual=new Residual(links,flow,outgoing,incoming,used,pathUsed,wireRate);
            for(int round=0;round<64;round++){
                var augmentation=residual.find();if(augmentation==null)break;
                int amount=augmentation.amount;var source=augmentation.source.source;var sink=augmentation.sink.consumer;
                // Intermediate sources/sinks have equal incoming and outgoing adjustments.
                // Only these endpoints change stored energy; shared wire changes are signed.
                source.energy-=amount;sink.energy+=amount;source.sentThisTick+=amount;sink.receivedThisTick+=amount;
                outgoing.merge(source,amount,Integer::sum);incoming.merge(sink,amount,Integer::sum);
                for(var step:augmentation.steps){int change=step.forward?amount:-amount;flow.merge(step.link,change,Integer::sum);pathUsed.merge(step.link.route,change,Integer::sum);for(var wire:step.link.route.wires)used.merge(wire,change,Integer::sum);}
                if(EnergyStoragePorts.storage(source.id))source.active=true;if(EnergyStoragePorts.storage(sink.id))sink.active=true;
                total+=amount;
            }
        }
        return total;
    }
    private record Step(Link link,boolean forward){}
    private record Augmentation(Supply source,Demand sink,List<Step> steps,int amount){}
    /** Bounded alternating residual paths, with whole-path shared-wire feasibility. */
    private static final class Residual {
        final List<Link> links;final Map<Link,Integer> flow;final Map<MachineState,Integer> outgoing,incoming;final Map<Position,Integer> used;final Map<Route,Integer> pathUsed;final int wireRate;
        final Map<MachineState,List<Link>> from=new IdentityHashMap<>(),to=new IdentityHashMap<>();
        final Set<MachineState> sources=Collections.newSetFromMap(new IdentityHashMap<>()),sinks=Collections.newSetFromMap(new IdentityHashMap<>());
        Supply start;int visits;
        Residual(List<Link> links,Map<Link,Integer> flow,Map<MachineState,Integer> outgoing,Map<MachineState,Integer> incoming,Map<Position,Integer> used,Map<Route,Integer> pathUsed,int wireRate){
            this.links=links;this.flow=flow;this.outgoing=outgoing;this.incoming=incoming;this.used=used;this.pathUsed=pathUsed;this.wireRate=wireRate;
            for(var link:links){from.computeIfAbsent(link.supply.source,k->new ArrayList<>()).add(link);to.computeIfAbsent(link.demand.consumer,k->new ArrayList<>()).add(link);}
        }
        Augmentation find(){
            visits=0;var attempted=Collections.newSetFromMap(new IdentityHashMap<MachineState,Boolean>());
            for(var link:links){start=link.supply;if(!attempted.add(start.source)||Math.min(start.source.energy,start.budget-outgoing.getOrDefault(start.source,0))<=0)continue;
                sources.clear();sinks.clear();sources.add(start.source);var found=visit(start,new ArrayList<>());if(found!=null)return found;if(visits>=4096)break;}
            return null;
        }
        Augmentation visit(Supply supply,List<Step> steps){
            if(steps.size()>=64)return null;
            for(var link:from.getOrDefault(supply.source,List.of())){
                if(++visits>4096)return null;var sink=link.demand.consumer;
                if(!allowed(link)||link.rate-pathUsed.getOrDefault(link.route,0)<=0||!sinks.add(sink))continue;
                steps.add(new Step(link,true));
                try{
                    if(sink!=start.source){int amount=amount(link.demand,steps);if(amount>0)return new Augmentation(start,link.demand,List.copyOf(steps),amount);}
                    for(var reverse:to.getOrDefault(sink,List.of())){
                        if(++visits>4096)return null;
                        if(flow.getOrDefault(reverse,0)<=0||!sources.add(reverse.supply.source))continue;
                        steps.add(new Step(reverse,false));try{var found=visit(reverse.supply,steps);if(found!=null)return found;}finally{steps.removeLast();sources.remove(reverse.supply.source);}
                    }
                }finally{steps.removeLast();sinks.remove(sink);}
            }
            return null;
        }
        int amount(Demand sink,List<Step> steps){
            int amount=Math.min(Math.min(start.source.energy,start.budget-outgoing.getOrDefault(start.source,0)),Math.min(sink.target-sink.consumer.energy,sink.maxInput-incoming.getOrDefault(sink.consumer,0)));
            if(amount<=0)return 0;var wireDelta=new HashMap<Position,Integer>();
            for(var step:steps){amount=Math.min(amount,step.forward?step.link.rate-pathUsed.getOrDefault(step.link.route,0):flow.getOrDefault(step.link,0));for(var wire:step.link.route.wires)wireDelta.merge(wire,step.forward?1:-1,Integer::sum);}
            for(var change:wireDelta.entrySet())if(change.getValue()>0)amount=Math.min(amount,(wireRate-used.getOrDefault(change.getKey(),0))/change.getValue());
            return Math.max(0,amount);
        }
    }
    private static boolean allowed(Link link){var source=link.supply.source;var sink=link.demand.consumer;return source.enabled&&sink.enabled&&source!=sink&&EnergyStoragePorts.permits(source,link.route.sourceFace,EnergyStoragePorts.Mode.OUTPUT)&&EnergyStoragePorts.permits(sink,link.route.consumerFace,EnergyStoragePorts.Mode.INPUT);}
    private static int available(Link link,Map<MachineState,Integer> outgoing,Map<MachineState,Integer> incoming,Map<Position,Integer> used,Map<Route,Integer> pathUsed,int wireRate){
        var source=link.supply.source;var sink=link.demand.consumer;
        if(!allowed(link))return 0;
        int amount=Math.min(Math.min(source.energy,link.supply.budget-outgoing.getOrDefault(source,0)),Math.min(link.demand.target-sink.energy,link.demand.maxInput-incoming.getOrDefault(sink,0)));
        amount=Math.min(amount,link.rate-pathUsed.getOrDefault(link.route,0));for(var wire:link.route.wires)amount=Math.min(amount,wireRate-used.getOrDefault(wire,0));
        return Math.max(0,amount);
    }
}
