package com.hexvane.strangematter.automation;

import com.hexvane.strangematter.machine.MachineService;
import com.hexvane.strangematter.machine.MachineState;
import com.hexvane.strangematter.machine.MachineWorkEffects;
import com.hexvane.strangematter.util.StackData;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.UUID;
import org.joml.Vector3i;

/** Native gathering emits the one real machine item. Only its tier crosses a placement boundary. */
public final class FactoryPickup {
    public static final String TIER_METADATA = "SMFactoryTier";
    private FactoryPickup() {}

    public static int tier(ItemStack item) {
        if (ItemStack.isEmpty(item)) return 1;
        int maximum = switch (item.getItemId()) {
            case "SM_Flux_Furnace" -> 2;
            case "SM_Pattern_Assembler" -> 3;
            default -> 1;
        };
        var metadata = StackData.metadata(item);
        var value = metadata == null ? null : metadata.get(TIER_METADATA);
        // The drop assets write Int32. Reject malformed or oversized numeric metadata safely.
        return value != null && value.isInt32() ? Math.clamp(value.asInt32().getValue(), 1, maximum) : 1;
    }

    /** Called only after the native placement succeeded and its block entity exists. */
    public static void placed(MachineService machines, World world, MachineState state,
                              ItemStack item, UUID newOwner) {
        world.debugAssertInTickingThread();
        var factory = machines.factory();
        if (factory == null) return;
        if(factory.placeParcel(world,state,item,newOwner))return;
        // This is a successful new placement, including when another same-frame callback
        // discovered its native holder before our placement callback. Never inherit access.
        var component = factory.component(world, state);
        if (component != null) {
            component.data.owner="";component.data.allowed.clear();component.data.pattern="";
            component.data.selected.clear();component.data.repeat=false;component.data.energy=0;
            state.owner=null;state.energy=0;state.selectedRecipes.clear();state.factoryMigration="";
            if(com.hexvane.strangematter.machine.EnergyStoragePorts.storage(state.id)){state.energyFaces=null;component.data.energyFaces=null;}
        }
        factory.initializeTier(world, state, tier(item));
        if (newOwner != null) factory.claim(world, state, newOwner);
        MachineWorkEffects.sync(world, state);
    }

    /** The event may be cancelled by a later listener, so examine its final state after dispatch. */
    public static void completeRemoval(MachineService machines, World world, Vector3i origin,
                                       MachineState original, FactoryComponent originalComponent, BreakBlockEvent event) {
        world.debugAssertInTickingThread();
        if (event.isCancelled() || original == null || machines.get(world, origin) != original) return;
        var current = world.getBlockType(origin.x, origin.y, origin.z);
        if (current != null && MachineService.IDS.contains(MachineService.baseId(current))) {
            // A replacement may already occupy this position before the queued callbacks run.
            // Its new native holder proves removal; a block ID alone cannot distinguish it.
            var currentComponent = component(world, origin);
            if (originalComponent == null || originalComponent == currentComponent) return;
            if (machines.factory() != null && currentComponent != null
                    && machines.factory().registeredComponent(world, original) == currentComponent) return;
        }
        if (machines.factory() != null) machines.factory().remove(world, original, originalComponent);
        machines.removed(world, origin);
        machines.save();
    }

    public static FactoryComponent component(World world, Vector3i position) {
        return FactoryComponent.getComponentType() == null ? null : BlockModule.getComponent(
                FactoryComponent.getComponentType(), world, position.x, position.y, position.z);
    }
}
