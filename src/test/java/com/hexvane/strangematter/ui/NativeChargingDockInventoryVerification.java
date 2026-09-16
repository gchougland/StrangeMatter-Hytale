package com.hexvane.strangematter.ui;

import com.hexvane.strangematter.automation.FactoryService;
import com.hexvane.strangematter.equipment.GadgetEnergy;
import com.hexvane.strangematter.equipment.NativePlayerFixture;
import com.hexvane.strangematter.machine.*;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.protocol.packets.interface_.*;
import com.hypixel.hytale.server.core.inventory.*;
import com.hypixel.hytale.server.core.universe.world.World;
import org.joml.Vector3d;
import static com.hexvane.strangematter.ui.NativeMachineInventoryVerification.*;

/** The actual burner page: fuel slot zero and gadget slot zero are distinct native windows. */
public final class NativeChargingDockInventoryVerification {
    public static void verify(World world,MachineService machines,FactoryService factory,MachineState burner)throws Exception{
        var c=factory.component(world,burner);var oldDock=c.charging.getItemStack((short)0);var oldFuel=c.input.getItemStack((short)0);
        int oldEnergy=burner.energy;
        try(var lifecycle=new MachineWindowLifecycle();var f=NativePlayerFixture.create(world,"NativeChargingDock",new Vector3d(burner.x+.5,burner.y,burner.z+2))){
            c.data.allowed.add(f.owner().getUuid().toString());
            var storage=f.store().getComponent(f.ref(),InventoryComponent.Storage.getComponentType()).getInventory();
            var fuel=new ItemStack("Ingredient_Charcoal",2).withMetadata("DockFuel",Codec.STRING,"leave this slot alone");
            var scanner=GadgetEnergy.withCharge(new ItemStack("SM_Field_Scanner",1).withMetadata("DockItem",Codec.STRING,"exact gadget"),0);
            c.input.setItemStackForSlot((short)0,fuel,false);c.charging.setItemStackForSlot((short)0,null,false);
            f.hotbar().setItemStackForSlot((short)7,scanner,false);
            MachinePage.open(f.owner(),machines,burner,f.store());
            // The fixture records writes rather than invoking the network adapter. Publish
            // the actual initial page to its ACK gate, so timed redraws cannot outrun the
            // displayed command stream while this test deliberately leaves the ACK pending.
            for(var packet:f.packets().packets)if(packet instanceof CustomPage||packet instanceof SetPage)
                com.hypixel.hytale.server.core.io.adapter.PacketAdapters.__handleOutbound(f.packets(),packet);
            var page=f.player().getPageManager().getCustomPage();require(page instanceof MachinePage,"Real burner page opens");
            var field=MachinePage.class.getDeclaredField("inventoryPanel");field.setAccessible(true);var panel=(MachineInventoryPanel)field.get(page);
            var windows=panel.windows();require(windows.length==2&&windows[0].getId()>0&&windows[1].getId()>0&&windows[0].getId()!=windows[1].getId(),"Fuel and writable dock retain distinct positive native window IDs");
            var frame=f.packets().ofType(CustomPage.class).getLast();
            require(property(frame,"#PlayerHotbarGrid[0][7] #ChargeMeter.Visible").getAsBoolean()
                    &&property(frame,"#PlayerHotbarGrid[0][7] #ChargeFill.Anchor").getAsJsonObject().get("Width").getAsInt()==0,
                    "Empty gadget visibly carries an empty energy meter in its native inventory cell");
            require(!property(frame,"#MachineInputGrid[0][0] #ChargeMeter.Visible").getAsBoolean(),"Fuel slots never display a gadget energy overlay");
            var press=CustomUIEventBindingType.SlotClicking;var drag=CustomUIEventBindingType.SlotMouseDragExited;var drop=CustomUIEventBindingType.Dropped;
            gesture(f,frame,"#PlayerHotbarGrid",7,press);gesture(f,frame,"#MachineOutputGrid",0,drop);
            moveRendered(f,frame,"#PlayerHotbarGrid",7,1,"#MachineOutputGrid",0);settle(f);
            require(scanner.equals(c.charging.getItemStack((short)0))&&fuel.equals(c.input.getItemStack((short)0)),"Native insertion addresses the gadget dock without replacing fuel slot zero");
            project(panel,frame);
            int capacity=GadgetEnergy.capacity(scanner);
            for(int charge:new int[]{capacity/2,capacity,0}){
                c.charging.setItemStackForSlot((short)0,GadgetEnergy.withCharge(scanner,charge),false);project(panel,frame);
                require(property(frame,"#MachineOutputGrid[0][0] #ChargeMeter.Visible").getAsBoolean()
                        &&property(frame,"#MachineOutputGrid[0][0] #ChargeFill.Anchor").getAsJsonObject().get("Width").getAsInt()==(34L*charge+capacity-1)/capacity,
                        "Native dock frame explicitly renders proportional energy at half, full and zero charge");
            }
            // The user pressed the displayed gadget, but its next charge update reaches the
            // server before the callback. The old code discarded that source, then selected
            // fuel when the pointer crossed the occupied fuel cell on its way to inventory.
            var displayed=new CustomPage(frame);
            burner.energy=1000;factory.chargeDock(world,burner);project(panel,frame);
            var charged=c.charging.getItemStack((short)0);
            gesture(f,displayed,"#MachineOutputGrid",0,press);
            gesture(f,frame,"#MachineInputGrid",0,drag);
            gesture(f,frame,"#PlayerStorageGrid",35,drop);
            moveRendered(f,frame,"#MachineInputGrid",0,1,"#PlayerStorageGrid",35);
            settle(f);
            require(charged.equals(storage.getItemStack((short)35))&&ItemStack.isEmpty(c.charging.getItemStack((short)0))&&fuel.equals(c.input.getItemStack((short)0)),
                    "A charge update and crossing fuel never redirect gadget extraction into the fuel window");
            project(panel,frame);gesture(f,frame,"#PlayerStorageGrid",35,press);gesture(f,frame,"#MachineOutputGrid",0,drop);settle(f);
            project(panel,frame);gesture(f,frame,"#MachineOutputGrid",0,press);
            factory.chargeDock(world,burner);var newest=c.charging.getItemStack((short)0);project(panel,frame);
            gesture(f,frame,"#PlayerHotbarGrid",7,drop);moveRendered(f,frame,"#MachineOutputGrid",0,1,"#PlayerHotbarGrid",7);settle(f);
            require(newest.equals(f.hotbar().getItemStack((short)7))&&ItemStack.isEmpty(c.charging.getItemStack((short)0))&&fuel.equals(c.input.getItemStack((short)0)),
                    "Native dock removal during an active charge drag keeps the newest RE and complete item metadata");
            f.player().getPageManager().setPage(f.ref(),f.store(),Page.None,false);
            c.data.allowed.remove(f.owner().getUuid().toString());
        }finally{c.input.setItemStackForSlot((short)0,oldFuel,false);c.charging.setItemStackForSlot((short)0,oldDock,false);burner.energy=oldEnergy;factory.changed(world,burner);}
        System.out.println("NATIVE_CHARGING_DOCK_INVENTORY_VERIFICATION_PASSED: actual burner two-window insertion/removal, stale charge frame, crossed fuel slot, unrelated native source rejection and latest-charge preservation.");
    }
    private static com.google.gson.JsonElement property(CustomPage frame,String selector){
        var command=java.util.Arrays.stream(frame.commands).filter(c->selector.equals(c.selector)).toList().getLast();
        return com.google.gson.JsonParser.parseString(command.data).getAsJsonObject().get("0");
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
