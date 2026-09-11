package com.hexvane.strangematter.progression;

import com.hexvane.strangematter.research.ResearchPageData;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.protocol.packets.interface_.*;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.*;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/** Native journal presentation of earned achievements. */
public final class ProgressionPage extends InteractiveCustomUIPage<ResearchPageData> {
    private final ProgressionService service;
    public ProgressionPage(PlayerRef player,ProgressionService service){super(player,CustomPageLifetime.CanDismissOrCloseThroughInteraction,ResearchPageData.CODEC);this.service=service;}
    @Override public void build(Ref<EntityStore> ref,UICommandBuilder cmd,UIEventBuilder events,Store<EntityStore> store){
        cmd.append("StrangeMatter/Progression.ui");
        events.addEventBinding(CustomUIEventBindingType.Activating,"#Close",EventData.of("Action","close"),false);
        int index=0;
        var milestones=service.snapshot(playerRef.getUuid());
        cmd.set("#Progress.Text",milestones.stream().filter(ProgressionService.Milestone::complete).count()+" / "+milestones.size()+" achievements completed");
        for(var milestone:milestones){
            cmd.append("#Milestones","StrangeMatter/ProgressionRow.ui");String selector="#Milestones["+index+++"]";
            cmd.set(selector+" #Name.Text",milestone.title());
            cmd.set(selector+" #Description.Text",milestone.description());
            cmd.set(selector+" #State.Text",milestone.complete()?"COMPLETE":"UNDISCOVERED");
            cmd.set(selector+" #State.Style.TextColor",milestone.complete()?"#65ebc9":"#8190ac");
        }
    }
    @Override public void handleDataEvent(Ref<EntityStore> ref,Store<EntityStore> store,ResearchPageData data){if("close".equals(data.action))close();}
}
