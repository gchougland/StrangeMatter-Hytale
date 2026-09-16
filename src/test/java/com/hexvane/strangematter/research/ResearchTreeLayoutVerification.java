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
        System.out.println("RESEARCH_TREE_LAYOUT_VERIFICATION_PASSED: all 33 default nodes, complete local prerequisites, obstacle avoiding traces, two bounded circuits and scrollable custom nodes.");
    }
    public static Map<String,ResearchTreeLayout.Plan> verify(){
        var plans=new LinkedHashMap<String,ResearchTreeLayout.Plan>();int total=0;
        for(String category:List.of("general","reality_forge")){
            var plan=ResearchTreeLayout.arrange(ResearchCatalog.nodes(),category);verifyPlan(plan);
            require(plan.height()<=(category.equals("general")?481:908),"Foundation map stays compact and Forge branches fit their scrolling canvas");total+=plan.nodes().size();plans.put(category,plan);
            if(category.equals("reality_forge"))require(plan.nodes().stream().filter(n->Set.of("resonant_separation","flux_smelting","gravitic_transport","pattern_assembly").contains(n.research().id())).count()==4,"Every automation discovery is reachable in the native scrolling map");
        }
        require(total==33,"Original research nodes, automation and three gadget discoveries are present");
        verifyForgeBranches(plans.get("reality_forge"));
        var foundations=plans.get("general");int[][] original={{388,236},{212,236},{564,236},{388,160},{212,312},{564,312},{36,312},{36,236},{212,388},{212,84},{388,84},{564,84},{212,8},{388,8},{564,8}};
        for(int i=0;i<original.length;i++)require(foundations.nodes().get(i).x()==original[i][0]&&foundations.nodes().get(i).y()==original[i][1],"Every original Foundation position stays unchanged");
        var custom=new ArrayList<>(ResearchCatalog.nodes());
        custom.add(new ResearchNode("test_added","general","Added research","",Map.of(ResearchType.ENERGY,1),List.of("reality_forge")));
        var extension=ResearchTreeLayout.arrange(custom,"general");verifyPlan(extension);require(extension.height()>481&&extension.nodes().size()==16,"Catalog extension remains reachable below the main map");
        var moved=new ArrayList<ResearchNode>();for(var node:ResearchCatalog.nodes())moved.add(node.id().equals("gravity_anomalies")?new ResearchNode(node.id(),"reality_forge",node.name(),node.description(),node.costs(),node.prerequisites()):node);
        verifyPlan(ResearchTreeLayout.arrange(moved,"reality_forge"));
        // The fourth extra card occupies the default transport bus. A later
        // transport descendant must fall back to obstacle routing around it.
        var extendedForge=new ArrayList<>(ResearchCatalog.nodes());
        for(int i=0;i<6;i++)extendedForge.add(new ResearchNode("extended_"+i,"reality_forge","Added technology "+i,"",Map.of(ResearchType.ENERGY,1),List.of(i==5?"gravitic_transport":"reality_forge")));
        var customForge=ResearchTreeLayout.arrange(extendedForge,"reality_forge");verifyPlan(customForge);
        require(customForge.traces().stream().anyMatch(s->s.parent().equals("gravitic_transport")&&s.child().equals("extended_5")),"Custom discoveries keep their edge when an added card obstructs a default branch lane");
        return plans;
    }
    private static void verifyForgeBranches(ResearchTreeLayout.Plan plan){
        var nodes=new HashMap<String,ResearchTreeLayout.Node>();for(var node:plan.nodes())nodes.put(node.research().id(),node);
        var root=nodes.get("reality_forge_category");var transport=nodes.get("gravitic_transport");
        var connection=plan.traces().stream().filter(s->s.parent().equals("reality_forge")&&s.child().equals("gravitic_transport")).toList();
        require(!connection.isEmpty()&&connection.stream().allMatch(s->s.source().equals(root.research().id())),"Canonical Forge prerequisite is routed from its visible category proxy");
        require(connection.stream().anyMatch(s->covers(s,root.cx(),root.cy()))&&connection.stream().anyMatch(s->covers(s,transport.cx(),transport.cy())),"The missing connection physically reaches both node icons");
        require(plan.traces().stream().noneMatch(s->s.source().equals(s.child())),"The category proxy never creates a self connection");
        require(transport.research().prerequisites().equals(List.of("reality_forge")),"Rendering does not rewrite the canonical prerequisite");
        require(ResearchTreeLayout.visibleParent("reality_forge",Set.of("reality_forge","reality_forge_category")).equals("reality_forge"),"A visible canonical node takes precedence over its proxy");
        for(var line:plan.traces())require(nodes.get(line.source()).y()<nodes.get(line.child()).y(),"Default Forge progression flows downward through readable tiers");
        for(var id:List.of("resonant_separation","flux_smelting","pattern_assembly"))require(nodes.get(id).x()==transport.x(),"Processing discoveries stay in the transport column");
        var containment=nodes.get("containment_basics");
        for(var id:List.of("echoform_imprinter","warp_gun","chrono_blister","graviton_hammer","hoverboard"))require(nodes.get(id).y()>containment.y()&&nodes.get(id).x()<transport.x(),"Containment equipment stays below its parent and outside the transport lane");
        for(int i=0;i<plan.traces().size();i++)for(int j=i+1;j<plan.traces().size();j++){
            var a=plan.traces().get(i);var b=plan.traces().get(j);
            String familyA=a.parent().replace("reality_forge_category","reality_forge"),familyB=b.parent().replace("reality_forge_category","reality_forge");
            if(familyA.equals(familyB)||!overlap(a.x(),a.y(),a.width(),a.height(),b.x(),b.y(),b.width(),b.height()))continue;
            int x=Math.max(a.x(),b.x()),y=Math.max(a.y(),b.y());
            require(plan.nodes().stream().anyMatch(n->(n.research().id().equals(a.child())&&n.research().id().equals(b.source())
                    ||n.research().id().equals(b.child())&&n.research().id().equals(a.source())
                    ||n.research().id().equals(a.child())&&n.research().id().equals(b.child()))
                    &&x>=n.x()&&x<n.x()+128&&y>=n.y()&&y<n.y()+68),"Different prerequisite branches cannot merge into a misleading shared bus: "+a+" / "+b);
        }
        require(plan.height()>464,"All Forge branches remain reachable through native scrolling");
    }
    private static boolean covers(ResearchTreeLayout.Segment s,int x,int y){return x>=s.x()&&y>=s.y()&&x<s.x()+s.width()&&y<s.y()+s.height();}
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
                if(other.research().id().equals(line.source())||other.research().id().equals(line.child()))continue;
                require(!overlap(line.x(),line.y(),line.width(),line.height(),other.x(),other.y(),128,68),"Connection crosses an unrelated card: "+line.parent()+" to "+line.child()+" crosses "+other.research().id());
            }
        }
        var expected=new HashSet<String>();for(var node:plan.nodes())for(String parent:node.research().prerequisites()){String visible=ResearchTreeLayout.visibleParent(parent,ids);if(ids.contains(visible)&&!visible.equals(node.research().id()))expected.add(parent+":"+node.research().id());}
        require(expected.equals(actual),"Every visible local or proxy prerequisite is connected exactly, using its canonical state ID");
    }
    private static boolean overlap(int ax,int ay,int aw,int ah,int bx,int by,int bw,int bh){return ax<bx+bw&&bx<ax+aw&&ay<by+bh&&by<ay+ah;}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
