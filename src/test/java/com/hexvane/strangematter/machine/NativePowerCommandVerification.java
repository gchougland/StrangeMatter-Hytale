package com.hexvane.strangematter.machine;

import com.hexvane.strangematter.StrangeMatterCommand;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.*;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.permissions.provider.HytalePermissionsProvider;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Real native command parsing, permissions and production guards without invoking an audit. */
public final class NativePowerCommandVerification {
    public static void verify()throws Exception{
        // Deliberately no services: every exercised command path must finish before inspection or report IO.
        var root=new StrangeMatterCommand(null,null,null,null,null);
        // Native command registration builds the optional-name/abbreviation lookup used by acceptCall.
        root.completeRegistration();
        var power=root.getSubCommands().get("power");var diagnose=power.getSubCommands().get("diagnose");
        require(root.getAliases().contains("sm")&&diagnose!=null,"Native /sm power diagnose command and alias exist");
        for(var command:List.of(power,diagnose))require("strangematter.admin".equals(command.getPermission())&&command.getPermissionGroups().isEmpty(),"Power inspection is explicitly administrator-only");
        require(!root.getPermissionGroupsRecursive().getOrDefault(HytalePermissionsProvider.GROUP_ADVENTURER,Set.of()).contains("strangematter.admin"),"The public command root does not grant power diagnostics to Adventurers");
        require(diagnose.getRequiredArguments().isEmpty()&&diagnose.getOptionalArguments().keySet().equals(Set.of("x","y","z","radius")),"Native help exposes four optional named arguments and no required positional argument");
        for(var argument:diagnose.getOptionalArguments().values()){
            require(argument instanceof OptionalArg<?> &&argument.getArgumentType()==ArgTypes.INTEGER,"Coordinates and radius use the real native integer parser");
            require(diagnose.hasBeenRegistered()&&diagnose.getOptionalArgument(argument.getName())==argument,"Native registration resolves each declared option for actual command execution");
        }

        var denied=new Sender(false);var deniedResult=call(root,denied,"sm power diagnose --x 1 --y 2 --z 3");
        require(deniedResult.failed()&&!denied.messages.isEmpty(),"The real command dispatcher rejects an unprivileged sender before execution");
        require(!power.hasPermission(denied)&&!diagnose.hasPermission(denied),"Both collection and leaf enforce the administrator permission");
        var admin=new Sender(true);require(power.hasPermission(admin)&&diagnose.hasPermission(admin),"The explicit administrator permission authorizes the diagnostic command");
        require(!call(root,admin,"sm power diagnose --help").failed()&&!admin.messages.isEmpty(),"Native --help succeeds without a player, world, machine service or report");
        for(String arguments:List.of("--x nope","--y 1.5","--z 2147483648","--radius 2147483648","--radius tiny","--radius 1.5","--radius","--unknown 32","1 2 3")){
            var sender=new Sender(true);require(call(root,sender,"sm power diagnose "+arguments).failed(),"Native parsing rejects malformed input before a report can run: "+arguments);
        }
        for(String arguments:List.of("","--radius 1","--radius 128","--x -2147483648 --y 0 --z 2147483647 --radius 32")){
            var sender=new Sender(true);var parsed=call(root,sender,"sm power diagnose "+arguments);
            require(!parsed.failed()&&sender.messages.size()==1&&"server.commands.errors.playerOrArg".equals(sender.messages.getFirst().getMessageId()),
                    "Valid native typed input reaches the player-only guard and cannot run from the console: "+arguments+"; parseFailed="+parsed.failed()+"; replies="+sender.messages);
        }

        var parseOptionals=AbstractCommand.class.getDeclaredMethod("processOptionalArguments",ParserContext.class,ParseResult.class,CommandContext.class);parseOptionals.setAccessible(true);
        var execute=diagnose.getClass().getDeclaredMethod("execute",CommandContext.class,Store.class,Ref.class,PlayerRef.class,World.class);execute.setAccessible(true);
        for(String arguments:List.of("--x 0","--y -1","--z 2","--x 0 --y 1","--x 0 --z 2","--y 1 --z 2","--x 0 --radius 1","--x 0 --radius 128")){
            var sender=new Sender(true);var context=parsed(diagnose,sender,arguments,parseOptionals);
            execute.invoke(diagnose,context,null,null,null,null);
            require(sender.messages.size()==1&&"Provide --x, --y and --z together, or aim at the network.".equals(sender.messages.getFirst().getRawText()),"Partial coordinates stop before target lookup, audit or report IO: "+arguments);
        }
        for(String radius:List.of("0","-1","129","-2147483648","2147483647")){
            var sender=new Sender(true);var context=parsed(diagnose,sender,"--x 0 --y 1 --z 2 --radius "+radius,parseOptionals);
            execute.invoke(diagnose,context,null,null,null,null);
            require(sender.messages.size()==1&&"Radius must be from 1 to 128 blocks.".equals(sender.messages.getFirst().getRawText()),"Out-of-range radius stops before audit or report IO: "+radius);
        }
        var omitted=parsed(diagnose,new Sender(true),"",parseOptionals);
        for(var argument:diagnose.getOptionalArguments().values())require(omitted.get(argument)==null&&!omitted.provided(argument),"Omitted optional arguments stay absent for aim/default-radius handling");
        System.out.println("NATIVE_POWER_COMMAND_VERIFICATION_PASSED: real native command tree, explicit admin permission, typed optional integer parsing/help, console rejection, six partial-coordinate combinations and radius bounds rejected before target lookup, audit or report IO.");
    }

    private static ParseResult call(AbstractCommand command,Sender sender,String input){
        var result=new ParseResult();var parser=ParserContext.of(Tokenizer.parseArguments(input,result),input,result);
        require(!result.failed(),"Fixture command tokenizes correctly: "+input);
        var completion=command.acceptCall(sender,parser,result);
        if(completion!=null){require(completion.isDone(),"Non-player command tests must not schedule work on a world");completion.join();}
        result.sendMessages(sender);return result;
    }
    private static CommandContext parsed(AbstractCommand command,Sender sender,String arguments,java.lang.reflect.Method parseOptionals)throws Exception{
        String input="diagnose "+arguments;var result=new ParseResult();
        var parser=ParserContext.of(Tokenizer.parseArguments(input,result),input,result);parser.convertToSubCommand();
        var context=new CommandContext(command,sender,input);parseOptionals.invoke(command,parser,result,context);
        require(!result.failed(),"Guard fixture uses successfully parsed native typed arguments: "+arguments);return context;
    }
    private static final class Sender implements CommandSender {
        private final boolean admin;private final UUID id=UUID.randomUUID();private final List<Message> messages=new ArrayList<>();
        Sender(boolean admin){this.admin=admin;}
        @Override public String getUsername(){return "PowerCommandVerification";}
        @Override public UUID getUuid(){return id;}
        @Override public boolean hasPermission(String permission){return admin&&"strangematter.admin".equals(permission);}
        @Override public boolean hasPermission(String permission,boolean fallback){return hasPermission(permission);}
        @Override public void sendMessage(Message message){messages.add(message);}
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
