package com.hexvane.strangematter.worldgen;

import com.hexvane.strangematter.research.ResearchPageData;
import com.hexvane.strangematter.util.InventoryOps;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.*;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;

public final class ScientistPage extends InteractiveCustomUIPage<ResearchPageData> {
    private final ScientistService service;
    private final UUID merchant;
    private boolean initialized;
    private String message="Trade resonite discoveries for Life Essence, and train this scientist to unlock more offers.";
    public ScientistPage(PlayerRef player,ScientistService service,UUID merchant){super(player,CustomPageLifetime.CanDismissOrCloseThroughInteraction,ResearchPageData.CODEC);this.service=service;this.merchant=merchant;}
    boolean isTradingWith(UUID identity){return merchant.equals(identity);}
    @Override public void build(Ref<EntityStore> ref,UICommandBuilder cmd,UIEventBuilder events,Store<EntityStore> store) {
        if(!initialized){cmd.append("StrangeMatter/ScientistTrade.ui");bind(events,"#Close","close","");initialized=true;}
        var state=service.record(merchant);if(state==null){close();return;}
        int tier=ScientistTrades.tier(state.xp);
        cmd.set("#Rank.Text",ScientistTrades.tierName(tier)+"  |  Experience "+state.xp+"  |  Currency: Life Essence");
        cmd.set("#Message.Text",message);cmd.clear("#Offers");int index=0;
        for(var offer:state.offers()) {
            cmd.append("#Offers","StrangeMatter/ScientistTradeRow.ui");String selector="#Offers["+index+++"]";
            cmd.set(selector+" #Input.Text",state.price(offer)+" × "+label(offer.input()));
            cmd.set(selector+" #Output.Text",offer.outputCount()+" × "+label(offer.output()));
            cmd.set(selector+" #Stock.Text",state.stock(offer)+" / "+offer.maxUses()+" trades remaining");
            cmd.set(selector+" #Trade.Disabled",state.stock(offer)<=0);bind(events,selector+" #Trade","trade",offer.id());
        }
    }
    @Override public void handleDataEvent(Ref<EntityStore> ref,Store<EntityStore> store,ResearchPageData data) {
        if("close".equals(data.action)){close();return;}
        if(!"trade".equals(data.action))return;
        message=service.trade(merchant,playerRef,store,data.value);
        UICommandBuilder cmd=new UICommandBuilder();UIEventBuilder events=new UIEventBuilder();build(ref,cmd,events,store);sendUpdate(cmd,events,false);
    }
    private static String label(String id){return ScientistTrades.LIFE_ESSENCE.equals(id)?"Life Essence":InventoryOps.label(id);}
    private static void bind(UIEventBuilder events,String selector,String action,String value){events.addEventBinding(CustomUIEventBindingType.Activating,selector,EventData.of("Action",action).append("Value",value),false);}
}
