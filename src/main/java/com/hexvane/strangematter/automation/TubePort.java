package com.hexvane.strangematter.automation;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import java.util.Objects;
import java.util.function.Predicate;

/** A real native inventory section. The native container's own filters always remain authoritative. */
public record TubePort(String section,String label,ItemContainer inventory,Predicate<ItemStack> acceptsInsert,boolean extractable,Predicate<ItemStack> acceptsExtract) {
    public TubePort(String section,String label,ItemContainer inventory,Predicate<ItemStack> acceptsInsert,boolean extractable){this(section,label,inventory,acceptsInsert,extractable,stack->true);}
    public TubePort {Objects.requireNonNull(section);Objects.requireNonNull(label);Objects.requireNonNull(inventory);Objects.requireNonNull(acceptsInsert);Objects.requireNonNull(acceptsExtract);}
}
