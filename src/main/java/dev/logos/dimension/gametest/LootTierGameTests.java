package dev.logos.dimension.gametest;

import dev.logos.dimension.LogosDimension;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 附属箱子的分级战利品表（chests/tiers/*）：真实抽取，确认 Logos 与附属物品都抽得到、结构箱子确实引用了它们。 */
public final class LootTierGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS=DeferredRegister.create(Registries.TEST_FUNCTION,LogosDimension.ID);
    private static final List<String> BASIC_BOOKS=List.of("logos:targeting_book","logos:change_book","logos:law_book","logos:number_book","logos:motion_book");
    /** 每档必须抽得到的物品（除基础符文书外）。 */
    private static final Map<String,List<String>> TIERS=Map.of(
        "common",List.of("logos_dimension:imperial_cent","logos:mana_shard","logos:ancient_mana_elixir","logos:ancient_crystal_mana_elixir"),
        "uncommon",List.of("logos_dimension:imperial_cent","logos_dimension:imperial_silver","logos:mana_shard","logos:ancient_crystal_mana_elixir","logos:ancient_transmuted_mana_elixir","logos:ancient_chalk_mana_elixir"),
        "rare",List.of("logos_dimension:imperial_silver","logos:mana_shard","logos:principle_book","logos:ancient_echo_mana_elixir","logos:ancient_firmament_mana_elixir","logos:ancient_sage_mana_elixir"));
    /** 每档每箱保底的货币。 */
    private static final Map<String,String> COIN=Map.of("common","logos_dimension:imperial_cent","uncommon","logos_dimension:imperial_cent","rare","logos_dimension:imperial_silver");

    public static void register(IEventBus bus){
        var tests=Map.<String,Consumer<GameTestHelper>>of("loot_tiers_roll",LootTierGameTests::tiers,"loot_tiers_in_wayside",LootTierGameTests::wayside);
        tests.forEach((name,test)->FUNCTIONS.register(name,()->test));FUNCTIONS.register(bus);
        bus.addListener((RegisterGameTestsEvent event)->{
            var env=event.registerEnvironment(LogosDimension.id("loot_tiers"),new TestEnvironmentDefinition.AllOf());
            for(String name:tests.keySet())event.registerTest(LogosDimension.id(name),new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION,LogosDimension.id(name)),new TestData<>(env,Identifier.withDefaultNamespace("empty"),200,0,true,Rotation.NONE,false,1,1,false,16)));
        });
    }

    /** 抽 n 箱，返回每箱的物品 id 集合。 */
    private static List<Set<String>> roll(GameTestHelper h,Identifier table,int n){
        var loot=h.getLevel().getServer().reloadableRegistries().getLootTable(ResourceKey.create(Registries.LOOT_TABLE,table));
        var params=new LootParams.Builder(h.getLevel()).withParameter(LootContextParams.ORIGIN,h.absoluteVec(new Vec3(1,1,1))).create(LootContextParamSets.CHEST);
        var chests=new ArrayList<Set<String>>();
        for(int i=0;i<n;i++){var ids=new HashSet<String>();loot.getRandomItems(params,(ItemStack s)->ids.add(BuiltInRegistries.ITEM.getKey(s.getItem()).toString()));chests.add(ids);}
        return chests;
    }
    private static Set<String> union(List<Set<String>> chests){var all=new HashSet<String>();chests.forEach(all::addAll);return all;}

    private static void tiers(GameTestHelper h){
        TIERS.forEach((tier,expected)->{
            var chests=roll(h,LogosDimension.id("chests/tiers/"+tier),1000);var all=union(chests);
            for(var c:chests)h.assertTrue(c.contains(COIN.get(tier)),"分级表 "+tier+" 有一箱没有保底货币："+c);
            for(String id:expected)h.assertTrue(all.contains(id),"1000 箱 "+tier+" 没抽到 "+id);
            h.assertTrue(BASIC_BOOKS.stream().allMatch(all::contains),"1000 箱 "+tier+" 没抽全五本基础符文书："+all);
            h.assertTrue(all.stream().allMatch(id->id.startsWith("logos:")||id.startsWith("logos_dimension:")),"分级表只放 Logos 与附属的东西："+all);
        });
        // 低档不出高档药剂，高档不出低档药剂。
        h.assertTrue(!union(roll(h,LogosDimension.id("chests/tiers/common"),400)).contains("logos:ancient_sage_mana_elixir"),"普通档出了仙朱药剂");
        h.assertTrue(!union(roll(h,LogosDimension.id("chests/tiers/rare"),400)).contains("logos:ancient_mana_elixir"),"稀有档出了最低阶药剂");
        h.succeed();
    }

    private static void wayside(GameTestHelper h){
        for(String kind:WaysideGameTests.KINDS){
            var chests=roll(h,LogosDimension.id("chests/wayside/"+kind),300);var all=union(chests);
            for(var c:chests)h.assertTrue(c.contains("logos_dimension:imperial_cent"),"小遗迹 "+kind+" 有一箱没有经由普通档拿到铜分："+c);
            h.assertTrue(all.contains("logos:ancient_mana_elixir")&&BASIC_BOOKS.stream().anyMatch(all::contains),"300 箱小遗迹 "+kind+" 没出 Logos 的药剂或符文书："+all);
            h.assertTrue(!all.contains("logos_dimension:imperial_silver"),"小遗迹 "+kind+" 应只引用普通档");
        }
        h.succeed();
    }
    private LootTierGameTests(){}
}
