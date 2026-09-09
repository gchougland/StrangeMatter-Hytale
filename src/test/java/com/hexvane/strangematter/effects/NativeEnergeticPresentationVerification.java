package com.hexvane.strangematter.effects;

import com.hypixel.hytale.math.vector.Vector3dUtil;
import com.hypixel.hytale.protocol.Direction;
import com.hypixel.hytale.protocol.ParticleRotationInfluence;
import com.hypixel.hytale.protocol.Position;
import com.hypixel.hytale.protocol.packets.world.SpawnParticleSystem;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.asset.type.particle.config.ParticleSpawner;
import com.hypixel.hytale.server.core.asset.type.particle.config.ParticleSystem;
import java.lang.foreign.MemorySegment;
import org.joml.Vector3d;

/** Native assets and packet directions, not a claim about a captured client frame. */
public final class NativeEnergeticPresentationVerification {
    public static void verify() {
        var system=ParticleSystem.getAssetMap().getAsset("SM_Stabilizer_Link");
        require(system!=null && system.getSpawners().length==2,"Actual stabilizer system is loaded");
        for(String suffix:new String[]{"Filament","Glow"}) {
            var spawner=ParticleSpawner.getAssetMap().getAsset("SM_Stabilizer_Link_"+suffix);
            var packet=spawner.toPacket();var bytes=MemorySegment.ofArray(new byte[packet.computeSize()]);packet.serialize(bytes,0);
            var decoded=com.hypixel.hytale.protocol.ParticleSpawner.toObject(bytes);
            require(decoded.particleRotationInfluence==ParticleRotationInfluence.BillboardVelocity,"Link sprite aligns its long Y axis with motion");
            require(!decoded.particleRotateWithSpawner,"Camera-facing velocity alignment does not receive a second spawner rotation");
            require(decoded.initialVelocity.speed.min>0 && decoded.initialVelocity.speed.max<=.011f,"Link uses stable nonzero orientation velocity");
            require(decoded.initialVelocity.yaw.min==0 && decoded.initialVelocity.yaw.max==0
                && decoded.initialVelocity.pitch.min==0 && decoded.initialVelocity.pitch.max==0,"Local velocity is exactly native forward");
            require(decoded.particle.initialAnimationFrame.scale.y.min*4>=1.25f,"One-unit segment has overlap instead of gaps");
        }
        int segments=0;
        for(var target:new Vector3d[]{new Vector3d(8,0,0),new Vector3d(-8,0,0),new Vector3d(0,0,8),new Vector3d(0,0,-8),
                new Vector3d(6,3,7),new Vector3d(-6,-3,7),new Vector3d(0,8,0)}) {
            var from=new Vector3d();var path=MachineLinks.arc(from,target,17);var parts=MachineLinks.segments(from,target,17);
            require(parts.size()==path.size()-1 && parts.size()<=56,"One bounded oriented segment connects every pair of arc vertices");
            for(int i=0;i<parts.size();i++) {
                var segment=parts.get(i);var r=segment.rotation();
                var packet=new SpawnParticleSystem("SM_Stabilizer_Link",new Position(segment.midpoint().x,segment.midpoint().y,segment.midpoint().z),
                    new Direction(r.yaw(),r.pitch(),0),segment.length(),null,.30f);
                var bytes=MemorySegment.ofArray(new byte[packet.computeSize()]);packet.serialize(bytes,0);
                var decoded=SpawnParticleSystem.toObject(bytes);
                var nativeDirection=Vector3dUtil.setYawPitch(decoded.rotation.yaw,decoded.rotation.pitch,new Vector3d());
                var expected=new Vector3d(path.get(i+1)).sub(path.get(i)).normalize();
                require(nativeDirection.dot(expected)>.99999,"Native packet forward follows the true world segment, including height changes");
                // Vector3dUtil uses TrigMathUtil's4096-entry sine/cosine lookup.
                // Applying that approximation again introduced up to0.000393 blocks
                // of endpoint error to an otherwise unchanged packet. Independently
                // reconstruct the encoded angles with precise trig, retaining the
                // native-helper direction comparison above as a separate assertion.
                var direction=new Vector3d(-Math.cos(decoded.rotation.pitch)*Math.sin(decoded.rotation.yaw),
                    Math.sin(decoded.rotation.pitch),-Math.cos(decoded.rotation.pitch)*Math.cos(decoded.rotation.yaw));
                var midpoint=new Vector3d(decoded.position.x,decoded.position.y,decoded.position.z);
                require(new Vector3d(midpoint).fma(-decoded.scale*.5,direction).distance(path.get(i))<.00001
                    &&new Vector3d(midpoint).fma(decoded.scale*.5,direction).distance(path.get(i+1))<.00001,"Packet midpoint, rotation and scale recover both segment endpoints");
                segments++;
            }
        }
        require(MachineLinks.segments(new Vector3d(),new Vector3d(),0).isEmpty(),"Coincident endpoints create no directionless sprite");
        require(MachineLinks.segments(new Vector3d(),new Vector3d(33,0,0),0).isEmpty(),"Out-of-range links are bounded");
        var rift=ParticleSystem.getAssetMap().getAsset("SM_Energetic_Rift");
        require(rift!=null&&rift.getSpawners().length==13,"Layered electric tear loads all thirteen layers");
        int total=0;
        for(var group:rift.toPacket().spawners) {
            var spawner=ParticleSpawner.getAssetMap().getAsset(group.spawnerId);require(spawner!=null,"Every rift layer resolves");
            var packet=spawner.toPacket();total+=packet.totalParticles.max;
            require(packet.lifeSpan>0&&packet.lifeSpan<=.92f&&packet.particleLifeSpan.max<=1.2f,"Rift emitter and particles have bounded lifetimes");
        }
        require(total==64,"One rift pulse is capped at64 particles, independent of layers");
        var model=Model.createUnitScaleModel(ModelAsset.getAssetMap().getAsset("SM_Energetic_Rift_Core")).toPacket();
        require(model.attachments!=null&&model.attachments.length==1
            &&model.attachments[0].model.endsWith("anomaly_energetic_shell.blockymodel"),"Fractured shell reaches the native model packet");
        System.out.println("NATIVE_ENERGETIC_PRESENTATION_VERIFICATION_PASSED: "+segments+" world-aligned link segment packets, finite64-particle rift pulse and native shell attachment");
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
