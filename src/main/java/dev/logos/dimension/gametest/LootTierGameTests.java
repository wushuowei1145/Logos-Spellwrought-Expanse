package dev.logos.dimension.gametest;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.luck.Luck;
import dev.logos.dimension.luck.LuckKind;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
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
        var tests=Map.<String,Consumer<GameTestHelper>>of("loot_tiers_roll",LootTierGameTests::tiers,"loot_tiers_in_wayside",LootTierGameTests::wayside,
            "loot_tiers_follow_fortune",LootTierGameTests::fortune,"loot_tiers_follow_kinds",LootTierGameTests::kinds);
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
    /** 走原版开箱的路：放一只箱子、挂上表，让这个玩家（可以是 null）第一次打开，取出内容后清空。 */
    private static List<ItemStack> open(GameTestHelper h,ServerPlayer player,Identifier table){
        var level=h.getLevel();var pos=h.absolutePos(new BlockPos(1,1,1));
        level.setBlock(pos,Blocks.CHEST.defaultBlockState(),Block.UPDATE_ALL);
        var chest=(ChestBlockEntity)level.getBlockEntity(pos);
        chest.setLootTable(ResourceKey.create(Registries.LOOT_TABLE,table),level.getRandom().nextLong());
        chest.unpackLootTable(player);
        var items=new ArrayList<ItemStack>();
        for(int slot=0;slot<chest.getContainerSize();slot++)if(!chest.getItem(slot).isEmpty())items.add(chest.getItem(slot).copy());
        chest.clearContent();
        return items;
    }
    /** 开 n 箱，数出符合条件的物品总数。 */
    private static int count(GameTestHelper h,ServerPlayer player,Identifier table,int n,java.util.function.Predicate<String> id){
        int total=0;
        for(int i=0;i<n;i++)for(var s:open(h,player,table))if(id.test(BuiltInRegistries.ITEM.getKey(s.getItem()).toString()))total+=s.getCount();
        return total;
    }
    private static ServerPlayer drew(GameTestHelper h,int vanilla,Map<LuckKind,Integer> kinds){
        var player=h.makeMockServerPlayerInLevel();
        if(vanilla!=0||!kinds.isEmpty())Luck.grant(player,vanilla,kinds);
        return player;
    }
    private static final java.util.function.Predicate<String> LOGOS=id->id.startsWith("logos:");

    /** 总签运：原版幸运抬高或压低整体出率（Logos 物品、小遗迹的金苹果），不动钱币。 */
    private static void fortune(GameTestHelper h){
        var great=drew(h,2,Map.of());var bad=drew(h,-2,Map.of());
        h.assertTrue(great.getLuck()==2&&bad.getLuck()==-2,"抽签没给到原版幸运："+great.getLuck()+" / "+bad.getLuck());
        var common=LogosDimension.id("chests/tiers/common");
        int none=count(h,null,common,400,LOGOS),up=count(h,great,common,400,LOGOS),down=count(h,bad,common,400,LOGOS);
        h.assertTrue(up>none&&none>down,"400 箱普通档的 Logos 物品应随幸运增减：大吉 "+up+"、不开签 "+none+"、大凶 "+down);
        var wayside=LogosDimension.id("chests/wayside/root_cradle");
        java.util.function.Predicate<String> apple="minecraft:golden_apple"::equals;
        int apples=count(h,great,wayside,400,apple),cursed=count(h,bad,wayside,400,apple);
        h.assertTrue(apples>0&&cursed==0,"小遗迹的金苹果应随幸运变：大吉 "+apples+"、大凶 "+cursed);
        h.succeed();
    }

    /** 各项运势：财运管钱币（大利才可能有金冠），法运管 Logos 的东西；没抽到这一项的照常。 */
    private static void kinds(GameTestHelper h){
        var common=LogosDimension.id("chests/tiers/common");
        var rich=drew(h,0,Map.of(LuckKind.WEALTH,2));var poor=drew(h,0,Map.of(LuckKind.WEALTH,-2));
        var sage=drew(h,0,Map.of(LuckKind.SPELL,2));var dull=drew(h,0,Map.of(LuckKind.SPELL,-2));
        java.util.function.Predicate<String> cent="logos_dimension:imperial_cent"::equals,crown="logos_dimension:imperial_crown"::equals;
        int cents=count(h,null,common,300,cent),richCents=count(h,rich,common,300,cent),poorCents=count(h,poor,common,300,cent);
        h.assertTrue(richCents>cents&&cents>poorCents,"300 箱普通档的铜分应随财运增减：大利 "+richCents+"、平 "+cents+"、大不利 "+poorCents);
        h.assertTrue(count(h,rich,common,600,crown)>0,"财运大利 600 箱普通档一个金冠都没有");
        h.assertTrue(count(h,null,common,600,crown)==0&&count(h,sage,common,300,crown)==0,"财运没到大利不该出金冠");
        int logos=count(h,null,common,400,LOGOS),wise=count(h,sage,common,400,LOGOS),dim=count(h,dull,common,400,LOGOS);
        h.assertTrue(wise>logos&&logos>dim,"400 箱普通档的 Logos 物品应随法运增减：大利 "+wise+"、平 "+logos+"、大不利 "+dim);
        h.succeed();
    }
    private LootTierGameTests(){}
}
