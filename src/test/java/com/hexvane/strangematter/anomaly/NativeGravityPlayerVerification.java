package com.hexvane.strangematter.anomaly;

import com.hexvane.strangematter.equipment.NativePlayerFixture;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.ChangeVelocityType;
import com.hypixel.hytale.protocol.Direction;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.protocol.packets.entities.ChangeVelocity;
import com.hypixel.hytale.protocol.packets.player.ClientMovement;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerInput;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import org.joml.Vector3d;
import java.lang.foreign.MemorySegment;
import java.util.List;

/** Original source equations plus real native input, velocity wire and lifecycle contracts. */
public final class NativeGravityPlayerVerification {
    public static void main(String[] args){verifyMath();System.out.println("GRAVITY_SOURCE_EQUATIONS_PASSED");}
    static void verify(World world,AnomalyService service,Vector3d centre)throws Exception {
        verifyMath();var position=new Vector3d(centre).add(2,0,0);double force=.75;
        double[][] examples={
                    {0,-8,-4,0,0}, {0,-8,4,0,0}, {4,-8,0,0,0}, {-4,-8,0,0,0},
                    {4,-8,-4,Math.PI/2,Math.PI/3}, {0,-8,-4,0,-Math.PI/3},
                    {0,8.4,-6,0,0}, {0,0,-1.2,0,0}, {0,0,0,0,Math.PI/2}};
        for(int i=0;i<examples.length;i++){
            try(var subject=NativePlayerFixture.create(world,"GravityNativeControls"+i,position)){
                var store=subject.store();subject.player().handleClientReady(false);service.tick(world,.05);
                var settings=store.getComponent(subject.ref(),MovementManager.getComponentType()).getSettings();var initialSettings=settings.clone();
                var e=examples[i];var reported=new Vector3d(e[0],e[1],e[2]);
                var states=store.getComponent(subject.ref(),MovementStatesComponent.getComponentType()).getMovementStates().clone();
                states.flying=false;states.walking=i<6;states.sprinting=i==6;states.jumping=i==6;
                states.crouching=i==7;states.idle=i==8;states.horizontalIdle=i==8;states.onGround=false;
                int start=forces(subject).size();packet(subject,reported,(float)e[3],(float)e[4],states,false);
                store.tick(.05f);service.tick(world,.05);
                var output=forces(subject);require(output.size()==start+1,"Exactly one source-rate force is emitted for a fresh movement frame "+i);
                var command=output.getLast();
                require(command.changeType==ChangeVelocityType.Add&&command.config==null&&command.x==0&&command.z==0,
                        "Forward, backward, strafe, diagonal, sprint, jump, crouch and idle all keep native horizontal control "+i);
                close(command.y,GravityMomentum.liftDelta(reported.y,force),1e-5,"Head pitch and movement keys never alter source vertical lift "+i);
                require(store.getComponent(subject.ref(),Velocity.getComponentType()).getClientVelocity().equals(reported),"The actual native client velocity report remains untouched "+i);
                var actual=store.getComponent(subject.ref(),MovementStatesComponent.getComponentType()).getMovementStates();
                require(actual.sprinting==states.sprinting&&actual.crouching==states.crouching&&actual.jumping==states.jumping,"Native sprint, crouch and jump flags remain unchanged "+i);
                var bytes=MemorySegment.ofArray(new byte[command.computeSize()]);command.serialize(bytes,0);
                require(command.equals(ChangeVelocity.toObject(bytes)),"The vertical-only Add survives actual wire serialization "+i);
                require(settings.equals(initialSettings),"Gravity does not replace any native movement tuning");
            }
        }
        try(var subject=NativePlayerFixture.create(world,"GravityDelayedReports",position)){
            var store=subject.store();subject.player().handleClientReady(false);service.tick(world,.05);
            var input=store.getComponent(subject.ref(),PlayerInput.getComponentType());
            packet(subject,new Vector3d(3,-8,-4),0,(float)(Math.PI/3),new MovementStates(),true);
            store.tick(.05f);service.tick(world,.05);
            close(forces(subject).getLast().y,GravityMomentum.liftDelta(-8,force),1e-5,"Optional wish force is not converted into a second controller");
            require(input.getMovementUpdateQueue().isEmpty(),"The native input consumer, rather than the anomaly, consumes the full movement queue");

            // Missing reports may predict briefly, but may not repeatedly apply a stale fall sample.
            double first=forces(subject).getLast().y;
            packet(subject,new Vector3d(3,-8,-4),0,0,new MovementStates(),false);store.tick(.05f);service.tick(world,.05);
            require(forces(subject).getLast().y<first*.8,"A newly arriving old-state report cannot replay the full in-flight fall cancellation");
            double second=forces(subject).getLast().y;store.tick(.05f);service.tick(world,.05);
            require(forces(subject).getLast().y<second,"A missing packet predicts from the already credited falling correction");
            for(int i=0;i<8;i++){store.tick(.05f);service.tick(world,.05);}
            int expired=forces(subject).size();for(int i=0;i<8;i++){store.tick(.05f);service.tick(world,.05);}
            require(forces(subject).size()==expired,"A stalled client stream does not receive endless additive impulses");
            packet(subject,new Vector3d(0,0,0),0,0,new MovementStates(),false);store.tick(.05f);service.tick(world,.05);
            close(forces(subject).getLast().y,2*force,1e-5,"A fresh collision-stop report replaces the old falling estimate");
            require(forces(subject).size()==expired+1,"Fresh native movement resumes the expired lift lease");

            // Leaving must not write the old XYZ vector over a same-frame turn, jump or collision.
            int beforeExit=subject.packets().ofType(ChangeVelocity.class).size();
            input.queue(new PlayerInput.RelativeMovement(7,0,0));input.queue(new PlayerInput.SetClientVelocity(new Vector3d(-6,8,3)));
            store.tick(.05f);service.tick(world,.05);
            require(subject.packets().ofType(ChangeVelocity.class).size()==beforeExit,"Leaving the field sends no stale full-vector restore packet");
            require(store.getComponent(subject.ref(),Velocity.getComponentType()).getClientVelocity().equals(new Vector3d(-6,8,3)),"Native post-exit movement remains intact");
        }
        // Without a velocity field, actual relative-position updates can still supply vertical speed.
        try(var subject=NativePlayerFixture.create(world,"GravityPositionReports",new Vector3d(centre).add(8.1,0,0))){
            var store=subject.store();service.tick(world,.05);store.tick(.05f);
            var input=store.getComponent(subject.ref(),PlayerInput.getComponentType());
            input.queue(new PlayerInput.RelativeMovement(-.3,-.2,0));store.tick(.05f);service.tick(world,.05);
            double f=GravityMomentum.force(Math.sqrt(7.8*7.8+.04));
            close(forces(subject).getLast().y,GravityMomentum.liftDelta(-2,f),1e-5,"Position-only sample measures vertical motion across its actual two-tick interval");
        }
        // Native implementation explicitly matches the client's Add semantics and preserves X/Z.
        var store=world.getEntityStore().getStore();var npc=NPCPlugin.get().spawnNPC(store,"Cow",null,new Vector3d(centre).add(12,0,0),new Rotation3f()).first();
        try{
            var controller=store.getComponent(npc,NPCEntity.getComponentType()).getRole().getActiveMotionController();
            controller.setKnockbackScale(1);controller.clearExternalForces();controller.setVelocity(new Vector3d(7,-8,-3),null,false);
            controller.addVelocity(new Vector3d(0,GravityMomentum.liftDelta(-8,.75),0),null);
            require(controller.getExternalVelocity().x==7&&controller.getExternalVelocity().z==-3,"Native Add leaves both existing horizontal velocity components exactly intact");
            close(controller.getExternalVelocity().y,-8+GravityMomentum.liftDelta(-8,.75),1e-9,"Native vertical Add has no horizontal resistance multiplier");
        }finally{if(npc.isValid())store.removeEntity(npc,RemoveReason.REMOVE);}
        System.out.println("NATIVE_GRAVITY_PLAYER_SOURCE_PARITY_PASSED: original distance/vertical equations, actual ordinary and optional-wish packets, unchanged native controls, jump/pitch/collision reports, bounded prediction/expiry, additive wire/engine semantics and no exit reset.");
    }
    private static List<ChangeVelocity> forces(NativePlayerFixture f){return f.packets().ofType(ChangeVelocity.class).stream().filter(p->p.changeType==ChangeVelocityType.Add&&p.config==null&&p.x==0&&p.z==0).toList();}
    private static void packet(NativePlayerFixture f,Vector3d velocity,float yaw,float pitch,MovementStates states,boolean wish){
        var packet=new ClientMovement();packet.velocity=new com.hypixel.hytale.protocol.Vector3d(velocity.x,velocity.y,velocity.z);
        packet.lookOrientation=new Direction(yaw,pitch,0);packet.movementStates=states;
        if(wish)packet.wishMovement=new com.hypixel.hytale.protocol.Position(0,0,-.1);
        var bytes=MemorySegment.ofArray(new byte[packet.computeSize()]);packet.serialize(bytes,0);
        f.packets().handle(ClientMovement.toObject(bytes));f.world().consumeTaskQueue();
        require(f.store().getComponent(f.ref(),PlayerInput.getComponentType()).getMovementUpdateQueue().stream().anyMatch(PlayerInput.SetClientVelocity.class::isInstance),"Actual GamePacketHandler routes the movement report before gravity observes it");
    }
    private static void verifyMath(){
        close(GravityMomentum.force(0),1,0,"Centre force");close(GravityMomentum.force(4),.5,0,"Half radius force");
        close(GravityMomentum.force(7.9),.1,0,"Minimum outer force");close(GravityMomentum.force(8.1),0,0,"Outside sphere");
        for(double f:new double[]{.1,.25,.5,1})for(double sourceV:new double[]{-3,-.5,-.01,0,.1,.42,2}){
            double expected=sourceV<0?sourceV*(1-.9*f)+.15*f:sourceV+.1*f;
            close(sourceV+GravityMomentum.liftDelta(sourceV*20,f)/20,expected,1e-12,"Exact Minecraft LivingTick equation after unit conversion");
        }
        var predicted=new GravityMomentum.PlayerLift();predicted.observe(-20);
        close(predicted.advance(.05,.5,false),10.5,1e-10,"First falling correction");
        close(predicted.advance(.05,.5,false),6.495,1e-10,"Predict the already applied correction then ordinary32 gravity before the next tick");
        var source=new GravityMomentum.PlayerLift();source.observe(-20);double combined=source.advance(.1,.5,false);
        close(combined,16.995,1e-10,"At most two original source steps combine into one packet after a short frame");
        for(int i=0;i<10;i++)source.advance(.05,.5,false);
        close(source.advance(.05,.5,false),0,0,"No force after stale report expiry");
        source.observe(0);close(source.advance(1,.5,false),0,0,"Long server stalls do not burst catch-up forces");
        source.observe(Double.NaN);close(source.advance(.05,1,false),0,0,"Invalid reports invalidate prediction");
        var inverted=new GravityMomentum.PlayerLift();inverted.observe(-20);inverted.advance(.05,.5,true);
        close(inverted.advance(.05,.5,true),5.055,1e-10,"Prediction retains the native inverted gravity sign");
        for(boolean declining:new boolean[]{false,true}){
            var delayed=new GravityMomentum.PlayerLift();double first=0;
            for(int i=0;i<5;i++){
                delayed.observe(-20-(declining?1.6*i:0));double impulse=delayed.advance(.05,1,false);
                if(i==0)first=impulse;else require(impulse<first*.5,"Repeated or still-declining pre-correction reports cannot repeat the large first cancellation");
            }
        }
        var gap=new GravityMomentum.PlayerLift();gap.observe(-20);double initial=gap.advance(.05,1,false);
        close(gap.advance(.30,1,false),0,0,"A300ms stream gap issues no catch-up packet");gap.observe(-20);
        require(gap.advance(.05,1,false)<initial*.7,"Credit survives a300ms gap followed by the same old falling snapshot");
        gap.observe(8.4);close(gap.advance(.05,.75,false),1.5,1e-10,"Fresh positive jump feedback retains the unchanged source rising branch");
        for(int delay:new int[]{0,5,10}){closedLoop(delay,.75,false);closedLoop(delay,.5,false);closedLoop(delay,.5,true);}
        var fast=new GravityMomentum.PlayerLift();fast.observe(0);int emitted=0;
        for(int i=0;i<20;i++){fast.observe(0);if(fast.advance(.01,1,false)>0)emitted++;}
        require(emitted==4,"A100Hz simulation cannot emit more than the original20Hz impulse rate");
        require(GravityMomentum.liftDelta(-Double.MAX_VALUE,1)<=75&&GravityMomentum.liftDelta(Double.NaN,1)==0,"Corrupt/extreme velocities cannot produce an unbounded or nonfinite packet");
    }
    private record Delayed(int tick,double value){}
    private static void closedLoop(int delay,double force,boolean gap){
        var field=new GravityMomentum.PlayerLift();var commands=new java.util.ArrayDeque<Delayed>();var reports=new java.util.ArrayDeque<Delayed>();
        double velocity=-20,first=0,maxImpulse=0,lateMin=Double.POSITIVE_INFINITY,lateMax=Double.NEGATIVE_INFINITY;
        for(int tick=0;tick<400;tick++){
            while(!commands.isEmpty()&&commands.getFirst().tick<=tick)velocity+=commands.removeFirst().value;
            // Both the Add and its response cross the network; no fake acknowledgement exists.
            if(!gap||tick<5||tick>12)reports.addLast(new Delayed(tick+delay,velocity));
            while(!reports.isEmpty()&&reports.getFirst().tick<=tick)field.observe(reports.removeFirst().value);
            double impulse=field.advance(.05,force,false);
            if(impulse>0&&first==0)first=impulse;maxImpulse=Math.max(maxImpulse,impulse);
            if(impulse>0){if(delay==0)velocity+=impulse;else commands.addLast(new Delayed(tick+delay,impulse));}
            velocity-=32*.05;
            require(Double.isFinite(velocity)&&Math.abs(velocity)<65,"Closed-loop delayed client velocity remains bounded at delay="+delay+", tick="+tick+", v="+velocity);
            if(tick>=300){lateMin=Math.min(lateMin,velocity);lateMax=Math.max(lateMax,velocity);}
        }
        require(maxImpulse<=first+1e-8,"The delayed loop never repeats a larger fall cancellation than its initial sample at delay="+delay);
        require(lateMin>-10&&lateMax<10,"Delayed feedback returns to bounded source lift instead of a persistent network-delay falling offset: delay="+delay+", force="+force+", gap="+gap+", range="+lateMin+".."+lateMax);
        if(force==.5){
            // No delay matches the exact source equilibrium. Delayed response projection retains
            // a conservative one native gravity tick offset, independent of the size of the delay.
            double equilibrium=(3*force-1.6)/(.9*force);
            require(lateMax-lateMin<.01,"A reporting gap must settle, not preserve the earlier -6.29..4.51 feedback oscillation: "+lateMin+".."+lateMax);
            require(lateMin>=equilibrium-1.601&&lateMax<=equilibrium+.001,"Settled lift remains within one native gravity tick of the exact source equilibrium");
            if(delay==0)close((lateMin+lateMax)/2,equilibrium,.001,"Immediate feedback matches exact source equilibrium");
        }
        System.out.println("GRAVITY_DELAY_LOOP delay="+delay+", force="+force+", gap="+gap+", maxImpulse="+maxImpulse+", finalRange="+lateMin+".."+lateMax);
    }
    private static void close(double actual,double expected,double tolerance,String message){require(Math.abs(actual-expected)<=tolerance,message+": "+actual+" vs "+expected);}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private NativeGravityPlayerVerification(){}
}
