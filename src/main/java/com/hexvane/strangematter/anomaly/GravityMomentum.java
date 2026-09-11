package com.hexvane.strangematter.anomaly;

import com.hypixel.hytale.server.core.modules.physics.util.PhysicsConstants;
import org.joml.Vector3d;
import java.util.ArrayDeque;

/** Source gravity equations in native blocks/second; creature bob remains independent. */
final class GravityMomentum {
    static final double BOB_AMPLITUDE=.32, BOB_OMEGA=.85;
    static final double SOURCE_TICK=.05, REPORT_LEASE=.25, RESPONSE_HORIZON=1, MAX_VERTICAL_SPEED=80;
    static double bob(double seconds,double phase){return BOB_AMPLITUDE*Math.sin(seconds*BOB_OMEGA+phase);}
    static double force(double distance){return Double.isFinite(distance)&&distance<=8?Math.max(.1,1-Math.max(0,distance)/8):0;}
    static double liftDelta(double vertical,double force){
        if(!Double.isFinite(vertical)||!Double.isFinite(force)||force<=0)return 0;
        force=Math.min(1,force);vertical=boundedVertical(vertical);
        // Minecraft LivingTick: falling v*(1-.9F)+.15F, otherwise v+.1F.
        // Its velocity is blocks/tick; native velocity is blocks/second at 20 ticks/second.
        return vertical<0?-.9*force*vertical+3*force:2*force;
    }
    static double boundedVertical(double value){return Math.max(-MAX_VERTICAL_SPEED,Math.min(MAX_VERTICAL_SPEED,value));}
    static boolean finite(Vector3d value){return value!=null&&Double.isFinite(value.x)&&Double.isFinite(value.y)&&Double.isFinite(value.z);}

    /** Short prediction gaps never reuse the same cached fall speed for every additive impulse. */
    static final class PlayerLift {
        private static final class Pending {
            double amount,issued,coverage;
            Pending(double amount,double issued,double coverage){this.amount=amount;this.issued=issued;this.coverage=coverage;}
        }
        private final ArrayDeque<Pending> pending=new ArrayDeque<>();
        private double predictedVertical,lastReported,gravity=PhysicsConstants.GRAVITY_ACCELERATION;
        private double age=Double.POSITIVE_INFINITY,accumulator,time;
        private boolean initialized;
        void observe(double vertical){
            if(!Double.isFinite(vertical)){reset();return;}
            vertical=boundedVertical(vertical);
            if(!initialized)pending.clear();
            else {
                // Arrival freshness is not an acknowledgement. Consume only the upward response
                // visible beyond ordinary gravity; source-era falling packets retain Add credit.
                double response=Math.max(0,vertical-lastReported+gravity*Math.min(age,RESPONSE_HORIZON));
                while(response>1e-8&&!pending.isEmpty()){
                    var first=pending.getFirst();double used=Math.min(response,first.amount);
                    first.coverage*=1-used/first.amount;first.amount-=used;response-=used;if(first.amount<1e-8)pending.removeFirst();
                }
            }
            double outstanding=0;for(var impulse:pending)outstanding+=impulse.amount;
            // Project the unreflected interval, including its ordinary gravity. Crediting Add
            // alone would leave a persistent downward offset proportional to network delay.
            double covered=0;for(var impulse:pending)covered+=impulse.coverage;
            double horizon=pending.isEmpty()?0:Math.min(Math.min(RESPONSE_HORIZON,covered),Math.max(0,time-pending.getFirst().issued));
            predictedVertical=boundedVertical(vertical+outstanding-gravity*horizon);
            // A fresh native jump/rise/collision-stop still selects the source rising branch;
            // it does not acknowledge unrelated later impulses that remain in flight.
            if(vertical>=0)predictedVertical=Math.max(0,predictedVertical);
            lastReported=vertical;age=0;initialized=true;
        }
        void reset(){initialized=false;age=Double.POSITIVE_INFINITY;accumulator=0;pending.clear();}
        double advance(double dt,double force,boolean inverted){
            if(!Double.isFinite(dt)||dt<=0||!initialized)return 0;
            age+=dt;
            // Expiry stops new sends, but does not forget already sent impulses. A later old
            // report must not repeat their full fall cancellation just because it arrived late.
            if(dt>REPORT_LEASE||age>REPORT_LEASE+1e-8){accumulator=0;time+=dt;return 0;}
            gravity=(inverted?-1:1)*PhysicsConstants.GRAVITY_ACCELERATION;
            accumulator+=dt;int steps=Math.min(2,(int)((accumulator+1e-8)/SOURCE_TICK));
            accumulator-=steps*SOURCE_TICK;
            if(accumulator>=SOURCE_TICK)accumulator%=SOURCE_TICK;
            double total=0;
            for(int i=0;i<steps;i++){
                double delta=liftDelta(predictedVertical,force);total+=delta;predictedVertical+=delta;
                if(i+1<steps)predictedVertical-=gravity*SOURCE_TICK;
            }
            predictedVertical=boundedVertical(predictedVertical-gravity*Math.max(0,dt-Math.max(0,steps-1)*SOURCE_TICK));
            double outstanding=0;for(var impulse:pending)outstanding+=impulse.amount;
            double credit=Math.min(total,Math.max(0,2*MAX_VERTICAL_SPEED-outstanding));
            if(credit>0)pending.addLast(new Pending(credit,time,steps*SOURCE_TICK*credit/total));
            if(pending.size()>32){var old=pending.removeFirst();var next=pending.getFirst();next.amount+=old.amount;next.coverage+=old.coverage;next.issued=old.issued;}
            time+=dt;return total;
        }
    }
    private GravityMomentum(){}
}
