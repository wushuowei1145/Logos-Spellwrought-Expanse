package dev.logos.dimension.jiansui;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.logos.dimension.screenfx.ScreenCover;
import dev.logos.dimension.screenfx.ScreenEffects;
import dev.logos.dimension.taixuan.TaixuanSite;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 调试命令 {@code /alchemy jiansui ...}（docs/JIAN_SUI_BOSS.md）：
 * {@code fall} 在脚下这处太玄灵境的空洞里摆出第三阶段的场景、把自己送到起点碎块；{@code fall off} 收掉；
 * {@code cover white|black [盖上] [停] [退去]} 试全屏白、全屏黑（刻）。
 */
public final class JianSuiCommands {
    private JianSuiCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        var root = Commands.literal("jiansui");
        // 在面前 4 格召一只没有场地的剑祟（只有两条血，打空第二条就散架）
        root.then(Commands.literal("spawn").executes(c -> {
            var player = c.getSource().getPlayerOrException();
            var level = player.level();
            var boss = new JianSui(JianSuiEntities.JIAN_SUI.get(), level);
            Vec3 at = player.position().add(player.getLookAngle().multiply(1, 0, 1).normalize().scale(4));
            boss.snapTo(at.x, player.getY(), at.z, player.getYRot() + 180, 0);
            level.addFreshEntity(boss);
            boss.showBar(true);
            return 1;
        }));
        // 重置脚下这处地标的剑祟（同白梅：正常一处只能打一次）；跳到某个阶段试过场
        root.then(Commands.literal("reset").executes(c -> JianSuiFight.reset(c.getSource().getLevel(), c.getSource().getPlayerOrException().blockPosition()) ? 1 : 0));
        var skip = Commands.literal("skip");
        for (var state : JianSuiFight.State.values()) skip.then(Commands.literal(state.name().toLowerCase(java.util.Locale.ROOT))
                .executes(c -> JianSuiFight.skip(c.getSource().getLevel(), c.getSource().getPlayerOrException().blockPosition(), state) ? 1 : 0));
        root.then(skip);
        root.then(Commands.literal("fall").executes(c -> fall(c.getSource().getPlayerOrException(), true))
                .then(Commands.literal("off").executes(c -> fall(c.getSource().getPlayerOrException(), false))));
        var cover = Commands.literal("cover");
        for (String color : new String[] {"white", "black"}) {
            var out = Commands.argument("out", IntegerArgumentType.integer(0, 200)).executes(c -> cover(c.getSource().getPlayerOrException(), color,
                    IntegerArgumentType.getInteger(c, "in"), IntegerArgumentType.getInteger(c, "hold"), IntegerArgumentType.getInteger(c, "out")));
            var hold = Commands.argument("hold", IntegerArgumentType.integer(0, 400)).then(out);
            var in = Commands.argument("in", IntegerArgumentType.integer(0, 200)).then(hold);
            cover.then(Commands.literal(color).executes(c -> cover(c.getSource().getPlayerOrException(), color, 10, 20, 12)).then(in));
        }
        root.then(cover);
        return root;
    }

    private static int fall(ServerPlayer player, boolean on) {
        var level = player.level();
        TaixuanSite site = TaixuanSite.at(level, player.blockPosition());
        if (site == null) {
            player.sendSystemMessage(Component.translatable("commands.logos_dimension.jiansui.no_site"));
            return 0;
        }
        clear(level, site);
        if (!on) return 1;
        FallStage.spawn(level, site);
        Vec3 start = startFeet(site);
        player.teleportTo(level, start.x, start.y, start.z, java.util.Set.of(), player.getYRot(), 0, true);
        player.resetFallDistance();
        return 1;
    }

    /** 收掉这处地标空洞里的场景。 */
    public static void clear(ServerLevel level, TaixuanSite site) {
        Vec3 axis = site.axis(FallStage.CENTER_Y);
        for (FallStage stage : level.getEntitiesOfClass(FallStage.class, new AABB(axis, axis).inflate(40))) stage.discard();
    }

    /** 起点碎块正中、脚落在碎块上的位置。 */
    public static Vec3 startFeet(TaixuanSite site) {
        var start = TaixuanSite.cavity().getAsJsonObject("start");
        var top = start.getAsJsonArray("top");
        return site.point(top.get(0).getAsDouble(), start.get("feet").getAsInt(), top.get(2).getAsDouble());
    }

    private static int cover(ServerPlayer player, String color, int in, int hold, int out) {
        ScreenEffects.cover(player, color.equals("white") ? ScreenCover.white(in, hold, out) : ScreenCover.black(in, hold, out));
        return 1;
    }

    static @Nullable FallStage stageAt(ServerLevel level, TaixuanSite site) {
        Vec3 axis = site.axis(FallStage.CENTER_Y);
        return level.getEntitiesOfClass(FallStage.class, new AABB(axis, axis).inflate(40)).stream().findFirst().orElse(null);
    }
}
