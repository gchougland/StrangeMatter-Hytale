package com.hexvane.strangematter.equipment;

import com.hexvane.strangematter.anomaly.AnomalyRecord;
import com.hexvane.strangematter.anomaly.AnomalyService;
import com.hexvane.strangematter.anomaly.AnomalyType;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.protocol.packets.player.ClientTeleport;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.component.Store;
import org.joml.Vector3d;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;

/** Real native players and inventory commits exercise the service's actual gate tick.
 * Only distant terrain IO is replaced with counted pending futures: a failed red
 * regression cannot generate thousands of blocks away in the shared test world.
 * NativeWarpProjectileVerification separately covers the actual projectile physics.
 */
public final class NativeGateIsolationVerification {
    private static final Vector3d POSITION=new Vector3d(16.5,250,16.5);
    private record Reserved(Object shot,boolean purple,short slot,String token) {}

    public static void verify(World world)throws Exception {
        var directory=Files.createTempDirectory("sm-native-gate-isolation-");
        var service=new AnomalyService(directory);service.naturalGeneration=false;
        var loaderCalls=new AtomicInteger();replaceDestinationLoader(service,loaderCalls);
        var warps=new WarpProjectiles(service);var store=world.getEntityStore().getStore();
        try(var first=NativePlayerFixture.create(world,"NativeGateOwnerA",new Vector3d(POSITION));
            var second=NativePlayerFixture.create(world,"NativeGateOwnerB",new Vector3d(28.5,250,28.5))){
            first.player().handleClientReady(false);second.player().handleClientReady(false);
            var gunGate=service.spawn(AnomalyType.WARP_GATE,world,new Vector3d(POSITION),false);
            service.setPortalChannel(gunGate.id,1);
            store.tick(1f/world.getTps());
            first.packets().packets.clear();second.packets().packets.clear();
            var positionBefore=new Vector3d(store.getComponent(first.ref(),TransformComponent.getComponentType()).getPosition());
            service.tick(world,.11);
            require(gunGate.age>0,"The lone gun gate ran through the production service tick beside an actual player");
            require(gunGate.pairedGate==null&&!gunGate.creatingPair&&service.all().size()==1&&loaderCalls.get()==0,
                    "Standing in a lone cyan gate does not create a natural partner or request distant terrain");
            require(first.packets().ofType(ClientTeleport.class).isEmpty()
                    &&store.getComponent(first.ref(),TransformComponent.getComponentType()).getPosition().equals(positionBefore),
                    "An unpaired gun gate sends no teleport and leaves the actual player in place");

            var natural=service.spawn(AnomalyType.WARP_GATE,world,new Vector3d(720.5,250,16.5),true);
            gateTick(service,world,gunGate);
            require(gunGate.pairedGate==null&&natural.pairedGate==null&&loaderCalls.get()==0,
                    "A lone gun gate cannot adopt an existing distant natural gate");
            gateTick(service,world,natural);
            require(gunGate.pairedGate==null&&natural.pairedGate==null&&loaderCalls.get()==0,
                    "A natural gate cannot select an unpaired gun endpoint as its distant partner");
            var naturalPartner=service.spawn(AnomalyType.WARP_GATE,world,new Vector3d(1450.5,250,16.5),true);
            gateTick(service,world,natural);
            reciprocal(natural,naturalPartner,"Natural gates still form their original distant pair while skipping the nearer gun endpoint");
            require(!service.pair(gunGate.id,natural.id),"The public pairing boundary rejects mixed natural and gun endpoints");
            reciprocal(natural,naturalPartner,"Rejected mixed pairing preserves the existing valid natural link");
            var capsuleGate=service.spawn(AnomalyType.WARP_GATE,world,new Vector3d(3000.5,250,16.5),false);
            var capsulePartner=service.spawn(AnomalyType.WARP_GATE,world,new Vector3d(3700.5,250,16.5),true);
            gateTick(service,world,capsuleGate);
            reciprocal(capsuleGate,capsulePartner,"An ordinary channel zero capsule gate retains normal pairing even without natural scan provenance");

            first.hotbar().setItemStackForSlot((short)0,new ItemStack("SM_Warp_Gun",1).withMetadata("GateIsolationMarker",Codec.STRING,"first gun"),false);
            second.hotbar().setItemStackForSlot((short)0,new ItemStack("SM_Warp_Gun",1).withMetadata("GateIsolationMarker",Codec.STRING,"second owner"),false);
            var aCyan=commit(warps,service,first,(short)0,false,new Vector3d(8.5,244,8.5));
            var bCyan=commit(warps,service,second,(short)0,false,new Vector3d(8.5,244,24.5));
            gateTick(service,world,aCyan);gateTick(service,world,bCyan);
            require(aCyan.pairedGate==null&&bCyan.pairedGate==null,"Separate owners' lone cyan commits remain unpaired");
            require(!service.pair(aCyan.id,bCyan.id)&&aCyan.pairedGate==null&&bCyan.pairedGate==null,
                    "Explicit same colour pairing is also rejected without altering either endpoint");
            var aPurple=commit(warps,service,first,(short)0,true,new Vector3d(24.5,244,8.5));
            reciprocal(aCyan,aPurple,"A gun's real inventory impact commits pair its cyan and purple endpoints");
            var gunBeforeCapture=first.hotbar().getItemStack((short)0);
            NativeWarpCaptureVerification.reject(service,aCyan);NativeWarpCaptureVerification.reject(service,aPurple);
            require(first.hotbar().getItemStack((short)0).equals(gunBeforeCapture),"Rejected capture leaves the actual gun's endpoint metadata intact");
            require(bCyan.pairedGate==null,"The other owner's cyan endpoint is untouched by the first owner's purple impact");
            var bPurple=commit(warps,service,second,(short)0,true,new Vector3d(24.5,244,24.5));
            reciprocal(bCyan,bPurple,"The second owner's purple commit pairs only its own cyan endpoint");
            reciprocal(aCyan,aPurple,"Creating the second personal pair preserves the first personal pair");

            first.hotbar().setItemStackForSlot((short)1,new ItemStack("SM_Warp_Gun",1),false);
            var spareCyan=commit(warps,service,first,(short)1,false,new Vector3d(12.5,240,12.5));
            gateTick(service,world,spareCyan);
            require(spareCyan.pairedGate==null,"A second gun held by the same owner cannot adopt the first gun's endpoints");
            var sparePurple=commit(warps,service,first,(short)1,true,new Vector3d(20.5,240,20.5));
            reciprocal(spareCyan,sparePurple,"The second gun pairs through its own opposite colour metadata");

            first.hotbar().setItemStackForSlot((short)2,new ItemStack("SM_Warp_Gun",1),false);
            var cancelled=reserve(first,(short)2,false);int beforeCancel=service.all().size();
            require(warps.clear(world,first.hotbar(),(short)2,first.hotbar().getItemStack((short)2)),"Actual clear removes a pending gun reservation");
            invokeImpact(warps,cancelled,store,new Vector3d(16.5,240,8.5));
            require(service.all().size()==beforeCancel,"A late impact after clear cannot create a portal");
            var transferred=reserve(first,(short)2,true);
            second.hotbar().setItemStackForSlot((short)1,first.hotbar().getItemStack((short)2),false);
            first.hotbar().setItemStackForSlot((short)2,ItemStack.EMPTY,false);
            invokeImpact(warps,transferred,store,new Vector3d(16.5,240,24.5));
            require(service.all().size()==beforeCancel,"A pending gun transferred to another player cannot be resolved through that other owner's inventory");

            require(warps.clear(world,first.hotbar(),(short)0,first.hotbar().getItemStack((short)0)),"Clearing the first gun succeeds");
            require(service.get(aCyan.id).isEmpty()&&service.get(aPurple.id).isEmpty(),"Clear removes exactly that gun's endpoints");
            reciprocal(bCyan,bPurple,"Clear preserves the other owner's pair");
            reciprocal(spareCyan,sparePurple,"Clear preserves the same owner's second gun pair");
            reciprocal(natural,naturalPartner,"Clear preserves nearby natural gates");
            require("first gun".equals(first.hotbar().getItemStack((short)0).getFromMetadataOrNull("GateIsolationMarker",Codec.STRING)),
                    "Pairing and clear preserve unrelated authored gun metadata");

            // Simulate the persisted reciprocal bad link written by an older release.
            var legacyNatural=service.spawn(AnomalyType.WARP_GATE,world,new Vector3d(2200.5,250,16.5),true);
            gunGate.pairedGate=legacyNatural.id;legacyNatural.pairedGate=gunGate.id;service.save();
            var restored=new AnomalyService(directory);
            require(restored.get(gunGate.id).orElseThrow().pairedGate==null&&restored.get(legacyNatural.id).orElseThrow().pairedGate==null,
                    "Loading a real saved mixed natural and gun link removes both bad endpoints' links");
            reciprocal(restored.get(bCyan.id).orElseThrow(),restored.get(bPurple.id).orElseThrow(),"Load repair preserves valid personal pairs");
            reciprocal(restored.get(natural.id).orElseThrow(),restored.get(naturalPartner.id).orElseThrow(),"Load repair preserves valid natural pairs");
            gateTick(service,world,gunGate);
            require(gunGate.pairedGate==null&&!gunGate.creatingPair&&loaderCalls.get()==0
                            &&first.packets().ofType(ClientTeleport.class).isEmpty(),
                    "The live gate tick also repairs a legacy mixed link before any loading or teleport");
        }finally{warps.cleanup(world);service.stopWorld(world);}
        NativeWarpCaptureVerification.verify(world);
        System.out.println("NATIVE_GATE_ISOLATION_VERIFICATION_PASSED: real service tick at lone cyan, zero terrain requests or teleport, natural candidate filtering, mixed link rejection and saved repair, same gun colour pairing, separate guns and owners, late cancelled and transferred impacts, exact clear scope.");
    }

