import com.hexvane.strangematter.effects.MachineLinks;
import com.hypixel.hytale.math.vector.Vector3dUtil;
import com.hypixel.hytale.protocol.*;
import com.hypixel.hytale.protocol.packets.world.SpawnParticleSystem;
import java.lang.foreign.MemorySegment;
import org.joml.Vector3d;

public final class ProbeLinkMath {
  public static void main(String[] args) {
    double worst=0,worstExact=0,minimumNativeDot=1;int count=0;
    for(var target:new Vector3d[]{new Vector3d(8,0,0),new Vector3d(-8,0,0),new Vector3d(0,0,8),new Vector3d(0,0,-8),new Vector3d(6,3,7),new Vector3d(-6,-3,7),new Vector3d(0,8,0)}) {
      var path=MachineLinks.arc(new Vector3d(),target,17);var parts=MachineLinks.segments(new Vector3d(),target,17);
      for(int i=0;i<parts.size();i++) {
        var s=parts.get(i);var r=s.rotation();
        var p=new SpawnParticleSystem("SM_Stabilizer_Link",new Position(s.midpoint().x,s.midpoint().y,s.midpoint().z),new Direction(r.yaw(),r.pitch(),0),s.length(),null,.30f);
        var bytes=MemorySegment.ofArray(new byte[p.computeSize()]);p.serialize(bytes,0);var d=SpawnParticleSystem.toObject(bytes);
        var nativeDirection=Vector3dUtil.setYawPitch(d.rotation.yaw,d.rotation.pitch,new Vector3d());
        var exactDirection=new Vector3d(-Math.cos(d.rotation.pitch)*Math.sin(d.rotation.yaw),Math.sin(d.rotation.pitch),-Math.cos(d.rotation.pitch)*Math.cos(d.rotation.yaw));
        var ideal=new Vector3d(path.get(i+1)).sub(path.get(i)).normalize();
        var mid=new Vector3d(d.position.x,d.position.y,d.position.z);
        double error=new Vector3d(mid).fma(d.scale*.5,nativeDirection).distance(path.get(i+1));
        double exactError=new Vector3d(mid).fma(d.scale*.5,exactDirection).distance(path.get(i+1));
        worstExact=Math.max(worstExact,exactError);minimumNativeDot=Math.min(minimumNativeDot,nativeDirection.dot(ideal));
        if(exactError>=.00001||nativeDirection.dot(ideal)<=.99999)throw new AssertionError("Native packet direction is outside the verified bound");
        if(error>worst) {
          worst=error;
          System.out.printf("target=%s index=%d length=%.9f yaw=%.9f pitch=%.9f nativeError=%.9g exactTrigError=%.9g nativeNorm=%.9f angleErrorRad=%.9g\n",target,i,d.scale,d.rotation.yaw,d.rotation.pitch,error,
            new Vector3d(mid).fma(d.scale*.5,exactDirection).distance(path.get(i+1)),nativeDirection.length(),Math.acos(Math.clamp(exactDirection.dot(ideal),-1,1)));
          System.out.printf("originalPosition=%s wirePosition=%s; originalLength=%.9f wireLength=%.9f rotationDelta=%.9g/%.9g\n",s.midpoint(),mid,s.length(),d.scale,d.rotation.yaw-r.yaw(),d.rotation.pitch-r.pitch());
        }
        count++;
      }
    }
    System.out.printf("PROBE_PASS segments=%d worstNativeEndpointError=%.9g worstPreciseEndpointError=%.9g minimumNativeDirectionDot=%.9g\n",count,worst,worstExact,minimumNativeDot);
  }
}
