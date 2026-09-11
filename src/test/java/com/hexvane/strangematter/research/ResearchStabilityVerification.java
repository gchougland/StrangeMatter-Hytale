package com.hexvane.strangematter.research;

import java.nio.file.*;
import java.util.*;
import com.google.gson.JsonParser;

/** Visible recall and rate-limited human controls, plus stochastic hazard, spacing and config migration. */
public final class ResearchStabilityVerification {
    public static void main(String[] args)throws Exception{verify();}
    public static void verify()throws Exception{
        verifyMigration();verifyRecurrence();
        int solved=0,relapses=0,maxTicks=0;
        for(int mask=1;mask<64;mask++)for(int seed=0;seed<12;seed++){
            var costs=new EnumMap<ResearchType,Integer>(ResearchType.class);
            for(var type:ResearchType.values())if((mask&(1<<type.ordinal()))!=0)costs.put(type,1);
            var game=new ResearchSession(new ResearchNode("visible_test","general","Instruments","",costs,List.of()),seed);game.begin();
            var order=new ArrayList<>(game.activeTypes());var memory=new ArrayList<Integer>();int[] pattern=null;int lastIndex=-1,recallTicks=0,next=0;
            var wasStable=new EnumMap<ResearchType,Boolean>(ResearchType.class);
            for(int tick=0;tick<6000&&game.state()==ResearchSession.State.RUNNING;tick++){
                var cognition=game.panel(ResearchType.COGNITION);
                if(cognition!=null){
                    if(pattern!=cognition.pattern){pattern=cognition.pattern;memory.clear();lastIndex=-1;recallTicks=0;}
                    if(cognition.displaying&&!cognition.displayGap&&cognition.displayIndex!=lastIndex){
                        // Read only the cue which would actually be visible, never the answer array in advance.
                        int shown=cognition.pattern[cognition.displayIndex];
                        if(cognition.displayIndex<memory.size())memory.set(cognition.displayIndex,shown);
                        else memory.add(shown);
                        lastIndex=cognition.displayIndex;
                    }
                    if(!cognition.displaying&&!cognition.stable)recallTicks++;
                }
                // One physical button every quarter second across the entire workstation.
                if(tick%5==0)for(int offset=0;offset<order.size();offset++){
                    int index=(next+offset)%order.size();var type=order.get(index);var p=game.panel(type);
                    if(p.cooldown>0)continue;
                    boolean used=switch(type){
                        case COGNITION -> !p.stable&&!p.displaying&&memory.size()==p.pattern.length&&recallTicks>=40&&game.control(type,"symbol",memory.get(p.inputCount));
                        case ENERGY -> Math.abs(p.value-p.target)>.02?game.control(type,"amplitude",p.value<p.target?1:-1):Math.abs(p.secondary-p.targetSecondary)>.02&&game.control(type,"period",p.secondary<p.targetSecondary?1:-1);
                        case GRAVITY -> p.value!=-p.target&&game.control(type,"force",(int)-p.target);
                        case SHADOW -> Math.abs(p.value+180-p.target)>.01?game.control(type,"angle",p.value+180<p.target?1:-1):Math.abs(ResearchSession.shadowLength(p)-p.targetSecondary)>.01&&game.control(type,"distance",ResearchSession.shadowLength(p)>p.targetSecondary?1:-1);
                        case SPACE -> ResearchPuzzleVerification.controlSpaceOneClick(game);
                        case TIME -> Math.abs(p.value-p.target)>.02&&game.control(type,"speed",p.value<p.target?1:-1);
                    };
                    if(used){next=(index+1)%order.size();break;}
                }
                game.tick();
                for(var type:order){boolean stable=game.panel(type).stable;if(Boolean.TRUE.equals(wasStable.put(type,stable))&&!stable)relapses++;}
            }
            require(game.state()==ResearchSession.State.SUCCESS,"Visible cues plus two-second recall and shared 250ms controls solve mask "+mask+" seed "+seed+" ("+game.state()+", "+game.instability()+")");
            maxTicks=Math.max(maxTicks,(int)game.ticks());solved++;
        }
        require(relapses>50,"Realistic play actually encounters the faster relapse rather than completing before every timer");
        System.out.println("PASS: "+solved+" visible-recall experiments across every one-to-six-panel combination, "+relapses+" real relapses, shared 250ms controls; longest run "+maxTicks/20.0+"s.");
    }
    private static ResearchNode node(int count,ResearchType observed){
        var costs=new EnumMap<ResearchType,Integer>(ResearchType.class);costs.put(observed,1);
        for(var type:ResearchType.values())if(costs.size()<count)costs.put(type,1);
        return new ResearchNode("hazard_test","general","Instruments","",costs,List.of());
    }
    private static ResearchSettings slowGauge(){
        var settings=new ResearchSettings();settings.instabilityDecreaseRate=.0001;settings.instabilityBaseIncreaseRate=.0001;return settings;
    }
    private static void calibrate(ResearchSession game,ResearchType type){
        var p=game.panel(type);p.stable=true;p.stableTicks=100;p.driftTicks=0;
        switch(type){
            case COGNITION -> {p.displaying=false;p.inputCount=p.pattern.length;}
            case ENERGY -> {p.value=p.target;p.secondary=p.targetSecondary;}
            case GRAVITY -> {p.value=-p.target;p.position=.5;p.velocity=0;}
            case SHADOW -> {p.value=p.target-180;p.secondary=70-p.targetSecondary/.8;}
            case SPACE -> {p.value=0;p.secondary=0;}
            case TIME -> {p.value=p.target;p.angle=p.targetAngle;}
        }
    }
    private static int firstDisturbance(int count,ResearchType observed,long seed){
        var game=new ResearchSession(node(count,observed),seed,slowGauge());game.begin();calibrate(game,observed);
        int limit=game.destabilizationTicks(observed)*12;
        for(int tick=1;tick<=limit;tick++){
            game.tick();require(game.state()==ResearchSession.State.RUNNING,"Hazard sampling does not terminate through its gauge");
            if(!game.panel(observed).stable)return tick;
        }
        throw new AssertionError("Seeded sample never disturbed: "+count+"/"+observed+"/"+seed);
    }
    private static void verifyRecurrence(){
        for(int count=1;count<=6;count++)for(var observed:ResearchType.values()){
            var probe=new ResearchSession(node(count,observed),1,slowGauge());
            int mean=(observed==ResearchType.SPACE?200:observed==ResearchType.ENERGY?120:90)*count;
            require(probe.destabilizationTicks(observed)==mean,"Mean timing scales linearly by active instrument count");
            double previous=0;
            for(int age=0;age<=mean*12;age++){
                double chance=probe.destabilizationChance(observed,age);
                require(Double.isFinite(chance)&&chance>=previous&&chance<1,"Hazard rises with stable age and never forces a deadline");
                require(age<=mean/4?chance==0:chance>0,"Only the short initial grace has zero risk");previous=chance;
            }
            long total=0;int min=Integer.MAX_VALUE,max=0;var distinct=new HashSet<Integer>();
            for(int seed=0;seed<1024;seed++){
                int wait=firstDisturbance(count,observed,seed);total+=wait;min=Math.min(min,wait);max=Math.max(max,wait);distinct.add(wait);
                if(seed<8)require(wait==firstDisturbance(count,observed,seed),"The same session seed reproduces its random recurrence");
            }
            double measured=total/1024.0;
            require(Math.abs(measured/mean-1)<.06,"Measured mean retains pacing: "+observed+"/"+count+" expected "+mean+" got "+measured);
            require(min>mean/4&&min<mean*.65&&max>mean*2&&distinct.size()>mean/2,"Relapses have a broad distribution rather than a hidden deadline");
        }
        verifySpacing();verifyWinnerFairness();
        System.out.println("PASS: 36,864 seeded first-relapse samples across all disciplines/counts retain mean pacing within6%, monotonic hazard, broad timing spread and deterministic seeds.");
    }
    private static void verifySpacing(){
        var settings=slowGauge();var game=new ResearchSession(node(6,ResearchType.COGNITION),145,settings);game.begin();
        for(var type:game.activeTypes()){calibrate(game,type);game.panel(type).driftTicks=100_000;}
        long last=-1000;int events=0;var represented=EnumSet.noneOf(ResearchType.class);
        for(int tick=0;tick<4000;tick++){
            var before=new EnumMap<ResearchType,Integer>(ResearchType.class);
            for(var type:game.activeTypes())before.put(type,game.panel(type).driftTicks);
            game.tick();int changed=0;
            for(var type:game.activeTypes()){
                var panel=game.panel(type);
                if(!panel.stable){changed++;represented.add(type);}
                else require(panel.driftTicks==before.get(type)+1,"Stable age continues during the shared cooldown without resetting");
            }
            require(changed<=1,"No two instruments relapse in the same native simulation tick");
            if(changed==1){
                require(game.ticks()-last>=settings.disturbanceCooldownTicks,"Real relapse events are separated by at least one second, including four-tick UI batches");
                last=game.ticks();events++;
                for(var type:game.activeTypes())if(!game.panel(type).stable)calibrate(game,type);
            }
        }
        require(events>=10&&represented.size()==6,"All six instruments still receive disturbances despite shared spacing");
        // A harmless repeated force selection must not erase age or lower the hazard.
        var force=game.panel(ResearchType.GRAVITY);int age=force.driftTicks;force.cooldown=0;
        require(game.control(ResearchType.GRAVITY,"force",(int)force.value)&&force.driftTicks==age,"Selecting the same counterforce does not restart the stable-age clock");
        for(var type:List.of(ResearchType.SHADOW,ResearchType.TIME)){
            var panel=game.panel(type);age=panel.driftTicks;panel.cooldown=0;
            require(game.control(type,type==ResearchType.SHADOW?"angle":"speed",0)&&panel.driftTicks==age,"An unchanged dial does not restart stable age: "+type);
        }
        var shadow=game.panel(ResearchType.SHADOW);shadow.value=60;shadow.target=240;age=shadow.driftTicks;shadow.cooldown=0;
        require(game.control(ResearchType.SHADOW,"angle",1)&&shadow.value==60&&shadow.driftTicks==age,"Pressing beyond a shadow dial boundary does not reset risk");
    }
    private static void verifyWinnerFairness(){
        int[] wins=new int[6];int samples=6000;
        for(int seed=0;seed<samples;seed++){
            var game=new ResearchSession(node(6,ResearchType.COGNITION),seed,slowGauge());game.begin();
            for(var type:game.activeTypes()){calibrate(game,type);game.panel(type).driftTicks=Integer.MAX_VALUE;}
            game.tick();int changed=0;
            for(var type:game.activeTypes())if(!game.panel(type).stable){changed++;wins[type.ordinal()]++;}
            require(changed<=1,"A crowd of successful high-risk rolls produces at most one winner");
        }
        int total=Arrays.stream(wins).sum();require(total>samples*.99,"The high-hazard sample produces enough observed winners to compare fairness");
        for(var type:ResearchType.values())require(Math.abs(wins[type.ordinal()]-total/6.0)<total/6.0*.12,
                "Uniform winner selection avoids enum-order priority: "+type+"="+wins[type.ordinal()]);
    }
    private static void verifyMigration()throws Exception{
        var directory=Files.createTempDirectory("sm-stability-config-");var file=directory.resolve("research-config.json");
        Files.writeString(file,"{\"energyRequiredAlignmentTicks\":100,\"energyDriftDelayTicks\":600,\"gravityDriftDelayTicks\":1000,\"shadowDriftDelayTicks\":200,\"timeDriftDelayTicks\":200,\"spaceDriftDelayTicks\":100,\"customKey\":\"retain me\"}");
        var defaults=ResearchSettings.load(directory);
        require(defaults.energyRequiredAlignmentTicks==1&&defaults.energyDriftDelayTicks==120&&defaults.gravityDriftDelayTicks==90&&defaults.shadowDriftDelayTicks==90&&defaults.timeDriftDelayTicks==90&&defaults.cognitionDriftDelayTicks==90&&defaults.spaceDriftDelayTicks==100,"Old shipped defaults migrate to immediate waves and new mean timings while Warp retains its existing interval");
        var document=JsonParser.parseString(Files.readString(file)).getAsJsonObject();require(document.get("customKey").getAsString().equals("retain me"),"Migration preserves unknown pack fields");
        String once=Files.readString(file);ResearchSettings.load(directory);require(Files.readString(file).equals(once),"Migration is one-time and does not rewrite a current config");
        Files.writeString(file,"{\"energyDriftDelayTicks\":700,\"gravityDriftDelayTicks\":250,\"shadowDriftDelayTicks\":340,\"timeDriftDelayTicks\":480}");
        var custom=ResearchSettings.load(directory);require(custom.energyDriftDelayTicks==700&&custom.gravityDriftDelayTicks==250&&custom.shadowDriftDelayTicks==340&&custom.timeDriftDelayTicks==480,"Every customized legacy interval is preserved");
        Files.writeString(file,"{\"stabilityTimingVersion\":1,\"energyDriftDelayTicks\":600,\"spaceAlignmentMargin\":0}");
        var current=ResearchSettings.load(directory);require(current.energyDriftDelayTicks==600&&current.spaceAlignmentMargin==0,"Explicit current old-valued timing and exact lattice mode remain valid choices");
        Files.writeString(file,"{\"stabilityTimingVersion\":1,\"energyDriftDelayTicks\":90,\"energyRequiredAlignmentTicks\":100}");
        var wave=ResearchSettings.load(directory);require(wave.energyDriftDelayTicks==120&&wave.energyRequiredAlignmentTicks==1,"Recent shipped wave defaults migrate once to immediate locking and a six-second mean");
        Files.writeString(file,"{\"stabilityTimingVersion\":1,\"energyDriftDelayTicks\":300,\"energyRequiredAlignmentTicks\":40}");
        var customWave=ResearchSettings.load(directory);require(customWave.energyDriftDelayTicks==300&&customWave.energyRequiredAlignmentTicks==40,"Explicit custom wave timing and qualification remain respected");
        Files.writeString(file,"{\"stabilityTimingVersion\":1,\"waveSettingsVersion\":1,\"energyDriftDelayTicks\":90,\"energyRequiredAlignmentTicks\":100}");
        var configured=ResearchSettings.load(directory);require(configured.energyDriftDelayTicks==90&&configured.energyRequiredAlignmentTicks==100,"Current-schema administrators may intentionally select former default values");
        Files.writeString(file,"{\"spaceAlignmentMargin\":2}");String invalid=Files.readString(file);boolean rejected=false;
        try{ResearchSettings.load(directory);}catch(IllegalArgumentException expected){rejected=true;}
        require(rejected&&Files.readString(file).equals(invalid),"A tolerance that could auto-solve the weakest starts is rejected before any config write");
        Files.writeString(file,"{\"timeSpeedAdjustment\":0.3,\"timeSpeedThreshold\":1}");invalid=Files.readString(file);rejected=false;
        try{ResearchSettings.load(directory);}catch(IllegalArgumentException expected){rejected=expected.getMessage().contains("within 1x");}
        require(rejected&&Files.readString(file).equals(invalid),"An infeasible clock config gives a clear range error before migration can change its file");
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
