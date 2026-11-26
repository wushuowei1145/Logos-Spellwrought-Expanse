package dev.logos.dimension.firearm;

import com.lowdragmc.lowdraglib2.nodegraphtookit.api.node.NodeAttribute;
import dev.logos.graph.RuneNode;
import dev.logos.graph.RuneNodes;
import dev.logos.graph.SpellGraph;

/**
 * 术式工坊里三种弹的节点（{@link SpellRounds}）：端口来自 Logos 里登记的寄托形体，只有三个事件输出。
 * <p>
 * 和 Logos 的 {@code RuneNodes} 一样，这里的类不能改名或挪走：存下来的节点图按 Java 类名记每个节点
 * （{@code dev.logos.dimension.firearm.RoundNodes$Rifle}），类名一变，图纸上的这个节点就读不出来了。
 */
public final class RoundNodes {
    private RoundNodes() {}

    @NodeAttribute(name = "buckshot_round", group = RuneNodes.FORM, graphTypes = SpellGraph.class)
    public static class Buckshot extends RuneNode {
        public Buckshot() {
            super(SpellRounds.BUCKSHOT);
        }
    }

    @NodeAttribute(name = "rifle_round", group = RuneNodes.FORM, graphTypes = SpellGraph.class)
    public static class Rifle extends RuneNode {
        public Rifle() {
            super(SpellRounds.RIFLE);
        }
    }

    @NodeAttribute(name = "blank_round", group = RuneNodes.FORM, graphTypes = SpellGraph.class)
    public static class Blank extends RuneNode {
        public Blank() {
            super(SpellRounds.BLANK);
        }
    }
}
