package dev.logos.dimension.ultimate;

import java.util.function.Supplier;

/**
 * 一位同伴的奥义（docs/ULTIMATES.md）：写在她的档案 {@link dev.logos.dimension.companion.CompanionDef#ultimate} 里。
 * 名字是 {@code ultimate.logos_dimension.<同伴>.name}；发动、接力、缺魔时她说的话是
 * {@code line.logos_dimension.<同伴>.ultimate_cast}、{@code ultimate_relay}、{@code ultimate_no_mana}。
 * 画面（客户端那一半）写在她的 {@code CompanionStyle#ultimate}。
 *
 * @param cooldown 冷却（刻）。记在主人身上（{@link UltimateCooldowns}），收回再叫也不清
 * @param mana     基准耗魔（主人的 Logos 魔力）；连携里第几段打几折见 {@link UltimateChain#manaFactor}
 * @param script   每次发动新开一段脚本
 */
public record UltimateDef(int cooldown, int mana, Supplier<? extends UltimateScript> script) {}
