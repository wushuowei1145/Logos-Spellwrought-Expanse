package dev.logos.dimension.wind;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * 调试命令：看当前风、强制指定强度、恢复自动。
 *
 * <pre>
 * /logoswind            查看当前风
 * /logoswind set 0.8    强制强度 0.8（草浪立刻变大，方便调参）
 * /logoswind auto       恢复自动风场
 * </pre>
 */
public final class WindCommands {
    private WindCommands(){}

    public static void register(RegisterCommandsEvent event){
        CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcher=event.getDispatcher();
        var root=Commands.literal("logoswind");
        root.executes(WindCommands::show);
        root.then(Commands.literal("show").executes(WindCommands::show));
        root.then(Commands.literal("auto").executes(c->{
            WindField.setOverride(null);
            feedback(c.getSource(),"风场已恢复自动");
            return 1;
        }));
        root.then(Commands.literal("set")
            .then(Commands.argument("strength",FloatArgumentType.floatArg(0f,WindField.STORM_MAX))
                .executes(c->{
                    float value=FloatArgumentType.getFloat(c,"strength");
                    WindField.setOverride(value);
                    feedback(c.getSource(),"风场已锁定为 "+String.format(java.util.Locale.ROOT,"%.2f",value));
                    return 1;
                })));
        dispatcher.register(root);
    }

    private static int show(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> c){
        var level=c.getSource().getLevel();
        var wind=WindField.of(level);
        var override=WindField.override();
        feedback(c.getSource(),String.format(java.util.Locale.ROOT,
            "风：强度 %.2f｜方向 %.0f°｜波速 %.2f%s",
            wind.strength(),Math.toDegrees(wind.angle()),wind.speed(),
            override==null?"":"（已锁定）"));
        return 1;
    }

    private static void feedback(net.minecraft.commands.CommandSourceStack source,String message){
        source.sendSuccess(()->Component.literal(message),false);
    }
}
