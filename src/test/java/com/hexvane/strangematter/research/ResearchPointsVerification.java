package com.hexvane.strangematter.research;

import com.hexvane.strangematter.StrangeMatterCommand;
import com.hypixel.hytale.server.core.command.system.ParseResult;
import com.hypixel.hytale.server.core.command.system.suggestion.SuggestionResult;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Awards all six balances atomically and exercises the native argument/help contract. */
public final class ResearchPointsVerification {
    public static void main(String[] args) throws Exception {
        var directory=Files.createTempDirectory("sm-all-points-");
        UUID player=UUID.randomUUID(), other=UUID.randomUUID(), full=UUID.randomUUID();
        Map<ResearchType,Integer> expected;
        try(var service=new ResearchService(directory)){
            service.addPoints(player,ResearchType.ENERGY,7);
            service.addPoints(player,ResearchType.TIME,13);
            var before=service.profile(player).points();
            service.addPointsAll(player,25);
            for(var type:ResearchType.values()){
                require(service.points(player,type)==before.get(type)+25,"Every discipline receives the full award");
                require(service.points(other,type)==0,"Other players retain their balances");
            }
            expected=service.profile(player).points();
            service.addPoints(full,ResearchType.TIME,Integer.MAX_VALUE);
            var fullBefore=service.profile(full).points();
            var savedBefore=Files.readString(directory.resolve("research.properties"));
            try{service.addPointsAll(full,1);throw new AssertionError("Overflow must reject the award");}
            catch(ArithmeticException rejected){
                require(service.profile(full).points().equals(fullBefore),"Overflow in the last discipline rolls back every balance");
                require(Files.readString(directory.resolve("research.properties")).equals(savedBefore),"Rejected award leaves the saved ledger untouched");
            }
            try{service.addPointsAll(player,-1);throw new AssertionError("Negative awards must fail");}
            catch(IllegalArgumentException rejected){require(service.profile(player).points().equals(expected),"Rejected negative award does not spend points");}
            var command=new StrangeMatterCommand(service,null,null,null,null);
            var published=command.getPermissionGroupsRecursive();
            String adventurer=com.hypixel.hytale.server.core.permissions.provider.HytalePermissionsProvider.GROUP_ADVENTURER;
            require(!published.getOrDefault(adventurer,java.util.Set.of()).contains("strangematter.admin"),
                    "Native recursive group publication must not grant administration to ordinary Adventurers");
            for(String id:List.of("kit","scientist","save","spawn","locate","points","pointsall","research","unlock")){
                var admin=command.getSubCommands().get(id);
                require("strangematter.admin".equals(admin.getPermission())&&admin.getPermissionGroups()!=null&&admin.getPermissionGroups().isEmpty(),
                        "Every administrative branch explicitly stops inherited default groups: "+id);
            }
            var nested=command.getSubCommands().get("research").getSubCommands().get("unlock");
            require(nested.getPermissionGroups()!=null&&nested.getPermissionGroups().isEmpty(),"Nested unlock cannot inherit public groups on reparenting");
            for(String id:List.of("help","journal","status","achievements"))require(command.getSubCommands().get(id).getPermissionGroups().contains(adventurer),"Ordinary player command stays available: "+id);
            var all=command.getSubCommands().get("pointsall");
            require(all!=null&&"strangematter.admin".equals(all.getPermission()),"All points subcommand requires the existing admin permission");
            require(all.getRequiredArguments().size()==1&&all.getOptionalArguments().containsKey("player"),"Native help exposes amount and optional target");
            var points=command.getSubCommands().get("points");
            require(points.getRequiredArguments().size()==2&&"strangematter.admin".equals(points.getPermission()),"Existing per discipline command keeps its arguments and permission");
            var parser=StrangeMatterCommand.disciplineArgument();
            var valid=new ParseResult();require("all".equals(parser.parse("ALL",valid))&&!valid.failed(),"Points all selector is case insensitive");
            for(var type:ResearchType.values())require(type.getName().equals(parser.parse(type.name(),new ParseResult())),"Existing discipline selectors remain accepted");
            var invalid=new ParseResult();require(parser.parse("invented",invalid)==null&&invalid.failed(),"Unknown discipline cannot turn into an all types award");
            var suggestions=new SuggestionResult();parser.suggest(null,"a",0,suggestions);
            require(suggestions.getSuggestions().equals(List.of("all")),"Native autocomplete offers all");
            require(command.getSubCommands().containsKey("achievements"),"Native help exposes the achievement journal");
        }
        try(var restored=new ResearchService(directory)){require(restored.profile(player).points().equals(expected),"Every awarded balance survives reload");}
        System.out.println("RESEARCH_POINTS_VERIFICATION_PASSED: all six balances, one saved transaction, overflow rollback, player isolation, reload and native command permissions/help/autocomplete.");
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
