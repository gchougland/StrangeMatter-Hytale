package com.hexvane.strangematter.research;

import com.hexvane.strangematter.StrangeMatterPlugin;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.*;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/** Native research tablet with category gating, all original costs and prerequisite navigation. */
public final class ResearchTabletPage extends InteractiveCustomUIPage<ResearchPageData> {
    private final ResearchService service;
    private String category = "general", selected = "research", message = "";
    private boolean initialized;
    public ResearchTabletPage(PlayerRef player, ResearchService service) {
        this(player, service, null);
    }
    public ResearchTabletPage(PlayerRef player, ResearchService service, String selectedNode) {
        super(player, CustomPageLifetime.CanDismissOrCloseThroughInteraction, ResearchPageData.CODEC); this.service = service;
        var node = service.node(selectedNode);
        if (node != null) { selected = node.id(); category = node.category(); }
    }
    @Override public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder events, Store<EntityStore> store) {
        if (!initialized) {
            cmd.append("StrangeMatter/ResearchTablet.ui"); initialized = true;
            bind(events, "#Close", "close", ""); bind(events, "#General", "category", "general");
            bind(events, "#Forge", "category", "reality_forge"); bind(events, "#Purchase", "purchase", "");
            bind(events,"#Journal","journal","");
        }
        var profile = service.profile(playerRef.getUuid());
        StringBuilder points = new StringBuilder();
        for (ResearchType type : ResearchType.values()) points.append(type.displayName()).append("  ").append(profile.points().get(type)).append("     ");
        cmd.set("#Points.Text", points.toString().trim());
        cmd.set("#Scanned.Text", profile.scannedCount() + " field observations recorded");
        cmd.set("#Forge.Disabled", !profile.unlocked().contains("reality_forge"));
        cmd.set("#Journal.Disabled",StrangeMatterPlugin.instance()==null||StrangeMatterPlugin.instance().progression()==null);
        cmd.clear("#Nodes");
        int index = 0;
        for (ResearchNode node : service.nodes()) {
            if (!node.category().equals(category)) continue;
            cmd.append("#Nodes", "StrangeMatter/ResearchNodeRow.ui");
            String selector = "#Nodes[" + index++ + "]";
            boolean unlocked = profile.unlocked().contains(node.id());
            boolean prerequisites = profile.unlocked().containsAll(node.prerequisites());
            cmd.set(selector + " #NodeName.Text", node.name());
            cmd.set(selector + " #NodeState.Text", unlocked ? "UNLOCKED" : prerequisites ? "AVAILABLE" : "PREREQUISITE");
            cmd.set(selector + " #NodeState.Style.TextColor", unlocked ? "#63e6c2" : prerequisites ? "#bd93f9" : "#707e9d");
            cmd.set(selector + " #NodeName.Style.TextColor", selected.equals(node.id()) ? "#62f0ff" : "#d7e0f5");
            bind(events, selector + " #SelectNode", "select", node.id());
        }
        ResearchNode node = service.node(selected);
        cmd.set("#NodeTitle.Text", node.name());
        cmd.set("#Description.Text", node.description());
        cmd.set("#Costs.Text", node.costs().isEmpty() ? "Available from the beginning" : node.costSummary());
        String prerequisites = node.prerequisites().stream().map(service::node).map(ResearchNode::name).collect(java.util.stream.Collectors.joining(", "));
        cmd.set("#Prerequisites.Text", prerequisites.isEmpty() ? "Foundation research" : "Requires: " + prerequisites);
        boolean unlocked = service.hasUnlocked(playerRef.getUuid(), node.id());
        cmd.set("#Purchase.Disabled", !unlocked && service.availability(playerRef.getUuid(), node) != null);
        cmd.set("#Purchase.Text", unlocked ? "READ FIELD GUIDE" : "CREATE RESEARCH NOTE");
        cmd.set("#Message.Text", message.isEmpty() ? (unlocked ? "Technology unlocked. Consult the crafting stations for its recipes." : "Spend observations to write a note, then stabilize its disciplines at a Research Machine.") : message);
    }
    @Override public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, ResearchPageData data) {
        if (data.action == null) return;
        switch (data.action) {
            case "close" -> { close(); return; }
            case "journal" -> {var plugin=StrangeMatterPlugin.instance();if(plugin!=null&&plugin.progression()!=null)plugin.progression().open(playerRef,store);return;}
            case "category" -> {
                if ("general".equals(data.value)) { category = "general"; selected = "research"; }
                else if ("reality_forge".equals(data.value) && service.hasUnlocked(playerRef.getUuid(), "reality_forge")) { category = "reality_forge"; selected = "reality_forge_category"; }
                message = "";
            }
            case "select" -> { ResearchNode node = service.node(data.value); if (node != null && node.category().equals(category)) { selected = node.id(); message = ""; if (service.hasUnlocked(playerRef.getUuid(), node.id())) { service.openInfo(playerRef, store, node.id()); return; } } }
            case "purchase" -> {
                if (service.hasUnlocked(playerRef.getUuid(), selected)) { service.openInfo(playerRef, store, selected); return; }
                message = service.purchase(playerRef.getUuid(), selected, ResearchService.inventory(store, ref));
                if (message.startsWith("Created ")) {
                    var transform = store.getComponent(ref, com.hypixel.hytale.server.core.modules.entity.component.TransformComponent.getComponentType());
                    if (transform != null) com.hexvane.strangematter.effects.GadgetEffects.sound(store.getExternalData().getWorld(), "SM_Research_Note_Create_SFX", transform.getPosition());
                }
            }
            default -> { return; }
        }
        UICommandBuilder cmd = new UICommandBuilder(); UIEventBuilder events = new UIEventBuilder();
        build(ref, cmd, events, store); sendUpdate(cmd, events, false);
    }
    static void bind(UIEventBuilder events, String selector, String action, String value) {
        events.addEventBinding(CustomUIEventBindingType.Activating, selector, EventData.of("Action", action).append("Value", value), false);
    }
}
