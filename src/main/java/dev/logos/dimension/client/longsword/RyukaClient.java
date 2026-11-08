package dev.logos.dimension.client.longsword;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.client.SurfaceAuras;
import dev.logos.dimension.dissolve.Dissolving;
import dev.logos.dimension.item.RyukaItem;
import dev.logos.dimension.ryuka.Ryuka;
import dev.logos.dimension.longsword.LongswordPayload;
import dev.logos.runtime.SurfaceAura;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;

/**
 * 流火在客户端怎么画（docs/RYUKA.md 第 2、5 节）：
 * <ul>
 *   <li>凝聚 {@code manifest}、散去 {@code dissipate} 两段招式里，整把刀按招式进度从柄往尖长出、从尖往柄散成花瓣（{@link LongswordRig#submitDissolving}）。</li>
 *   <li>魔力耗尽（物品带 {@link Ryuka#FADED}）时只有刀身散去，留下刀柄；魔力回来时刀身重新长出。看着物品上这个标记的变化画，不另发包。</li>
 *   <li>刀身完整时：一层自发光让它像晶体，刀身燃着淡粉的狐火（Logos 物品表面火焰换色相，玩家自己的魔力缠绕照常保留）。</li>
 * </ul>
 */
public final class RyukaClient {
    /** 刀身散去、重新长出用多少刻。 */
    public static final float FADE_TICKS = 16;
    /** 狐火的色相：原本的橙红往回转到淡粉。 */
    public static final float PINK_HUE = .85f;
    private static final SurfaceAura PINK_FIRE = new SurfaceAura(SurfaceAura.Style.FIRE, PINK_HUE);
    /** 刀身自发光的强弱。 */
    private static final float GLOW = .55f;
    public static final String MANIFEST = "manifest", DISSIPATE = "dissipate";
    private static final int EDGE = Ryuka.CHERRY | Dissolving.PETALS;

    /** 每个拿着流火的人：刀身上次是否散去，以及变化发生的时刻（客户端游戏刻）。 */
    private record Fade(boolean faded, double since) {}
    private static final Map<Integer, Fade> FADES = new HashMap<>();
    private record Key(int entity, boolean firstPerson) {}
    /** 客户端测试用：给刀身加过几次狐火。 */
    private static int pinkFires;

    private RyukaClient() {}

    public static boolean is(LongswordPayload p) { return p != null && p.variant() == RyukaItem.WEAPON.id(); }

    /** 每帧看一眼这个人手上的流火：刀身散去或回来的那一刻记下来。 */
    public static void observe(LivingEntity holder, double now) {
        var stack = holder.getMainHandItem();
        if (!(stack.getItem() instanceof RyukaItem)) { FADES.remove(holder.getId()); return; }
        boolean faded = Ryuka.faded(stack);
        var last = FADES.get(holder.getId());
        // 第一次看到时按已经变完处理，不补放一次消散
        if (last == null) FADES.put(holder.getId(), new Fade(faded, now - FADE_TICKS));
        else if (last.faded != faded) FADES.put(holder.getId(), new Fade(faded, now));
    }

    /** 刀身完整、可以加狐火和自发光。 */
    public static boolean whole(LongswordRig rig, LongswordPayload p, int entity, double now) {
        if (p.move().equals(MANIFEST) || p.move().equals(DISSIPATE)) return false;
        var fade = FADES.get(entity);
        return fade == null || !fade.faded && now - fade.since >= FADE_TICKS;
    }

    /** 画流火的刀（不含手臂）：凝聚、散去、只剩刀柄、刀身重新长出，或者完整的一把。 */
    public static void submit(LongswordRig rig, LongswordPayload p, int entity, double now, LongswordRig.Sample sample, PoseStack pose,
                              SubmitNodeCollector collector, int light, boolean firstPerson) {
        var key = new Key(entity, firstPerson);
        double age = now - p.started();
        if (p.move().equals(MANIFEST) || p.move().equals(DISSIPATE)) {
            double length = rig.length(p.clip());
            float progress = length > 0 ? (float) Mth.clamp(age / 20 * p.speed() / length, 0, 1) : 1;
            rig.submitDissolving(sample, pose, collector, light, progress, p.move().equals(MANIFEST), false, EDGE, key);
            return;
        }
        var fade = FADES.get(entity);
        float since = fade == null ? FADE_TICKS : (float) (now - fade.since);
        if (fade != null && since < FADE_TICKS) {
            rig.submitDissolving(sample, pose, collector, light, since / FADE_TICKS, !fade.faded, true, EDGE, key);
            return;
        }
        boolean hilt = fade != null && fade.faded;
        rig.submit(sample, pose, collector, light, null, false, true, hilt);
        if (!hilt) rig.submitGlow(sample, pose, collector, firstPerson, GLOW, 0xFFFFFF);
    }

    /** 刀身上的淡粉狐火：换掉 Logos 给的表面效果，玩家自己的魔力缠绕留着。 */
    public static void pinkFire(ItemStackRenderState item) {
        if (item == null || item.isEmpty()) return;
        var carrier = (SurfaceAuras.Carrier) item;
        carrier.logos$aura(PINK_FIRE, carrier.logos$wrap(), carrier.logos$owner());
        pinkFires++;
    }

    public static void clear() { FADES.clear(); }

    public static int pinkFires() { return pinkFires; }
}
