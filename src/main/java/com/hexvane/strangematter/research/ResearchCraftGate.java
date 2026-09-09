package com.hexvane.strangematter.research;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.event.events.ecs.CraftRecipeEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/** Defence against stale or manually granted native knowledge bypassing the research ledger. */
public final class ResearchCraftGate extends EntityEventSystem<EntityStore,CraftRecipeEvent.Pre> {
    private final ResearchService research;
    public ResearchCraftGate(ResearchService research) { super(CraftRecipeEvent.Pre.class); this.research = research; }
    @Override public Query<EntityStore> getQuery() { return PlayerRef.getComponentType(); }
    @Override public void handle(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store, CommandBuffer<EntityStore> buffer, CraftRecipeEvent.Pre event) {
        if (event.isCancelled()) return;
        var player = chunk.getComponent(index, PlayerRef.getComponentType());
        String rejection = validate(player.getUuid(), event);
        if (rejection != null) player.sendMessage(Message.raw(rejection));
    }
    public String validate(java.util.UUID player, CraftRecipeEvent.Pre event) {
        if (event.isCancelled()) return null;
        var output = event.getCraftedRecipe().getPrimaryOutput();
        String node = output == null ? null : research.requiredResearchForItem(output.getItemId());
        if (node != null && !research.hasUnlocked(player, node)) {
            event.setCancelled(true);
            return "Complete " + research.researchName(node) + " research before crafting this item.";
        }
        return null;
    }
}
