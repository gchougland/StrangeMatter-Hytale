package com.hexvane.strangematter.ui;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import java.util.Locale;

/** Shared charge instrument. Updates are sent through the owning page's bounded lease. */
public final class PowerMeter {
    private PowerMeter() {}
    public static void append(UICommandBuilder commands,String host){commands.append(host,"StrangeMatter/PowerMeter.ui");}
    public static void draw(UICommandBuilder commands,String host,int stored,int capacity,int enteringPerSecond,int usedPerSecond,long remainingEnergy,String status,boolean infinite){
        boolean visible=capacity>0||infinite;
        commands.set(host+".Visible",visible);if(!visible)return;
        double fraction=infinite?1:Math.clamp((double)stored/Math.max(1,capacity),0,1);
        commands.set(host+" #ChargeFill.Value",fraction);
        commands.set(host+" #ChargeValue.Text",infinite?"UNLIMITED CHARGE":String.format(Locale.ROOT,"%,d / %,d RE",Math.max(0,stored),capacity));
        commands.set(host+" #ChargeState.Text",status);
        commands.set(host+" #ChargeWarning.Visible",!infinite&&stored<Math.max(1,(usedPerSecond+19)/20));
        commands.set(host+" #ChargePanel.TooltipText",String.format(Locale.ROOT,"Receiving %,d RE each second\nUsing %,d RE each second\nThis job needs %,d more RE",Math.max(0,enteringPerSecond),Math.max(0,usedPerSecond),Math.max(0,remainingEnergy)));
    }
}