    private static void replaceDestinationLoader(AnomalyService service,AtomicInteger calls)throws Exception {
        Class<?> type=Class.forName("com.hexvane.strangematter.anomaly.WarpLandingLoads");
        var constructor=type.getDeclaredConstructor(BiFunction.class,LongSupplier.class);constructor.setAccessible(true);
        BiFunction<World,Long,CompletableFuture<?>> loader=(world,index)->{calls.incrementAndGet();return new CompletableFuture<>();};
        var field=AnomalyService.class.getDeclaredField("gateLoads");field.setAccessible(true);
        field.set(service,constructor.newInstance(loader,(LongSupplier)System::nanoTime));
    }
    private static void gateTick(AnomalyService service,World world,AnomalyRecord gate)throws Exception {
        var method=AnomalyService.class.getDeclaredMethod("tickGate",World.class,AnomalyRecord.class);method.setAccessible(true);
        synchronized(service){method.invoke(service,world,gate);}
    }
    private static Reserved reserve(NativePlayerFixture owner,short slot,boolean purple)throws Exception {
        Class<?> type=Class.forName(WarpProjectiles.class.getName()+"$Shot");
        var constructor=type.getDeclaredConstructor(PlayerRef.class,World.class,boolean.class,Vector3d.class);constructor.setAccessible(true);
        Object shot=constructor.newInstance(owner.owner(),owner.world(),purple,new Vector3d(POSITION));
        var tokenField=type.getDeclaredField("token");tokenField.setAccessible(true);String token=(String)tokenField.get(shot);
        var stack=owner.hotbar().getItemStack(slot).withMetadata(WarpProjectiles.flightKey(purple),Codec.STRING,token);
        require(owner.hotbar().setItemStackForSlot(slot,stack,false).succeeded(),"Native inventory accepts the real flight nonce");
        return new Reserved(shot,purple,slot,token);
    }
    private static void invokeImpact(WarpProjectiles warps,Reserved reserved,Store<EntityStore> store,Vector3d position)throws Exception {
        Method method=WarpProjectiles.class.getDeclaredMethod("openPortal",reserved.shot.getClass(),Store.class,Vector3d.class);method.setAccessible(true);
        method.invoke(warps,reserved.shot,store,position);
    }
    private static AnomalyRecord commit(WarpProjectiles warps,AnomalyService service,NativePlayerFixture owner,short slot,boolean purple,Vector3d position)throws Exception {
        var reserved=reserve(owner,slot,purple);invokeImpact(warps,reserved,owner.store(),position);
        var current=owner.hotbar().getItemStack(slot);
        String id=current.getFromMetadataOrNull(WarpProjectiles.portalKey(purple),Codec.STRING);
        require(id!=null&&current.getFromMetadataOrNull(WarpProjectiles.flightKey(purple),Codec.STRING)==null,
                "Production impact commits an endpoint and consumes only its matching reservation");
        var record=service.get(UUID.fromString(id)).orElseThrow();
        require(!record.natural&&record.portalChannel==(purple?2:1),"Committed gun endpoints retain personal colour and nonnatural provenance");
        return record;
    }
    private static void reciprocal(AnomalyRecord a,AnomalyRecord b,String message){require(b.id.equals(a.pairedGate)&&a.id.equals(b.pairedGate),message);}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
