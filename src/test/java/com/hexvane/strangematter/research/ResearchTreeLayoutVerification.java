package com.hexvane.strangematter.research;

import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Geometry invariants for the actual production router, also exports the preview's exact paths. */
public final class ResearchTreeLayoutVerification {
    public static void main(String[] args)throws Exception{
        var plans=verify();
        if(args.length>0){var path=Path.of(args[0]);Files.createDirectories(path.getParent());Files.writeString(path,new GsonBuilder().setPrettyPrinting().create().toJson(plans));}
        System.out.println("RESEARCH_TREE_LAYOUT_VERIFICATION_PASSED: all 26 default nodes, complete local prerequisites, obstacle avoiding traces, two bounded circuits and scrollable custom nodes.");
    }
    public static Map<String,ResearchTreeLayout.Plan> verify(){
        var plans=new LinkedHashMap<String,ResearchTreeLayout.Plan>();int total=0;
        for(String category:List.of("general","reality_forge")){
            var plan=ResearchTreeLayout.arrange(ResearchCatalog.nodes(),category);verifyPlan(plan);
            require(plan.height()<=481,"Default category fits without scrolling");total+=plan.nodes().size();plans.put(category,plan);
        }
        require(total==26,"All original research nodes are present");
        var custom=new ArrayList<>(ResearchCatalog.nodes());
        custom.add(new ResearchNode("test_added","general","Added research","",Map.of(ResearchType.ENERGY,1),List.of("reality_forge")));
        var extension=ResearchTreeLayout.arrange(custom,"general");verifyPlan(extension);require(extension.height()>481&&extension.nodes().size()==16,"Catalog extension remains reachable below the main map");
        var moved=new ArrayList<ResearchNode>();for(var node:ResearchCatalog.nodes())moved.add(node.id().equals("gravity_anomalies")?new ResearchNode(node.id(),"reality_forge",node.name(),node.description(),node.costs(),node.prerequisites()):node);
        verifyPlan(ResearchTreeLayout.arrange(moved,"reality_forge"));
        return plans;
    }
    private static void verifyPlan(ResearchTreeLayout.Plan plan){
        Set<String> ids=new HashSet<>();for(var node:plan.nodes()){
            require(ids.add(node.research().id()),"Unique nodes");
            require(node.x()>=0&&node.x()+ResearchTreeLayout.NODE_WIDTH<=ResearchTreeLayout.WIDTH&&node.y()>=0&&node.y()+ResearchTreeLayout.NODE_HEIGHT<=plan.height(),"Node fits: "+node.research().id());
        }
        for(int i=0;i<plan.nodes().size();i++)for(int j=i+1;j<plan.nodes().size();j++){
            var a=plan.nodes().get(i);var b=plan.nodes().get(j);
            require(!overlap(a.x(),a.y(),128,68,b.x(),b.y(),128,68),"Node cards cannot overlap");
        }
        var actual=new HashSet<String>();for(var line:plan.traces()){
            actual.add(line.parent()+":"+line.child());
            require(line.x()>=0&&line.y()>=0&&line.x()+line.width()<=728&&line.y()+line.height()<=plan.height(),"Trace fits");
            require(line.width()<=6||line.height()<=6,"Traces are orthogonal");
            for(var other:plan.nodes()){
                if(other.research().id().equals(line.parent())||other.research().id().equals(line.child()))continue;
                require(!overlap(line.x(),line.y(),line.width(),line.height(),other.x(),other.y(),128,68),"Connection crosses an unrelated card: "+line.parent()+" to "+line.child()+" crosses "+other.research().id());
            }
        }
        var expected=new HashSet<String>();for(var node:plan.nodes())for(String parent:node.research().prerequisites())if(ids.contains(parent))expected.add(parent+":"+node.research().id());
        require(expected.equals(actual),"Every same category prerequisite is connected exactly");
    }
    private static boolean overlap(int ax,int ay,int aw,int ah,int bx,int by,int bw,int bh){return ax<bx+bw&&bx<ax+aw&&ay<by+bh&&by<ay+ah;}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
