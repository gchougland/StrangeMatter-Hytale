package com.hexvane.strangematter.equipment;

import com.hexvane.strangematter.StrangeMatterConfig;
import com.hexvane.strangematter.anomaly.AnomalyRecord;
import com.hexvane.strangematter.anomaly.AnomalyService;
import com.hexvane.strangematter.anomaly.AnomalyType;
import com.hexvane.strangematter.machine.MachineService;
import com.hexvane.strangematter.research.ResearchService;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomHud;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import org.joml.Vector3d;

import java.lang.foreign.MemorySegment;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

/** Native inventories/HUD and real aim/capture code; only the monotonic charge timestamp is advanced. */
public final class NativeWarpCaptureVerification {
    public static void verify(World world) throws Exception {
        var directory=Files.createTempDirectory("sm-warp-capture-");
        var anomalies=new AnomalyService(directory);anomalies.naturalGeneration=false;
        try(var research=new ResearchService(directory);
            var machines=new MachineService(directory,new StrangeMatterConfig(),research,anomalies);
            var owner=NativePlayerFixture.create(world,"WarpCaptureVerification",new Vector3d(16.5,238,16.5))) {
            var equipment=new EquipmentService(research,anomalies,machines);
            try {
                var normal=anomalies.spawn(AnomalyType.WARP_GATE,world,new Vector3d(10.5,232,10.5),true);
                var partner=anomalies.spawn(AnomalyType.WARP_GATE,world,new Vector3d(24.5,232,10.5),true);
                require(anomalies.pair(normal.id,partner.id),"Ordinary natural pair initialized");
                require(anomalies.canCapture(normal.id),"Natural world gate is capturable");
                var first=anomalies.capture(normal.id).orElseThrow();
                require(normal.contained&&!anomalies.canCapture(normal.id)&&partner.id.equals(normal.pairedGate),"Natural capture preserves pair and locks contained identity");
                var relocated=anomalies.release(first.token(),world,new Vector3d(10.5,232,20.5)).orElseThrow();
                require(relocated.released&&!relocated.scannable()&&relocated.portalChannel==0&&anomalies.canCapture(relocated.id),"Transported regular gate remains capturable without fresh scan provenance");
                var second=anomalies.capture(relocated.id).orElseThrow();
                require(!first.token().equals(second.token())&&partner.id.equals(relocated.pairedGate),"Recapture renews bearer nonce and preserves regular pair");
                require(anomalies.cancelCapture(second.token())&&anomalies.canCapture(relocated.id),"Inventory rollback still restores an ordinary gate");

                // The persisted marker, not 'natural', is authoritative even for a reclassified record.
                var cyan=anomalies.spawn(AnomalyType.WARP_GATE,world,new Vector3d(8.5,232,24.5),true);
                var purple=anomalies.spawn(AnomalyType.WARP_GATE,world,new Vector3d(24.5,232,24.5),false);
                anomalies.setPortalChannel(cyan.id,1);anomalies.setPortalChannel(purple.id,2);
                require(anomalies.pair(cyan.id,purple.id),"Test gun pair initialized");
                reject(anomalies,cyan);reject(anomalies,purple);anomalies.save();
                var reloaded=new AnomalyService(directory);
                reject(reloaded,reloaded.get(cyan.id).orElseThrow());reject(reloaded,reloaded.get(purple.id).orElseThrow());
                require(reloaded.canCapture(normal.id),"Saved relocated regular gate remains eligible after restart");

                var store=owner.store();
                var position=store.getComponent(owner.ref(),TransformComponent.getComponentType()).getPosition();
                var eye=new Vector3d(position).add(0,ModelComponent.getEyeHeight(owner.ref(),store),0);
                var direction=store.getComponent(owner.ref(),HeadRotation.getComponentType()).getDirection().normalize();
                var target=anomalies.spawn(AnomalyType.WARP_GATE,world,new Vector3d(direction).mul(4).add(eye),true);
                var vacuum=new ItemStack("SM_Echo_Vacuum",1).withMetadata("TestMarker",Codec.STRING,"preserve vacuum");
                var empty=new ItemStack("SM_Containment_Capsule",1).withMetadata("TestMarker",Codec.STRING,"preserve empty");
                owner.hotbar().setItemStackForSlot((short)0,vacuum,false);
                owner.hotbar().setItemStackForSlot((short)1,empty,false);
                Object aim=invoke(equipment,"aim",new Class<?>[]{Ref.class,Store.class,double.class},owner.ref(),store,12d);
                require(aim!=null,"Production aim reads native head rotation and block occlusion");
                Map<UUID,Object> acquisitions=acquisitions(equipment);

                begin(equipment,owner,aim);
                require(acquisitions.containsKey(owner.owner().getUuid()),"Regular target begins a vacuum charge");
                mature(acquisitions,owner.owner().getUuid());
                anomalies.setPortalChannel(target.id,1);
                complete(equipment,owner,aim);
                reject(anomalies,target);
                require(owner.hotbar().getItemStack((short)1).equals(empty)&&owner.hotbar().getItemStack((short)0).equals(vacuum),"Marker change during charge consumes neither capsule nor tool");
                require(!acquisitions.containsKey(owner.owner().getUuid()),"Denied completion retires its acquisition");

                for(int channel:new int[]{1,2}) {
                    anomalies.setPortalChannel(target.id,0);begin(equipment,owner,aim);
                    require(acquisitions.containsKey(owner.owner().getUuid()),"Prior regular acquisition exists before retarget rejection");
                    anomalies.setPortalChannel(target.id,channel);
                    // Reset the native HUD layer between equal notices; its normal renderer
                    // correctly deduplicates identical commands while a notice is still shown.
                    research.hud().clear(owner.owner(),store);owner.packets().packets.clear();
                    begin(equipment,owner,aim);
                    require(!acquisitions.containsKey(owner.owner().getUuid()),"Gun endpoint is rejected before charging and clears prior acquisition");
                    require(hudExplainsRestriction(owner),"Native HUD explains the gun-owned portal restriction for channel "+channel);
                    require(owner.hotbar().getItemStack((short)1).equals(empty),"Early rejection preserves exact empty capsule metadata");
                }

                anomalies.setPortalChannel(target.id,0);begin(equipment,owner,aim);mature(acquisitions,owner.owner().getUuid());
                complete(equipment,owner,aim);
                var filled=owner.hotbar().getItemStack((short)1);String token=filled.getFromMetadataOrNull("SMAnomaly",Codec.STRING);
                require(target.contained&&filled.getItemId().equals("SM_Containment_Capsule_Warp_Gate")&&token!=null&&token.startsWith(target.id+":"),"Actual Vacuum completion still fills a native capsule for an ordinary gate");
            } finally { equipment.cleanup(world); }
        } finally { anomalies.stopWorld(world); }
        System.out.println("NATIVE_WARP_CAPTURE_VERIFICATION_PASSED: natural and transported capture, renewed identity, paired cyan/purple rejection and reload, early native HUD feedback, stale acquisition marker race, exact inventory preservation");
    }

