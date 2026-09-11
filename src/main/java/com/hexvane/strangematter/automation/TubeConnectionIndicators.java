package com.hexvane.strangematter.automation;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;
import java.util.*;
import java.util.function.Predicate;
import static com.hexvane.strangematter.automation.TubeEndpoints.*;

/** Short ordered light pulses show configured flow even when every inventory is empty. */
final class TubeConnectionIndicators {
    static final int MAX_CONNECTIONS=64,MAX_PER_PLAYER=24;
    static final double RANGE=20,INTERVAL=.125,PERIOD=1;
    record Connection(TubeService.Node node,int face,List<Ref<EntityStore>> viewers){}
    private List<Connection> visible=List.of();
    private double refresh,emission;
    void invalidate(){refresh=0;}

    void tick(World world,double dt,double clock,Collection<TubeService.Node> nodes,Predicate<Position> endpoint){
        if((refresh-=dt)<=0){refresh=1;select(world,nodes,endpoint);}
        if((emission-=dt)>0)return;
        emission=INTERVAL;
        var store=world.getEntityStore().getStore();
        for(var c:visible){
            var n=c.node;
            if(!n.ref().isValid()||!TubeService.tube(world,n.position())||TubeService.tube(world,n.position().offset(c.face)))continue;
            var mode=n.component().face(c.face).mode;
            if(mode==TubeConfiguration.Mode.OFF)continue;
            var position=position(n.position(),c.face,mode,clock);
            // Recheck range on each pulse; the cached audience never receives distant or transferred-world cues.
            var viewers=c.viewers.stream().filter(r->r.isValid()&&r.getStore()==store).filter(r->{
                var p=store.getComponent(r,PlayerRef.getComponentType());
                return p!=null&&p.getTransform().getPosition().distanceSquared(position)<=RANGE*RANGE;
            }).toList();
            if(!viewers.isEmpty())ParticleUtil.spawnParticleEffect(system(mode),position,viewers,store);
        }
    }
    private void select(World world,Collection<TubeService.Node> nodes,Predicate<Position> endpoint){
        var selected=new LinkedHashMap<String,Connection>();
        for(var viewer:world.getPlayerRefs()){
            var ref=viewer.getReference();if(ref==null||!ref.isValid())continue;
            var eye=viewer.getTransform().getPosition();
            var nearby=nodes.stream().filter(n->n.ref().isValid()&&TubeService.center(n.position()).distanceSquared(eye)<=RANGE*RANGE)
                .sorted(Comparator.comparingDouble(n->TubeService.center(n.position()).distanceSquared(eye))).toList();
            int budget=MAX_PER_PLAYER;
            for(var n:nearby){
                for(int face=0;face<6&&budget>0;face++){
                    if(n.component().face(face).mode==TubeConfiguration.Mode.OFF||TubeService.tube(world,n.position().offset(face))||!endpoint.test(n.position().offset(face)))continue;
                    String key=TubeService.key(n.position())+":"+face;
                    var connection=selected.get(key);
                    if(connection==null){
                        if(selected.size()>=MAX_CONNECTIONS)continue;
                        connection=new Connection(n,face,new ArrayList<>());selected.put(key,connection);
                    }
                    connection.viewers.add(ref);budget--;
                }
                if(budget<=0)break;
            }
        }
        visible=List.copyOf(selected.values());
    }
    static String system(TubeConfiguration.Mode mode){return mode==TubeConfiguration.Mode.EXTRACT?"SM_Tube_Take":"SM_Tube_Send";}
    static Vector3d position(Position p,int face,TubeConfiguration.Mode mode,double clock){
        double phase=clock/PERIOD-Math.floor(clock/PERIOD);
        double radial=mode==TubeConfiguration.Mode.EXTRACT?.46-.36*phase:.10+.36*phase;
        return TubeService.center(p).add(DX[face]*radial,DY[face]*radial,DZ[face]*radial);
    }
    int selectedCount(){return visible.size();}
}
