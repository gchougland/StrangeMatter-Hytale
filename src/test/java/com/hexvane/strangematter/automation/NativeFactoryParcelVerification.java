package com.hexvane.strangematter.automation;

import com.hexvane.strangematter.equipment.GadgetEnergy;
import com.hexvane.strangematter.util.StackData;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.nio.file.Files;
import java.util.UUID;
import org.joml.Vector3i;

/** Native item and holder payload plus actual fsynced ownership journal through restart stages. */
public final class NativeFactoryParcelVerification {
    public static void verify()throws Exception {
        var directory=Files.createTempDirectory("sm-native-factory-parcel-");
        var component=new FactoryComponent();component.data.energy=385;component.data.fuelTicks=47;
        var gadget=GadgetEnergy.withCharge(new ItemStack("SM_Warp_Gun",1).withMetadata("PortalIdentity",Codec.STRING,"parcel-preserved"),1234);
        component.charging.setItemStackForSlot((short)0,gadget,false);
        component.input.setItemStackForSlot((short)0,new ItemStack("Ingredient_Charcoal",3),false);
        var receipt=new FactoryParcelLedger.Receipt();receipt.itemId="SM_Resonant_Burner";receipt.owner=UUID.randomUUID();receipt.world="parcel-test";receipt.enabled=false;receipt.sourceIdentity=component.data.identity;
        receipt.payload=FactoryComponent.CODEC.encode(component,new ExtraInfo()).asDocument().toJson();
        var ledger=new FactoryParcelLedger(directory);ledger.create(receipt);
        for(var phase:new FactoryParcelLedger.Phase[]{FactoryParcelLedger.Phase.PACKING,FactoryParcelLedger.Phase.REMOVING,FactoryParcelLedger.Phase.RETURNING,FactoryParcelLedger.Phase.AVAILABLE}){
            ledger.phase(receipt,phase);ledger=new FactoryParcelLedger(directory);receipt=ledger.get(receipt.token);
            require(receipt.phase==phase,"Restart preserves pending handoff phase "+phase);
            var restored=FactoryComponent.CODEC.decode(org.bson.BsonDocument.parse(receipt.payload),new ExtraInfo());
            require(restored.data.energy==385&&restored.data.fuelTicks==47&&restored.input.getItemStack((short)0).getQuantity()==3,"Parcel retains burner fuel and stored energy");
            require(StackData.encode(restored.charging.getItemStack((short)0)).equals(StackData.encode(gadget)),"Parcel preserves exact charge and unrelated portal metadata");
        }
        var parcel=StackData.decode(StackData.encode(receipt.item()));
        require(receipt.token.equals(FactoryParcelLedger.token(parcel))&&parcel.getQuantity()==1,"Native item save preserves singular parcel identity");
        require(ledger.placing(receipt,"destination",new Vector3i(3,5,7),UUID.randomUUID().toString(),UUID.randomUUID()),"Available parcel may reserve one placed machine");
        require(!ledger.placing(receipt,"destination",new Vector3i(8,5,7),UUID.randomUUID().toString(),UUID.randomUUID()),"A repeated placement cannot claim the same gadget payload twice");
        ledger=new FactoryParcelLedger(directory);receipt=ledger.get(receipt.token);require(receipt.phase==FactoryParcelLedger.Phase.PLACING&&receipt.position().equals(new Vector3i(3,5,7)),"Interrupted placement retains the owned destination for recovery");
        ledger.phase(receipt,FactoryParcelLedger.Phase.PLACED);ledger=new FactoryParcelLedger(directory);receipt=ledger.get(receipt.token);
        require(!ledger.placing(receipt,"another-world",new Vector3i(),UUID.randomUUID().toString(),UUID.randomUUID()),"A retired parcel remains invalid after restart");
        ledger.phase(receipt,FactoryParcelLedger.Phase.BROKEN);ledger=new FactoryParcelLedger(directory);receipt=ledger.get(receipt.token);
        require(receipt.phase==FactoryParcelLedger.Phase.BROKEN&&ledger.pending().isEmpty()&&!ledger.placing(receipt,"another-world",new Vector3i(),UUID.randomUUID().toString(),UUID.randomUUID()),"Environmental ownership is terminal and cannot issue a second parcel after restart");
        System.out.println("NATIVE_FACTORY_PARCEL_VERIFICATION_PASSED: persisted handoff phases, exact occupied dock/fuel/reserve, native token roundtrip and single payload claim across restart.");
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
