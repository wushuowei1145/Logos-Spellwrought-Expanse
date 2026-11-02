"""异世界货币的美术与数据：十三种钱币、万国钱囊、兑换台，以及模型、配方、掉落和语言条目。

全部用代码画 16×16 像素图。运行：uv run --with pillow python tools/fx_economy_art.py
"""
from pathlib import Path
from PIL import Image
import json

ROOT = Path(__file__).resolve().parent.parent
R = ROOT / 'src/main/resources'
A = R / 'assets/logos_dimension'
D = R / 'data/logos_dimension'


def write(p, v):
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(v, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def hexc(s, a=255):
    s = s.lstrip('#')
    return (int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16), a)


# 金属色板：底色、亮面、暗面、描边
METALS = {
    'copper': ('#b8683a', '#e09a62', '#7d3f22', '#4a2414'),
    'bronze': ('#9c6a3a', '#c9965c', '#694322', '#3b2412'),
    'brass': ('#c79a3e', '#ecc86a', '#8a6424', '#4d3510'),
    'silver': ('#b9bcc4', '#eef0f4', '#7c808c', '#3e414a'),
    'gold': ('#e2b23a', '#fde68a', '#a8741c', '#5a3a08'),
}


def blank():
    return Image.new('RGBA', (16, 16), (0, 0, 0, 0))


def disc(im, metal, r=6.4, cx=7.5, cy=7.5, sx=1.0, sy=1.0):
    """带描边、左上高光、右下阴影和一圈边缘的圆（或椭圆）钱币。"""
    base, light, dark, line = (hexc(c) for c in METALS[metal])
    px = im.load()
    for y in range(16):
        for x in range(16):
            dx, dy = (x - cx) / sx, (y - cy) / sy
            d = (dx * dx + dy * dy) ** .5
            if d <= r:
                c = base
                if d > r - 1.0:
                    c = line
                elif d > r - 2.0:
                    c = light if dx + dy < 0 else dark
                elif dx + dy < -r * .9:
                    c = light
                px[x, y] = c
    return base, light, dark, line


def stamp(im, rows, color, ox=0, oy=0):
    """按字符图压印纹样：'#' 用给定颜色。"""
    px = im.load()
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch == '#':
                px[x + ox, y + oy] = color