    static void reject(AnomalyService service,AnomalyRecord gate) {
        UUID pair=gate.pairedGate,nonce=gate.capsuleNonce;
        boolean enabled=gate.enabled,contained=gate.contained;int channel=gate.portalChannel;
        require(!service.canCapture(gate.id)&&service.capture(gate.id).isEmpty(),"Authoritative capture rejects gun channel "+channel);
        require(java.util.Objects.equals(pair,gate.pairedGate)&&java.util.Objects.equals(nonce,gate.capsuleNonce)
                &&gate.enabled==enabled&&gate.contained==contained&&gate.portalChannel==channel,"Rejected capture changes no endpoint/pair/nonce state");
    }
    private static void begin(EquipmentService service,NativePlayerFixture owner,Object aim)throws Exception {
        invoke(service,"begin",new Class<?>[]{PlayerRef.class,Store.class,aim.getClass(),String.class,boolean.class},owner.owner(),owner.store(),aim,"SM_Echo_Vacuum",true);
    }
    private static void complete(EquipmentService service,NativePlayerFixture owner,Object aim)throws Exception {
        invoke(service,"completeCapture",new Class<?>[]{PlayerRef.class,Store.class,aim.getClass()},owner.owner(),owner.store(),aim);
    }
    private static Object invoke(Object owner,String name,Class<?>[] types,Object...args)throws Exception {
        Method method=owner.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(owner,args);
    }
    @SuppressWarnings("unchecked") private static Map<UUID,Object> acquisitions(EquipmentService service)throws Exception {
        var field=EquipmentService.class.getDeclaredField("acquisitions");field.setAccessible(true);return (Map<UUID,Object>)field.get(service);
    }
    private static void mature(Map<UUID,Object> acquisitions,UUID player)throws Exception {
        Object acquisition=acquisitions.get(player);require(acquisition!=null,"Charge exists before advancing timestamp");
        var components=acquisition.getClass().getRecordComponents();Object[] values=new Object[components.length];Class<?>[] types=new Class<?>[components.length];
        for(int i=0;i<components.length;i++) {
            var accessor=components[i].getAccessor();accessor.setAccessible(true);types[i]=components[i].getType();
            values[i]=components[i].getName().equals("began")?System.nanoTime()-2_100_000_000L:accessor.invoke(acquisition);
        }
        var constructor=acquisition.getClass().getDeclaredConstructor(types);constructor.setAccessible(true);acquisitions.put(player,constructor.newInstance(values));
    }
    private static boolean hudExplainsRestriction(NativePlayerFixture player) {
        for(var packet:player.packets().ofType(CustomHud.class)) {
            var wire=MemorySegment.ofArray(new byte[packet.computeSize()]);packet.serialize(wire,0);var decoded=CustomHud.toObject(wire);
            if(Arrays.stream(decoded.commands).anyMatch(command->"#GadgetStatus.Text".equals(command.selector)&&command.data.contains("Warp Gun portals cannot be contained")))return true;
        }
        return false;
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
