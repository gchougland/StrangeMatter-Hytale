package com.hexvane.strangematter.anomaly;

import com.hypixel.hytale.server.core.modules.physics.util.PhysicsConstants;
import org.joml.Vector3d;
import java.util.Collection;

/** Bounded velocity integration shared by the native field and its regression. */
final class GravityMomentum {
    static final double DAMPING=.18, BOB_AMPLITUDE=.32, BOB_OMEGA=.85;
    static final double STEERING_ACCELERATION=2.6, STEERING_SPEED=6, INPUT_LEASE=.25;
    static double bob(double seconds,double phase){return BOB_AMPLITUDE*Math.sin(seconds*BOB_OMEGA+phase);}
    static double bobVelocity(double seconds,double phase){return BOB_AMPLITUDE*BOB_OMEGA*Math.cos(seconds*BOB_OMEGA+phase);}
    static Vector3d entry(Vector3d client,Vector3d measured){
        var result=new Vector3d(finite(client)&&client.lengthSquared()>1e-6?client:finite(measured)?measured:new Vector3d());
        if(result.length()>16)result.normalize(16);
        result.y=Math.max(-6,Math.min(6,result.y));return result;
    }
    static Vector3d desired(Vector3d momentum,double dt,double elapsed,double phase){
        momentum.mul(Math.exp(-DAMPING*dt));
        return new Vector3d(momentum).add(0,bobVelocity(elapsed,phase),0);
    }
    /** WishMovement is a world-space input force, independent of the resulting velocity. */
    static Vector3d inputAxes(Vector3d wish,double yaw){
        if(!finite(wish)||!Double.isFinite(yaw)||wish.lengthSquared()<1e-10)return new Vector3d();
        var direction=new Vector3d(wish).normalize();double sin=Math.sin(yaw),cos=Math.cos(yaw);
        // Local axes: right, explicit vertical input, forward. Native forward at yaw zero is -Z.
        return new Vector3d(direction.x*cos-direction.z*sin,direction.y,-direction.x*sin-direction.z*cos);
    }
    /** Conservative fallback when the ordinary client omits the optional wish force. */
    static Vector3d residualAxes(Vector3d reported,Collection<Vector3d> recentCommands,double yaw){
        if(!finite(reported)||recentCommands.isEmpty()||Math.hypot(reported.x,reported.z)<.15)return new Vector3d();
        Vector3d residual=null;double best=Double.POSITIVE_INFINITY,baselineSpeed=0;
        // A received packet can precede the newest command. Use the closest recent horizontal
        // baseline instead of interpreting the latency between our own commands as input.
        for(var command:recentCommands){
            var difference=new Vector3d(reported.x-command.x,0,reported.z-command.z);
            if(difference.lengthSquared()<best){best=difference.lengthSquared();residual=difference;baselineSpeed=Math.hypot(command.x,command.z);}
        }
        double magnitude=Math.sqrt(best),deadzone=.35+.08*baselineSpeed;
        // Do not infer pitch from falling, or thrust from tiny drag/collision corrections.
        // Very large residuals are external forces or invalid reports, not ordinary walking.
        if(residual==null||magnitude<=deadzone||magnitude>14)return new Vector3d();
        return inputAxes(residual,yaw).mul(Math.min(1,(magnitude-deadzone)/1.5));
    }
    static Vector3d steeringDirection(Vector3d axes,double yaw,double pitch){
        if(!finite(axes)||!Double.isFinite(yaw)||!Double.isFinite(pitch))return new Vector3d();
        pitch=Math.max(-Math.PI/2,Math.min(Math.PI/2,pitch));
        double sin=Math.sin(yaw),cos=Math.cos(yaw),vertical=Math.sin(pitch),horizontal=Math.cos(pitch);
        var direction=new Vector3d(axes.x*cos-axes.z*sin*horizontal,axes.y+axes.z*vertical,-axes.x*sin-axes.z*cos*horizontal);
        if(direction.lengthSquared()>1)direction.normalize();return direction;
    }
    static void steer(Vector3d momentum,Vector3d axes,double yaw,double pitch,double dt){
        var direction=steeringDirection(axes,yaw,pitch);if(direction.lengthSquared()<1e-10)return;
        // Entry momentum or external knockback above cruising speed is retained and decays
        // normally; steering can turn or brake it but cannot accelerate it further.
        double limit=Math.max(STEERING_SPEED,momentum.length());
        momentum.fma(STEERING_ACCELERATION*Math.min(.1,Math.max(0,dt)),direction);
        if(momentum.lengthSquared()>limit*limit)momentum.normalize(limit);
    }
    static Vector3d instruction(Vector3d desired,double dt,boolean inverted){
        // Initial compensation for gravity between server instructions. Client drag and frame
        // timing differ; bounded height feedback corrects the residual using reported positions.
        return new Vector3d(desired).add(0,(inverted?-1:1)*PhysicsConstants.GRAVITY_ACCELERATION*Math.min(.1,dt)*.5,0);
    }
    static double heightCorrection(double target,double actual){return Math.max(-1.25,Math.min(1.25,(target-actual)*.9));}
    static boolean finite(Vector3d value){return value!=null&&Double.isFinite(value.x)&&Double.isFinite(value.y)&&Double.isFinite(value.z);}
    private GravityMomentum(){}
}
