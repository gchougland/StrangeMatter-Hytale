package com.hexvane.strangematter.automation;

import com.google.gson.Gson;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import java.util.UUID;
import java.util.Arrays;

/** Configuration lives in the tube's block holder, including across loaded and parked chunk sections. */
public final class TubeComponent implements Component<ChunkStore> {
    static ComponentType<ChunkStore,TubeComponent> type;
    private static final Gson GSON=new Gson();
    public static final BuilderCodec<TubeComponent> CODEC=BuilderCodec.builder(TubeComponent.class,TubeComponent::new)
        .append(new KeyedCodec<>("Settings",Codec.STRING),(c,v)->c.read(v),c->GSON.toJson(c.data)).add().build();
    private Data data=new Data();
    private static final class Data {UUID id=UUID.randomUUID(),owner;long revision;boolean[] configuredFaces=new boolean[6];TubeConfiguration[] faces={new TubeConfiguration(),new TubeConfiguration(),new TubeConfiguration(),new TubeConfiguration(),new TubeConfiguration(),new TubeConfiguration()};}
    public static ComponentType<ChunkStore,TubeComponent> getComponentType(){return type;}
    public UUID id(){return data.id;}public UUID owner(){return data.owner;}public long revision(){return data.revision;}
    public TubeConfiguration face(int i){return data.faces[i].copy();}
    public void owner(UUID value){data.owner=value;data.revision++;}
    public void configure(int index,TubeConfiguration config){if(index<0||index>=6)throw new IllegalArgumentException("Face");config.validate();data.faces[index]=config.copy();data.configuredFaces[index]=true;data.revision++;}
    public boolean configured(int index){return data.configuredFaces[index];}
    boolean defaultInsert(int index,String section){
        if(data.configuredFaces[index])return false;
        var config=data.faces[index];if(config.mode==TubeConfiguration.Mode.INSERT&&config.section.equals(section))return false;
        config.mode=TubeConfiguration.Mode.INSERT;config.section=section;data.revision++;return true;
    }
    private void read(String raw){
        var next=GSON.fromJson(raw,Data.class);if(next==null||next.id==null||next.faces==null||next.faces.length!=6)throw new IllegalArgumentException("Invalid tube save");
        for(var c:next.faces){if(c==null)throw new IllegalArgumentException("Missing tube face");c.validate();}
        if(!com.google.gson.JsonParser.parseString(raw).getAsJsonObject().has("configuredFaces")){
            // Old saves only recorded a whole-tube revision. A never-edited tube has
            // zero edits, or one owner claim; anything else may include explicit OFF.
            boolean untouched=next.revision<=(next.owner==null?0:1)&&Arrays.stream(next.faces).allMatch(TubeComponent::pristine);
            next.configuredFaces=new boolean[6];if(!untouched)Arrays.fill(next.configuredFaces,true);
        }
        if(next.configuredFaces==null||next.configuredFaces.length!=6)throw new IllegalArgumentException("Invalid tube face markers");data=next;
    }
    private static boolean pristine(TubeConfiguration c){return c.mode==TubeConfiguration.Mode.OFF&&c.section.equals("storage")&&!c.exclude&&c.match==TubeConfiguration.Match.ITEM&&Arrays.stream(c.samples).allMatch(java.util.Objects::isNull)&&c.resource.isEmpty()&&c.leaveBehind==0&&c.fillUpTo==0&&c.priority==0&&c.batch==5;}
    @Override public TubeComponent clone(){var result=new TubeComponent();result.read(GSON.toJson(data));return result;}
}
