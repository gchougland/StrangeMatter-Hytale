package com.hexvane.strangematter;

import com.hypixel.hytale.codec.*;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.protocol.*;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;

/** Server-resolved custom interactions, represented to the client as native simple interactions. */
public final class StrangeMatterInteraction extends SimpleInstantInteraction {
    public static final BuilderCodec<StrangeMatterInteraction> CODEC=BuilderCodec.builder(StrangeMatterInteraction.class,StrangeMatterInteraction::new,SimpleInstantInteraction.CODEC)
        .append(new KeyedCodec<>("Action",Codec.STRING),(v,s)->v.action=s,v->v.action).add().build();
    private String action="use";
    @Override protected void firstRun(InteractionType type,InteractionContext context,CooldownHandler cooldown){
        var plugin=StrangeMatterPlugin.instance();if(plugin==null){context.getState().state=InteractionState.Failed;return;}
        plugin.equipment().interact(context,action);
    }
    @Override protected void simulateFirstRun(InteractionType type,InteractionContext context,CooldownHandler cooldown) {}
    @Override public WaitForDataFrom getWaitForDataFrom(){return WaitForDataFrom.Server;}
    @Override protected com.hypixel.hytale.protocol.Interaction generatePacket(){return new com.hypixel.hytale.protocol.SimpleInteraction();}
}