def coin_round(metal, emblem=None, emblem_color='dark', r=6.4):
    im = blank()
    base, light, dark, line = disc(im, metal, r=r)
    if emblem:
        h, w = len(emblem), len(emblem[0])
        stamp(im, emblem, dark if emblem_color == 'dark' else light, 8 - (w + 1) // 2, 8 - (h + 1) // 2)
    return im


# ---------------------------------------------------------------- 纹样

CROWN = ['#.#.#', '#####', '#####']
LAUREL = ['#...#', '#...#', '.#.#.', '..#..']
DOT = ['##', '##']
PAW = ['.#.#.', '#...#', '..#..', '.###.', '.###.']
CAT = ['#...#', '##.##', '#####', '#.#.#', '#####', '.###.']
WOLF = ['#....', '##...', '.####', '#####', '..###', '..#.#']
SC = ['##.##', '#..#.', '##.#.', '.#.#.', '##.##']


def fox_mon():
    """寛永通宝式方孔铜钱：小方孔带一圈内郭，四面各一个钱文。"""
    im = coin_round('copper')
    base, light, dark, line = (hexc(c) for c in METALS['copper'])
    px = im.load()
    for y in range(6, 10):
        for x in range(6, 10):
            px[x, y] = line if x in (6, 9) or y in (6, 9) else (0, 0, 0, 0)
    for x, y in [(7, 3), (8, 3), (11, 7), (11, 8), (7, 12), (8, 12), (3, 7), (3, 8)]:
        px[x, y] = dark
    return im


def fox_ichibu():
    """一分银：长方形银块，压着横向印记。"""
    im = blank()
    base, light, dark, line = (hexc(c) for c in METALS['silver'])
    px = im.load()
    for y in range(2, 14):
        for x in range(4, 12):
            c = base
            if x in (4, 11) or y in (2, 13):
                c = line
            elif x == 5 or y == 3:
                c = light
            elif x == 10 or y == 12:
                c = dark
            px[x, y] = c
    for y in (5, 8, 11):
        for x in range(6, 10):
            px[x, y] = dark
    px[7, 6] = dark; px[8, 9] = dark
    return im


def fox_koban():
    """小判：竖椭圆金币，满布横向锤纹（茣蓙目），上下两枚极印。"""
    im = blank()
    base, light, dark, line = (hexc(c) for c in METALS['gold'])
    px = im.load()
    for y in range(16):
        for x in range(16):
            d = ((x - 7.5) / 4.9) ** 2 + ((y - 7.5) / 7.4) ** 2
            if d > 1:
                continue
            c = base
            if d > .78:
                c = line
            elif d > .55:
                c = light if (x - 7.5) + (y - 7.5) * .6 < 0 else dark
            elif y % 2 == 0:
                c = hexc('#cf9e2e')
            px[x, y] = c
    for x, y in [(7, 3), (8, 3), (7, 12), (8, 12)]:
        px[x, y] = dark
    return im


def imperial_cent():
    return coin_round('copper', DOT, r=6.0)


def imperial_silver():
    return coin_round('silver', LAUREL)


def imperial_crown():
    return coin_round('gold', CROWN)


def cat_penny():
    return coin_round('copper', PAW, r=6.0)


def cat_shilling():
    im = coin_round('silver', PAW)
    # 铣边
    px = im.load(); line = hexc(METALS['silver'][3]); light = hexc(METALS['silver'][1])
    for x, y in [(7, 1), (13, 7), (7, 13), (1, 7), (3, 3), (12, 3), (12, 12), (3, 12)]:
        px[x, y] = light if px[x, y] == line else px[x, y]
    return im


def cat_sovereign():
    return coin_round('gold', CAT)


def wolf_as():
    return coin_round('bronze', SC, r=6.0)


def wolf_sestertius():
    return coin_round('brass', WOLF, r=7.0)


def wolf_denarius():
    return coin_round('silver', WOLF, r=5.8)


def wolf_aureus():
    im = coin_round('gold', WOLF)
    px = im.load(); dark = hexc(METALS['gold'][2])
    for x, y in [(3, 5), (3, 9), (4, 11), (12, 5), (12, 9), (11, 11)]:
        px[x, y] = dark
    return im


COINS = {
    'imperial_cent': imperial_cent, 'imperial_silver': imperial_silver, 'imperial_crown': imperial_crown,
    'fox_mon': fox_mon, 'fox_ichibu': fox_ichibu, 'fox_koban': fox_koban,
    'cat_penny': cat_penny, 'cat_shilling': cat_shilling, 'cat_sovereign': cat_sovereign,
    'wolf_as': wolf_as, 'wolf_sestertius': wolf_sestertius, 'wolf_denarius': wolf_denarius, 'wolf_aureus': wolf_aureus,
}


def wallet():
    """万国钱囊：深红锦缎束口袋，金线收口，袋口探出四色钱币。"""
    im = blank()
    px = im.load()
    cloth, light, dark, line = hexc('#8e2f3c'), hexc('#b8505a'), hexc('#5c1a26'), hexc('#2e0d14')
    gold = hexc('#e2b23a')
    # 袋身
    for y in range(6, 15):
        half = {6: 3, 7: 4, 8: 5, 9: 6, 10: 6, 11: 6, 12: 6, 13: 5, 14: 4}[y]
        for x in range(8 - half, 8 + half):
            c = cloth
            if x == 8 - half or x == 8 + half - 1 or y == 14:
                c = line
            elif x <= 8 - half + 1:
                c = light
            elif x >= 8 + half - 2 or y == 13:
                c = dark
            px[x, y] = c
    # 锦纹
    for x, y in [(6, 9), (9, 10), (7, 12), (10, 12), (5, 11)]:
        px[x, y] = hexc('#d9a441')
    # 束口与金绳
    for x in range(5, 11):
        px[x, 5] = gold
    px[4, 6] = gold; px[11, 6] = gold; px[12, 7] = gold; px[12, 8] = hexc('#a8741c')
    # 探出的钱币：帝国金、狐银、猫铜、狼铜
    for x, y, metal in [(6, 3, 'gold'), (8, 2, 'silver'), (10, 3, 'copper'), (7, 4, 'brass')]:
        base, l, d, ln = (hexc(c) for c in METALS[metal])
        px[x, y] = l; px[x + 1, y] = base; px[x, y + 1] = base; px[x + 1, y + 1] = d
    return im


def wood(seed):
    im = Image.new('RGBA', (16, 16), hexc('#7a5230'))
    px = im.load()
    tones = [hexc('#6b4628'), hexc('#7a5230'), hexc('#86603a'), hexc('#5e3d22')]
    for y in range(16):
        for x in range(16):
            v = (x * 7 + y * 13 + seed * 31 + (y // 4) * 17) % 11
            px[x, y] = tones[0 if v < 2 else 1 if v < 6 else 2 if v < 9 else 3]
        if y % 4 == 3:
            for x in range(16):
                px[x, y] = hexc('#4a301a')
    return im


def counter_top():
    """台面：漆木台面，上有一杆天平和几摞钱币。"""
    im = Image.new('RGBA', (16, 16), hexc('#5a2e22'))
    px = im.load()
    for y in range(16):
        for x in range(16):
            if x in (0, 15) or y in (0, 15):
                px[x, y] = hexc('#3a1c14')
            elif (x + y) % 9 == 0:
                px[x, y] = hexc('#6a3828')
    # 天平横杆与两个秤盘
    for x in range(3, 13):
        px[x, 5] = hexc('#c9a24a')
    px[8, 4] = hexc('#e2c06a'); px[8, 6] = hexc('#8a6a28')
    for cx in (3, 12):
        for dx in (-1, 0, 1):
            px[cx + dx, 7] = hexc('#b89040')
    # 钱币摞
    for x, y, metal in [(4, 11, 'gold'), (7, 12, 'silver'), (11, 11, 'copper'), (9, 9, 'brass')]:
        base, l, d, ln = (hexc(c) for c in METALS[metal])
        px[x, y] = l; px[x + 1, y] = base; px[x, y + 1] = d; px[x + 1, y + 1] = d
    return im


def counter_front():
    """正面：木板柜面，中间挂一块写着钱字纹样的招牌。"""
    im = wood(1)
    px = im.load()
    for x in range(16):
        px[x, 0] = hexc('#3a1c14'); px[x, 1] = hexc('#5a2e22')
    for y in range(4, 12):
        for x in range(4, 12):
            px[x, y] = hexc('#2b2420') if x in (4, 11) or y in (4, 11) else hexc('#d8c8a0')
    stamp(im, ['.###.', '#.#.#', '#####', '#.#.#', '.###.'], hexc('#9c3a2a'), 6, 5)
    return im


def counter_side():
    im = wood(2)
    px = im.load()
    for x in range(16):
        px[x, 0] = hexc('#3a1c14'); px[x, 1] = hexc('#5a2e22')
    return im


def bank_front():
    """钱庄正面：朱漆柜面，中间一道铁箍包边的库门，门上金色钱纹。"""
    im = Image.new('RGBA', (16, 16), hexc('#7a2a26'))
    px = im.load()
    for y in range(16):
        for x in range(16):
            if (x * 5 + y * 11) % 13 == 0:
                px[x, y] = hexc('#6a2220')
    for x in range(16):
        px[x, 0] = hexc('#2e1410'); px[x, 15] = hexc('#2e1410')
    for y in range(3, 15):
        for x in range(4, 12):
            px[x, y] = hexc('#3a3a42') if x in (4, 11) or y in (3, 14) else hexc('#5a4a3a')
    for y in (6, 11):
        for x in range(5, 11):
            px[x, y] = hexc('#3a3a42')
    stamp(im, ['.##.', '#..#', '#..#', '.##.'], hexc('#e2b23a'), 6, 7)
    for x, y in [(7, 8), (8, 8), (7, 9), (8, 9)]:
        px[x, y] = hexc('#2e1410')
    return im


def bank_side():
    """侧面：朱漆板，铜钉成行。"""
    im = Image.new('RGBA', (16, 16), hexc('#7a2a26'))
    px = im.load()
    for y in range(16):
        for x in range(16):
            if x in (0, 15) or y in (0, 15):
                px[x, y] = hexc('#2e1410')
            elif x in (7, 8):
                px[x, y] = hexc('#6a2220')
    for x in (3, 12):
        for y in (3, 8, 12):
            px[x, y] = hexc('#e2b23a'); px[x + 1, y + 1] = hexc('#a8741c')
    return im


def bank_top():
    """台面：黑漆台面上一把算盘。"""
    im = Image.new('RGBA', (16, 16), hexc('#2b1a16'))
    px = im.load()
    for x in range(16):
        px[x, 0] = hexc('#1a0e0a'); px[x, 15] = hexc('#1a0e0a')
    for y in range(4, 12):
        px[2, y] = hexc('#8a5a30'); px[13, y] = hexc('#8a5a30')
    for x in range(2, 14):
        px[x, 4] = hexc('#8a5a30'); px[x, 11] = hexc('#8a5a30'); px[x, 6] = hexc('#6a4020')
    for x in range(3, 13, 2):
        px[x, 5] = hexc('#c9a24a')
        for y in (8, 9, 10):
            if (x + y) % 3:
                px[x, y] = hexc('#c9a24a')
    return im


def main():
    tex = A / 'textures/item'
    tex.mkdir(parents=True, exist_ok=True)
    for name, draw in COINS.items():
        draw().save(tex / f'{name}.png')
        write(A / 'models/item' / f'{name}.json', {'parent': 'minecraft:item/generated', 'textures': {'layer0': f'logos_dimension:item/{name}'}})
        write(A / 'items' / f'{name}.json', {'model': {'type': 'minecraft:model', 'model': f'logos_dimension:item/{name}'}})
    wallet().save(tex / 'wallet.png')
    write(A / 'models/item/wallet.json', {'parent': 'minecraft:item/generated', 'textures': {'layer0': 'logos_dimension:item/wallet'}})
    write(A / 'items/wallet.json', {'model': {'type': 'minecraft:model', 'model': 'logos_dimension:item/wallet'}})

    btex = A / 'textures/block'
    counter_top().save(btex / 'exchange_counter_top.png')
    counter_front().save(btex / 'exchange_counter_front.png')
    counter_side().save(btex / 'exchange_counter_side.png')
    write(A / 'models/block/exchange_counter.json', {'parent': 'minecraft:block/orientable', 'textures': {
        'top': 'logos_dimension:block/exchange_counter_top', 'front': 'logos_dimension:block/exchange_counter_front',
        'side': 'logos_dimension:block/exchange_counter_side'}})
    write(A / 'blockstates/exchange_counter.json', {'variants': {
        f'facing={f}': {'model': 'logos_dimension:block/exchange_counter', **({'y': r} if r else {})}
        for f, r in [('north', 0), ('east', 90), ('south', 180), ('west', 270)]}})
    write(A / 'items/exchange_counter.json', {'model': {'type': 'minecraft:model', 'model': 'logos_dimension:block/exchange_counter'}})
    bank_top().save(btex / 'bank_counter_top.png')
    bank_front().save(btex / 'bank_counter_front.png')
    bank_side().save(btex / 'bank_counter_side.png')
    write(A / 'models/block/bank_counter.json', {'parent': 'minecraft:block/orientable', 'textures': {
        'top': 'logos_dimension:block/bank_counter_top', 'front': 'logos_dimension:block/bank_counter_front',
        'side': 'logos_dimension:block/bank_counter_side'}})
    write(A / 'blockstates/bank_counter.json', {'variants': {
        f'facing={f}': {'model': 'logos_dimension:block/bank_counter', **({'y': r} if r else {})}
        for f, r in [('north', 0), ('east', 90), ('south', 180), ('west', 270)]}})
    write(A / 'items/bank_counter.json', {'model': {'type': 'minecraft:model', 'model': 'logos_dimension:block/bank_counter'}})
    write(D / 'loot_table/blocks/bank_counter.json', {'type': 'minecraft:block', 'pools': [{'rolls': 1, 'entries': [
        {'type': 'minecraft:item', 'name': 'logos_dimension:bank_counter'}], 'conditions': [{'condition': 'minecraft:survives_explosion'}]}]})
    write(D / 'recipe/bank_counter.json', {'type': 'minecraft:crafting_shaped', 'category': 'misc',
        'pattern': ['GCG', 'PIP', 'PPP'], 'key': {'G': 'minecraft:gold_ingot', 'C': 'minecraft:chest', 'I': 'minecraft:iron_ingot', 'P': '#minecraft:planks'},
        'result': {'id': 'logos_dimension:bank_counter', 'count': 1}})
    write(D / 'loot_table/blocks/exchange_counter.json', {'type': 'minecraft:block', 'pools': [{'rolls': 1, 'entries': [
        {'type': 'minecraft:item', 'name': 'logos_dimension:exchange_counter'}], 'conditions': [{'condition': 'minecraft:survives_explosion'}]}]})
    # 钱币本身不能合成；钱囊和兑换台可以
    write(D / 'recipe/wallet.json', {'type': 'minecraft:crafting_shaped', 'category': 'misc',
        'pattern': [' S ', 'LGL', ' L '], 'key': {'S': 'minecraft:string', 'L': 'minecraft:leather', 'G': 'minecraft:gold_ingot'},
        'result': {'id': 'logos_dimension:wallet', 'count': 1}})
    write(D / 'recipe/exchange_counter.json', {'type': 'minecraft:crafting_shaped', 'category': 'misc',
        'pattern': ['NBN', 'PPP', 'P P'], 'key': {'N': 'minecraft:gold_nugget', 'B': 'minecraft:book', 'P': '#minecraft:planks'},
        'result': {'id': 'logos_dimension:exchange_counter', 'count': 1}})

    zh = {
        'itemGroup.logos_dimension.economy': '异世界货币',
        'block.logos_dimension.bank_counter': '钱庄',
        'screen.logos_dimension.bank.account': '账户',
        'screen.logos_dimension.bank.leverage': '杠杆',
        'screen.logos_dimension.bank.deposit': '存入',
        'screen.logos_dimension.bank.withdraw': '取出',
        'screen.logos_dimension.bank.deposit_all': '全部存入',
        'screen.logos_dimension.bank.loan': '（借款）',
        'screen.logos_dimension.bank.credit': '授信 %s · 已借 %s · 净值约 %s（含持仓）',
        'screen.logos_dimension.bank.notices': '钱庄通知',
        'screen.logos_dimension.bank.no_notices': '暂无通知',
        'screen.logos_dimension.bank.margin': '保证金（金冠）',
        'screen.logos_dimension.bank.leverage_hint': '倍数',
        'screen.logos_dimension.bank.stop': '止损价',
        'screen.logos_dimension.bank.take': '止盈价',
        'screen.logos_dimension.bank.open': '开仓',
        'screen.logos_dimension.bank.close': '平仓',
        'screen.logos_dimension.bank.long': '做多 ▲（赌它涨）',
        'screen.logos_dimension.bank.short': '做空 ▼（赌它跌）',
        'screen.logos_dimension.bank.liq_short': '爆仓',
        'screen.logos_dimension.bank.notional': '名义 ',
        'screen.logos_dimension.bank.entry_liq': '开 %s · 爆 %s 银元',
        'screen.logos_dimension.bank.overnight': '每夜利息 %s',
        'screen.logos_dimension.bank.will_borrow': '存款不够，差额向钱庄借',
        'screen.logos_dimension.bank.positions': '持仓 %s 笔',
        'screen.logos_dimension.bank.pnl': '浮动盈亏 ',
        'screen.logos_dimension.bank.no_positions': '还没有持仓',
        'screen.logos_dimension.bank.row': '保证金 %s · 开 %s · 爆 %s',
        'screen.logos_dimension.bank.crowns': '%s 金冠',
        'bank.side.long': '多',
        'bank.side.short': '空',
        'bank.notice.liquidate': '%s%s仓被强平，盈亏 %s 金冠',
        'bank.notice.stop': '%s%s仓触发止损，盈亏 %s 金冠',
        'bank.notice.take': '%s%s仓触发止盈，盈亏 %s 金冠',
        'bank.notice.close': '%s%s仓已平，盈亏 %s 金冠',
        'message.logos_dimension.bank.not_enough': '%s不够',
        'message.logos_dimension.bank.in_debt': '还欠着钱庄的钱，借款不能取现',
        'message.logos_dimension.bank.closed': '开平仓只在 6:00–18:00，存取随时可以',
        'message.logos_dimension.bank.leverage': '倍数最多 %s 倍',
        'message.logos_dimension.bank.too_many': '最多同时持有 %s 笔',
        'message.logos_dimension.bank.credit': '超出授信额度',
        'message.logos_dimension.bank.bad_stop': '止损要在亏损一侧，止盈在盈利一侧',
        'item.logos_dimension.wallet': '万国钱囊',
        'block.logos_dimension.exchange_counter': '兑换台',
        'currency.logos_dimension.imperial': '帝国币', 'currency.logos_dimension.fox': '狐钱',
        'currency.logos_dimension.cat': '猫币', 'currency.logos_dimension.wolf': '狼币',
        'item.logos_dimension.imperial_cent': '铜分', 'item.logos_dimension.imperial_silver': '银元', 'item.logos_dimension.imperial_crown': '金冠',
        'item.logos_dimension.fox_mon': '文', 'item.logos_dimension.fox_ichibu': '一分银', 'item.logos_dimension.fox_koban': '小判',
        'item.logos_dimension.cat_penny': '便士', 'item.logos_dimension.cat_shilling': '先令', 'item.logos_dimension.cat_sovereign': '金镑',
        'item.logos_dimension.wolf_as': '阿斯', 'item.logos_dimension.wolf_sestertius': '塞斯特斯',
        'item.logos_dimension.wolf_denarius': '第纳尔', 'item.logos_dimension.wolf_aureus': '奥里斯',
        'tooltip.logos_dimension.coin.worth': '面值 %s %s',
        'tooltip.logos_dimension.wallet.empty': '空空如也',
        'tooltip.logos_dimension.wallet.use': '右键打开 · 物品栏里把钱币点上来存入',
        'screen.logos_dimension.fx.amount': '数量（按最大面额）',
        'screen.logos_dimension.fx.all': '全部',
        'screen.logos_dimension.fx.when': '第 %s 天 %s', 'screen.logos_dimension.fx.when_short': '%s日%s',
        'screen.logos_dimension.fx.trade': '兑换',
        'screen.logos_dimension.fx.open': '开市', 'screen.logos_dimension.fx.closed': '休市（6:00 开门）',
        'screen.logos_dimension.fx.range_near': '近五天', 'screen.logos_dimension.fx.range_wide': '近二十天',
        'screen.logos_dimension.fx.from': '付出：%s', 'screen.logos_dimension.fx.to': '换成：%s',
        'screen.logos_dimension.fx.no_data': '还没有行情，等兑换商开门',
        'screen.logos_dimension.fx.ohlc': '开 %s 高 %s 低 %s 收 %s 银元',
        'screen.logos_dimension.fx.held': '持有：', 'screen.logos_dimension.fx.get': '得到：',
        'screen.logos_dimension.fx.cost': '点差与冲击：', 'screen.logos_dimension.fx.short': '钱不够',
        'screen.logos_dimension.fx.news': '告示与传闻',
        'screen.logos_dimension.wallet.deposit': '存入背包散币',
        'screen.logos_dimension.wallet.auto_on': '捡钱入囊：开', 'screen.logos_dimension.wallet.auto_off': '捡钱入囊：关',
        'screen.logos_dimension.wallet.locked': '已锁：结账时不会被自动换掉', 'screen.logos_dimension.wallet.unlocked': '结账钱不够时可自动换汇',
        'screen.logos_dimension.wallet.total': '合计约 ',
        'message.logos_dimension.fx.too_far': '离兑换台太远了',
        'message.logos_dimension.fx.closed': '兑换商已经打烊，明早六点开门',
        'message.logos_dimension.fx.too_small': '数额太小，换不出一个最小单位',
        'message.logos_dimension.fx.not_enough': '%s不够',
        'message.logos_dimension.fx.traded': '%s → %s',
        'fx.tier.0': '[告示]', 'fx.tier.1': '[可靠]', 'fx.tier.2': '[坊间]', 'fx.tier.3': '[小道]',
        'fx.news.festival.notice': '神狐聚落贴出告示：两日后举行狐火祭',
        'fx.news.festival.start': '狐火祭开幕，各地商旅争换狐钱（+%s%%）',
        'fx.news.mint.rumor.up': '传闻帝国铸币局要大量增发新币',
        'fx.news.mint.rumor.down': '传闻帝国要回收劣币、收紧银根',
        'fx.news.mint.up': '帝国增发新币，帝国币贬值（各币 +%s%%）',
        'fx.news.mint.down': '帝国回收劣币，帝国币走强（各币 -%s%%）',
        'fx.news.mint.fake': '帝国铸币局辟谣：币制不变',
        'fx.news.calamity.rumor': '有消息说边境出现大规模魔物异动',
        'fx.news.calamity.real': '魔潮袭来，资金涌入狐钱避险，猫币受挫（狐钱 +%s%%）',
        'fx.news.calamity.fake': '边境魔物异动是虚惊一场',
        'fx.news.council.rumor.up': '传言灵猫议会将通过通商法案',
        'fx.news.council.rumor.down': '传言灵猫议会将否决通商法案',
        'fx.news.council.up': '灵猫议会通过通商法案，猫币上涨（+%s%%）',
        'fx.news.council.down': '灵猫议会否决通商法案，猫币下跌（-%s%%）',
        'fx.news.council.fake': '灵猫议会本次并未表决通商法案',
        'fx.news.war.rumor.up': '前线传来消息：群狼占了上风',
        'fx.news.war.rumor.down': '前线传来消息：群狼节节败退',
        'fx.news.war.up': '群狼大胜，狼币大涨（+%s%%）',
        'fx.news.war.down': '群狼战败，狼币大跌（-%s%%）',
        'fx.news.war.fake': '前线战事只是谣传',
        'fx.news.harvest.rumor.up': '%s的产地有望丰收',
        'fx.news.harvest.rumor.down': '%s的产地恐遭歉收',
        'fx.news.harvest.up': '%s产地丰收（+%s%%）',
        'fx.news.harvest.down': '%s产地歉收（-%s%%）',
        'fx.news.harvest.fake': '%s产地收成如常',
        'fx.news.milestone.fox_sanctuary': '神狐聚落与外界通商，狐钱身价上扬',
    }
    en = {
        'itemGroup.logos_dimension.economy': 'Otherworld Currencies',
        'block.logos_dimension.bank_counter': 'Bank',
        'screen.logos_dimension.bank.account': 'Account',
        'screen.logos_dimension.bank.leverage': 'Margin',
        'screen.logos_dimension.bank.deposit': 'Deposit',
        'screen.logos_dimension.bank.withdraw': 'Withdraw',
        'screen.logos_dimension.bank.deposit_all': 'Deposit all',
        'screen.logos_dimension.bank.loan': '(loan)',
        'screen.logos_dimension.bank.credit': 'Credit %s · borrowed %s · net about %s (incl. positions)',
        'screen.logos_dimension.bank.notices': 'Bank notices',
        'screen.logos_dimension.bank.no_notices': 'No notices',
        'screen.logos_dimension.bank.margin': 'Margin (crowns)',
        'screen.logos_dimension.bank.leverage_hint': 'Leverage',
        'screen.logos_dimension.bank.stop': 'Stop',
        'screen.logos_dimension.bank.take': 'Take',
        'screen.logos_dimension.bank.open': 'Open',
        'screen.logos_dimension.bank.close': 'Close',
        'screen.logos_dimension.bank.long': 'Long ▲ (bet it rises)',
        'screen.logos_dimension.bank.short': 'Short ▼ (bet it falls)',
        'screen.logos_dimension.bank.liq_short': 'Liq',
        'screen.logos_dimension.bank.notional': 'Notional ',
        'screen.logos_dimension.bank.entry_liq': 'In %s · liq %s silver',
        'screen.logos_dimension.bank.overnight': 'Overnight %s',
        'screen.logos_dimension.bank.will_borrow': 'Short of funds; the bank lends the rest',
        'screen.logos_dimension.bank.positions': '%s positions',
        'screen.logos_dimension.bank.pnl': 'Open P&L ',
        'screen.logos_dimension.bank.no_positions': 'No positions',
        'screen.logos_dimension.bank.row': 'margin %s · in %s · liq %s',
        'screen.logos_dimension.bank.crowns': '%s crowns',
        'bank.side.long': ' long',
        'bank.side.short': ' short',
        'bank.notice.liquidate': '%s%s liquidated, P&L %s crowns',
        'bank.notice.stop': '%s%s stopped out, P&L %s crowns',
        'bank.notice.take': '%s%s took profit, P&L %s crowns',
        'bank.notice.close': '%s%s closed, P&L %s crowns',
        'message.logos_dimension.bank.not_enough': 'Not enough %s',
        'message.logos_dimension.bank.in_debt': 'You owe the bank; loans cannot be withdrawn',
        'message.logos_dimension.bank.closed': 'Positions trade 6:00–18:00; deposits any time',
        'message.logos_dimension.bank.leverage': 'Leverage is at most %s×',
        'message.logos_dimension.bank.too_many': 'At most %s open positions',
        'message.logos_dimension.bank.credit': 'Over your credit line',
        'message.logos_dimension.bank.bad_stop': 'Stop must be on the losing side, take on the winning side',
        'item.logos_dimension.wallet': 'Purse of Nations',
        'block.logos_dimension.exchange_counter': 'Exchange Counter',
        'currency.logos_dimension.imperial': 'Imperial', 'currency.logos_dimension.fox': 'Fox Coin',
        'currency.logos_dimension.cat': 'Cat Coin', 'currency.logos_dimension.wolf': 'Wolf Coin',
        'item.logos_dimension.imperial_cent': 'Cent', 'item.logos_dimension.imperial_silver': 'Silver', 'item.logos_dimension.imperial_crown': 'Crown',
        'item.logos_dimension.fox_mon': 'Mon', 'item.logos_dimension.fox_ichibu': 'Ichibu', 'item.logos_dimension.fox_koban': 'Koban',
        'item.logos_dimension.cat_penny': 'Penny', 'item.logos_dimension.cat_shilling': 'Shilling', 'item.logos_dimension.cat_sovereign': 'Sovereign',
        'item.logos_dimension.wolf_as': 'As', 'item.logos_dimension.wolf_sestertius': 'Sestertius',
        'item.logos_dimension.wolf_denarius': 'Denarius', 'item.logos_dimension.wolf_aureus': 'Aureus',
        'tooltip.logos_dimension.coin.worth': 'Worth %s %s',
        'tooltip.logos_dimension.wallet.empty': 'Empty',
        'tooltip.logos_dimension.wallet.use': 'Use to open · click coins onto it in the inventory to deposit',
        'screen.logos_dimension.fx.amount': 'Amount (largest coin)',
        'screen.logos_dimension.fx.all': 'All',
        'screen.logos_dimension.fx.when': 'Day %s %s', 'screen.logos_dimension.fx.when_short': 'D%s %s',
        'screen.logos_dimension.fx.trade': 'Exchange',
        'screen.logos_dimension.fx.open': 'Open', 'screen.logos_dimension.fx.closed': 'Closed (opens 6:00)',
        'screen.logos_dimension.fx.range_near': '5 days', 'screen.logos_dimension.fx.range_wide': '20 days',
        'screen.logos_dimension.fx.from': 'Pay: %s', 'screen.logos_dimension.fx.to': 'Get: %s',
        'screen.logos_dimension.fx.no_data': 'No quotes yet; wait for the changer to open',
        'screen.logos_dimension.fx.ohlc': 'O %s H %s L %s C %s silver',
        'screen.logos_dimension.fx.held': 'Held: ', 'screen.logos_dimension.fx.get': 'Get: ',
        'screen.logos_dimension.fx.cost': 'Spread & impact: ', 'screen.logos_dimension.fx.short': 'Not enough',
        'screen.logos_dimension.fx.news': 'Notices & rumors',
        'screen.logos_dimension.wallet.deposit': 'Deposit loose coins',
        'screen.logos_dimension.wallet.auto_on': 'Auto-collect: on', 'screen.logos_dimension.wallet.auto_off': 'Auto-collect: off',
        'screen.logos_dimension.wallet.locked': 'Locked: never auto-exchanged at checkout', 'screen.logos_dimension.wallet.unlocked': 'May be auto-exchanged when short at checkout',
        'screen.logos_dimension.wallet.total': 'Total about ',
        'message.logos_dimension.fx.too_far': 'Too far from the exchange counter',
        'message.logos_dimension.fx.closed': 'The money changer has closed; opens at 6:00',
        'message.logos_dimension.fx.too_small': 'Too small to get a single coin',
        'message.logos_dimension.fx.not_enough': 'Not enough %s',
        'message.logos_dimension.fx.traded': '%s → %s',
        'fx.tier.0': '[Notice]', 'fx.tier.1': '[Reliable]', 'fx.tier.2': '[Hearsay]', 'fx.tier.3': '[Whisper]',
        'fx.news.festival.notice': 'The fox sanctuary posts a notice: Foxfire Festival in two days',
        'fx.news.festival.start': 'Foxfire Festival opens; traders scramble for fox coin (+%s%%)',
        'fx.news.mint.rumor.up': 'Rumor: the imperial mint will strike a flood of new coin',
        'fx.news.mint.rumor.down': 'Rumor: the empire will recall debased coin and tighten',
        'fx.news.mint.up': 'The empire mints new coin; imperial weakens (all +%s%%)',
        'fx.news.mint.down': 'The empire recalls debased coin; imperial strengthens (all -%s%%)',
        'fx.news.mint.fake': 'The imperial mint denies any change',
        'fx.news.calamity.rumor': 'Word of great monster unrest at the border',
        'fx.news.calamity.real': 'A monster tide strikes; money flees to fox coin, cat coin slips (fox +%s%%)',
        'fx.news.calamity.fake': 'The border unrest was a false alarm',
        'fx.news.council.rumor.up': 'Hearsay: the cat council will pass the trade act',
        'fx.news.council.rumor.down': 'Hearsay: the cat council will reject the trade act',
        'fx.news.council.up': 'Cat council passes the trade act; cat coin rises (+%s%%)',
        'fx.news.council.down': 'Cat council rejects the trade act; cat coin falls (-%s%%)',
        'fx.news.council.fake': 'The cat council never voted on the trade act',
        'fx.news.war.rumor.up': 'News from the front: the wolves have the upper hand',
        'fx.news.war.rumor.down': 'News from the front: the wolves are falling back',
        'fx.news.war.up': 'The wolves win; wolf coin soars (+%s%%)',
        'fx.news.war.down': 'The wolves lose; wolf coin plunges (-%s%%)',
        'fx.news.war.fake': 'The war at the front was only rumor',
        'fx.news.harvest.rumor.up': '%s lands expect a fine harvest',
        'fx.news.harvest.rumor.down': '%s lands fear a poor harvest',
        'fx.news.harvest.up': '%s lands bring in a fine harvest (+%s%%)',
        'fx.news.harvest.down': '%s lands suffer a poor harvest (-%s%%)',
        'fx.news.harvest.fake': '%s harvest turns out ordinary',
        'fx.news.milestone.fox_sanctuary': 'The fox sanctuary opens trade with the world; fox coin gains',
    }
    for lang, values in [('zh_cn', zh), ('en_us', en)]:
        p = A / 'lang' / f'{lang}.json'
        d = json.loads(p.read_text(encoding='utf-8'))
        d.update(values)
        write(p, d)

    # 预览图：放大八倍排一行，便于审美术
    names = list(COINS) + ['wallet']
    sheet = Image.new('RGBA', (len(names) * 18 * 8, 18 * 8), hexc('#2b2620'))
    for i, n in enumerate(names):
        img = Image.open(tex / f'{n}.png').resize((128, 128), Image.NEAREST)
        sheet.alpha_composite(img, (i * 144 + 8, 8))
    out = ROOT / 'build/fx_economy_preview.png'
    out.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(out)


if __name__ == '__main__':
    main()
