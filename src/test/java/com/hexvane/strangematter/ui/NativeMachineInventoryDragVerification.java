package com.hexvane.strangematter.ui;

import com.hexvane.strangematter.equipment.NativePlayerFixture;
import com.hexvane.strangematter.util.StackData;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.protocol.packets.interface_.CustomPage;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.hexvane.strangematter.ui.NativeMachineInventoryVerification.*;
import static com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType.*;

/** Serialized native gesture regressions; deliberately never synthesizes SlotClicking. */
public final class NativeMachineInventoryDragVerification {
    private static final String STORAGE="#PlayerStorageGrid", INPUT="#MachineInputGrid", HOTBAR="#PlayerHotbarGrid";
    private record Saved(ItemContainer inventory,short slot,String stack) {}

    public static void verify(NativePlayerFixture f,CustomPage frame,MachineInventoryPanel panel,
                              ItemContainer input,ItemContainer output,ItemContainer storage,
                              ItemContainer hotbar,AtomicBoolean permitted)throws Exception {
        f.world().debugAssertInTickingThread();
        var saved=new ArrayList<Saved>();
        for(short slot=20;slot<=29;slot++)saved.add(save(storage,slot));
        for(short slot=12;slot<=15;slot++)saved.add(save(input,slot));
        saved.add(save(hotbar,(short)6));
        var outputBefore=snapshot(output);
        var originalBindings=bindings(frame);
        boolean wasPermitted=permitted.get();
        try {
            permitted.set(true);
            for(var cell:saved)cell.inventory.setItemStackForSlot(cell.slot,null,false);
            var first=item(10,"first drag");var other=item(9,"untouched source");
            storage.setItemStackForSlot((short)20,first,false);
            storage.setItemStackForSlot((short)21,other,false);
            redraw(panel,frame,originalBindings);

            drag(f,frame,STORAGE,20,INPUT,12);
            settle(f);
            exact(storage,20,null,"Initial drag-only source is emptied");
            exact(input,12,first,"Initial drag-only move preserves the complete stack");
            redraw(panel,frame,originalBindings);

            // The source control can emit cleanup immediately after a target accepts the drop.
            drag(f,frame,INPUT,12,HOTBAR,6);
            gesture(f,frame,INPUT,12,DragCancelled);
            settle(f);
            exact(input,12,null,"Immediate source cleanup does not erase an accepted drop");
            exact(hotbar,6,first,"Second drag starts without a preceding click and survives cleanup");
            redraw(panel,frame,originalBindings);
            gesture(f,frame,INPUT,12,DragCancelled);
            drag(f,frame,HOTBAR,6,STORAGE,23);
            settle(f);
            exact(hotbar,6,null,"Cleanup after completion does not lock the next drag");
            exact(storage,23,first,"Third drag returns the exact stack to player storage");
            redraw(panel,frame,originalBindings);
            drag(f,frame,STORAGE,21,INPUT,13);
            settle(f);
            exact(storage,21,null,"Unchanged source binding remains usable after other slot redraws");
            exact(input,13,other,"An untouched source can begin a fresh drag after completed moves");

            var cancelled=item(6,"cancelled source");var fresh=item(7,"fresh after cancellation");
            storage.setItemStackForSlot((short)24,cancelled,false);
            storage.setItemStackForSlot((short)25,fresh,false);
            redraw(panel,frame,originalBindings);
            gesture(f,frame,STORAGE,24,SlotMouseDragExited);
            gesture(f,frame,STORAGE,24,DragCancelled);
            settle(f);
            exact(storage,24,cancelled,"Cancellation without any target never withdraws its source");
            exact(storage,25,fresh,"Cancellation leaves unrelated material intact");
            exact(input,14,null,"Cancellation alone creates no destination stack");

            // Begin a different gesture before the old cancellation timer has expired.
            gesture(f,frame,STORAGE,24,SlotMouseDragExited);
            gesture(f,frame,STORAGE,24,DragCancelled);
            drag(f,frame,STORAGE,25,INPUT,14);
            settle(f);
            exact(storage,24,cancelled,"Fast fresh drag never reuses the cancelled source");
            exact(storage,25,null,"Fast fresh drag consumes only its own source");
            exact(input,14,fresh,"Delayed cancellation cannot erase a newer accepted drop");

            input.setItemStackForSlot((short)14,null,false);
            storage.setItemStackForSlot((short)25,fresh,false);
            redraw(panel,frame,originalBindings);
            drag(f,frame,STORAGE,24,STORAGE,24);
            drag(f,frame,STORAGE,25,INPUT,14);
            settle(f);
            exact(storage,24,cancelled,"Dropping back on the source ends that speculative gesture");
            exact(storage,25,null,"Fresh source remains usable after a same-slot drop without cleanup");
            exact(input,14,fresh,"Same-slot drop cannot leak the old source into a later drag");

            input.setItemStackForSlot((short)14,null,false);
            storage.setItemStackForSlot((short)25,fresh,false);
            redraw(panel,frame,originalBindings);
            gesture(f,frame,STORAGE,24,SlotMouseDragExited);
            gesture(f,frame,STORAGE,24,DragCancelled);
            gesture(f,frame,INPUT,14,Dropped);
            gesture(f,frame,STORAGE,25,SlotMouseDragExited);
            settle(f);
            exact(storage,24,null,"A real target accepted after source cleanup retains its pending move");
            exact(input,14,cancelled,"Crossing another source cannot erase a target that already accepted a drop");
            exact(storage,25,fresh,"An accepted pending drop does not consume the crossed source");

            // Exercise rejection BEFORE the release callback, rather than only during its timer.
            var stale=item(6,"source before checkpoint");var afterBusy=item(4,"source after checkpoint");
            for(String rejected:List.of("drop","native","cancel")) {
                storage.setItemStackForSlot((short)26,stale,false);
                storage.setItemStackForSlot((short)27,afterBusy,false);
                input.setItemStackForSlot((short)15,null,false);
                redraw(panel,frame,originalBindings);
                gesture(f,frame,STORAGE,26,SlotMouseDragExited);
                permitted.set(false);
                switch(rejected) {
                    case "drop" -> gesture(f,frame,INPUT,15,Dropped);
                    case "native" -> moveRendered(f,frame,STORAGE,26,2,INPUT,15);
                    case "cancel" -> gesture(f,frame,STORAGE,26,DragCancelled);
                    default -> throw new AssertionError(rejected);
                }
                exact(storage,26,stale,"Busy "+rejected+" does not alter source inventory");
                exact(input,15,null,"Busy "+rejected+" does not alter destination inventory");
                permitted.set(true);
                redraw(panel,frame,originalBindings);
                drag(f,frame,STORAGE,27,INPUT,15);
                settle(f);
                exact(storage,26,stale,"Rejected "+rejected+" cleared the previous speculative selection");
                exact(storage,27,null,"Fresh source is usable immediately after busy "+rejected);
                exact(input,15,afterBusy,"Fresh drag after busy "+rejected+" moves the right metadata payload");
            }

            var split=item(10,"split and late duplicate");
            storage.setItemStackForSlot((short)28,split,false);
            input.setItemStackForSlot((short)14,null,false);
            redraw(panel,frame,originalBindings);
            drag(f,frame,STORAGE,28,INPUT,14);
            moveRendered(f,frame,STORAGE,28,3,INPUT,14);
            settle(f);
            exact(storage,28,split.withQuantity(7),"Native split leaves the correct remainder");
            exact(input,14,split.withQuantity(3),"Native split owns one transfer over the pending fallback");
            redraw(panel,frame,originalBindings);
            moveRendered(f,frame,STORAGE,28,3,INPUT,14);
            gesture(f,frame,INPUT,14,SlotMouseDragCompleted);
            settle(f);
            exact(storage,28,split.withQuantity(7),"Late duplicate after a redraw cannot remove another remainder");
            exact(input,14,split.withQuantity(3),"Late release after a redraw does not duplicate a native split");
            drag(f,frame,STORAGE,28,INPUT,14);
            settle(f);
            exact(storage,28,null,"Fresh drag-only action can move the remaining split to the same destination");
            exact(input,14,split,"Deliberate retry after duplicate suppression remains usable");
            require(outputBefore.equals(snapshot(output)),"Repeated gestures do not affect output slots");
            redraw(panel,frame,originalBindings);
            System.out.println("NATIVE_MACHINE_INVENTORY_DRAG PASS: stable serialized bindings, repeated drag-only moves, immediate and completed cleanup, cancelled-source isolation, three busy inbox rejections, split duplicate after redraw and deliberate retry");
        } finally {
            permitted.set(false);
            try { gesture(f,frame,STORAGE,20,DragCancelled);settle(f); }
            finally {
                for(var cell:saved)cell.inventory.setItemStackForSlot(cell.slot,cell.stack==null?null:StackData.decode(cell.stack),false);
                permitted.set(wasPermitted);
                project(panel,frame);
            }
        }
    }
    private static void drag(NativePlayerFixture f,CustomPage frame,String from,int slot,String to,int target) {
        gesture(f,frame,from,slot,SlotMouseDragExited);
        gesture(f,frame,to,target,Dropped);
    }
    private static ItemStack item(int count,String proof){return new ItemStack("SM_Resonite_Ingot",count).withMetadata("DragOnlyProof",Codec.STRING,proof);}
    private static String encode(ItemStack stack){return ItemStack.isEmpty(stack)?null:StackData.encode(stack);}
    private static Saved save(ItemContainer inventory,short slot){return new Saved(inventory,slot,encode(inventory.getItemStack(slot)));}
    private static List<String> snapshot(ItemContainer inventory){var result=new ArrayList<String>();for(short i=0;i<inventory.getCapacity();i++)result.add(encode(inventory.getItemStack(i)));return result;}
    private static List<String> bindings(CustomPage frame){return Arrays.stream(frame.eventBindings).map(b->b.selector+"\n"+b.type+"\n"+b.data+"\n"+b.locksInterface).toList();}
    private static void redraw(MachineInventoryPanel panel,CustomPage frame,List<String> expected){project(panel,frame);require(expected.equals(bindings(frame)),"Every original event binding remains byte-for-byte unchanged across serialized slot redraws");}
    private static void exact(ItemContainer inventory,int slot,ItemStack expected,String message){String actual=encode(inventory.getItemStack((short)slot));require(Objects.equals(actual,encode(expected)),message+"; slot="+slot+", actual="+actual+", expected="+encode(expected));}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private NativeMachineInventoryDragVerification(){}
}
