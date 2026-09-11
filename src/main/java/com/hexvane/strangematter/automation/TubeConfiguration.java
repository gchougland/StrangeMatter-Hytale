package com.hexvane.strangematter.automation;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import java.util.*;

public final class TubeConfiguration {
    public enum Mode {OFF,EXTRACT,INSERT}
    public enum Match {ITEM,EXACT,RESOURCE}
    public Mode mode=Mode.OFF;
    public String section="storage";
    public boolean exclude;
    public Match match=Match.ITEM;
    public String[] samples=new String[5];
    public String resource="";
    public int leaveBehind,fillUpTo,priority,batch=5;
    public TubeConfiguration copy(){
        var c=new TubeConfiguration();c.mode=mode;c.section=section;c.exclude=exclude;c.match=match;c.samples=samples.clone();c.resource=resource;c.leaveBehind=leaveBehind;c.fillUpTo=fillUpTo;c.priority=priority;c.batch=batch;return c;
    }
    public void validate(){
        if(mode==null||match==null||section==null||section.length()>64||resource==null||resource.length()>128||samples==null||samples.length!=5
                ||leaveBehind<0||leaveBehind>100000||fillUpTo<0||fillUpTo>100000||priority< -10||priority>10||batch<1||batch>5)throw new IllegalArgumentException("Invalid tube connection settings");
        for(var sample:samples)if(sample!=null){if(sample.length()>65536)throw new IllegalArgumentException("Filter sample too large");TubeStacks.decode(sample);}
    }
    public boolean accepts(ItemStack stack){
        if(ItemStack.isEmpty(stack))return false;
        boolean has=false,hit=false;
        if(match==Match.RESOURCE){has=!resource.isBlank();hit=has&&ItemContainer.getMatchingResourceType(stack.getItem(),resource)!=null;}
        else for(String encoded:samples)if(encoded!=null){
            has=true;var sample=TubeStacks.decode(encoded);
            if(match==Match.ITEM?sample.getItemId().equals(stack.getItemId()):sample.isStackableWith(stack)&&sample.getOverrideDroppedItemAnimation()==stack.getOverrideDroppedItemAnimation())hit=true;
        }
        return !has||(exclude?!hit:hit);
    }
    public int count(ItemContainer inventory){int count=0;for(short s=0;s<inventory.getCapacity();s++){var item=inventory.getItemStack(s);if(accepts(item))count+=item.getQuantity();}return count;}
}
