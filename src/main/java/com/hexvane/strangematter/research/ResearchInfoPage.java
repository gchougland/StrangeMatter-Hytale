package com.hexvane.strangematter.research;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.*;
import com.hypixel.hytale.server.core.ui.builder.*;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/** Unlocked teaching pages, with complete original text and current native recipe ingredients. */
public final class ResearchInfoPage extends InteractiveCustomUIPage<ResearchPageData> {
    private final ResearchService service; private final ResearchNode node;
    private int index; private boolean initialized;
    public ResearchInfoPage(PlayerRef player, ResearchService service, ResearchNode node) {
        super(player, CustomPageLifetime.CanDismissOrCloseThroughInteraction, ResearchPageData.CODEC); this.service = service; this.node = node;
    }
    @Override public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder events, Store<EntityStore> store) {
        if (!initialized) {
            cmd.append("StrangeMatter/ResearchInfo.ui"); initialized = true;
            ResearchTabletPage.bind(events, "#Back", "back", ""); ResearchTabletPage.bind(events, "#Close", "close", "");
            ResearchTabletPage.bind(events, "#Previous", "previous", ""); ResearchTabletPage.bind(events, "#Next", "next", "");
            for(var type:ResearchType.values()){
                cmd.append("#GuideDisciplines","StrangeMatter/ResearchDisciplineChip.ui");String row="#GuideDisciplines["+type.ordinal()+"]";
                ResearchDisciplineUi.icon(cmd,row+" #DisciplineIcon",type);cmd.set(row+" #DisciplineLabel.Text",type.displayName());
                var a=new Anchor();a.setLeft(Value.of(type.ordinal()*140));a.setTop(Value.of(0));a.setWidth(Value.of(138));a.setHeight(Value.of(20));cmd.setObject(row+".Anchor",a);
            }
        }
        var pages = ResearchTeaching.pages(node); var page = pages.get(index);
        cmd.set("#Topic.Text", node.name().toUpperCase(java.util.Locale.ROOT)); cmd.set("#Heading.Text", page.title());
        cmd.set("#Body.Text", page.content()); cmd.set("#Edition.Text", ResearchTeaching.hytaleNotes(node.id()));
        int lines = page.content().lines().mapToInt(line -> Math.max(1, (int) Math.ceil(line.length() / 76.0))).sum();
        Anchor body = new Anchor(); body.setHeight(Value.of(Math.max(150, lines * 24 + 30))); cmd.setObject("#Body.Anchor", body);
        var recipe = ResearchTeaching.recipe(page.recipe()); cmd.set("#Recipe.Visible", recipe != null);
        if (recipe != null) { cmd.set("#Output.ItemId", recipe.output()); cmd.set("#Ingredients.Text", recipe.details()); }
        cmd.set("#PageNumber.Text", "FIELD GUIDE  " + (index + 1) + " / " + pages.size());
        cmd.set("#Previous.Disabled", index == 0); cmd.set("#Next.Disabled", index + 1 == pages.size());
    }
    @Override public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, ResearchPageData data) {
        if ("close".equals(data.action)) { close(); return; }
        if (!service.hasUnlocked(playerRef.getUuid(), node.id()) || "back".equals(data.action)) { service.openTablet(playerRef, store, node.id()); return; }
        int last = ResearchTeaching.pages(node).size() - 1;
        if ("next".equals(data.action)) index = Math.min(last, index + 1);
        else if ("previous".equals(data.action)) index = Math.max(0, index - 1); else return;
        // A new reading page starts at the top, rather than inheriting the previous scroll offset.
        initialized = false;
        UICommandBuilder cmd = new UICommandBuilder(); UIEventBuilder events = new UIEventBuilder(); build(ref, cmd, events, store); sendUpdate(cmd, events, true);
    }
}
