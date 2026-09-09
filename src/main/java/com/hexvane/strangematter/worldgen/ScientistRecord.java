package com.hexvane.strangematter.worldgen;

import java.util.*;

/** Persistent state belongs to the merchant, so multiplayer clients share its stock and training. */
public final class ScientistRecord {
    public UUID id,entity;
    public String world;
    public int x,y,z,xp;
    public boolean spawned;
    public long restockSlot=Long.MIN_VALUE;
    public Map<String,Integer> uses=new HashMap<>(),demand=new HashMap<>();
    public ScientistRecord(UUID id,String world,int x,int y,int z){this.id=id;this.world=world;this.x=x;this.y=y;this.z=z;}
    public List<ScientistTrades.Offer> offers(){return ScientistTrades.selected(id,ScientistTrades.tier(xp));}
    public int stock(ScientistTrades.Offer o){return Math.max(0,o.maxUses()-uses.getOrDefault(o.id(),0));}
    public int price(ScientistTrades.Offer o){return ScientistTrades.price(o,demand.getOrDefault(o.id(),0));}
    public void traded(ScientistTrades.Offer o){uses.merge(o.id(),1,Integer::sum);xp+=o.merchantXp();}
    public boolean restock(long slot) {
        if(restockSlot==Long.MIN_VALUE){restockSlot=slot;return true;}
        if(slot<=restockSlot)return false;restockSlot=slot;
        for(var o:offers()) {int count=uses.getOrDefault(o.id(),0);demand.merge(o.id(),count-(o.maxUses()-count),Integer::sum);}
        uses.clear();return true;
    }
}
