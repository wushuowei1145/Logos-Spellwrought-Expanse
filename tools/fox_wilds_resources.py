"""灵狐樱野 · 和风特色建材与核心构件资产生成脚本：
代码逐像素绘制 Minecraft 原版风格 16x16 贴图，并生成对应的 blockstates、models、loot_tables 与 lang 条目。
"""
from pathlib import Path
import json, math
import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "src/main/resources"
DATA = RES / "data/logos_dimension"
ASSETS = RES / "assets/logos_dimension"
NS = "logos_dimension"

TEXTURE_DIR = ASSETS / "textures/block"
PARTICLE_DIR = ASSETS / "textures/particle"
MODEL_BLOCK_DIR = ASSETS / "models/block"
MODEL_ITEM_DIR = ASSETS / "models/item"
BLOCKSTATE_DIR = ASSETS / "blockstates"
ITEM_DIR = ASSETS / "items"
LOOT_DIR = DATA / "loot_table/blocks"

for p in [TEXTURE_DIR, PARTICLE_DIR, MODEL_BLOCK_DIR, MODEL_ITEM_DIR, BLOCKSTATE_DIR, ITEM_DIR, LOOT_DIR]:
    p.mkdir(parents=True, exist_ok=True)


def write_json(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def simple_loot_table(id_name):
    return {
        "type": "minecraft:block",
        "pools": [
            {
                "rolls": 1.0,
                "bonus_rolls": 0.0,
                "entries": [
                    {
                        "type": "minecraft:item",
                        "name": f"{NS}:{id_name}"
                    }
                ],
                "conditions": [
                    {
                        "condition": "minecraft:survives_explosion"
                    }
                ]
            }
        ],
        "random_sequence": f"{NS}:blocks/{id_name}"
    }


def slab_loot_table(id_name):
    return {
        "type": "minecraft:block",
        "pools": [
            {
                "bonus_rolls": 0.0,
                "entries": [
                    {
                        "type": "minecraft:item",
                        "functions": [
                            {
                                "add": False,
                                "conditions": [
                                    {
                                        "block": f"{NS}:{id_name}",
                                        "condition": "minecraft:block_state_property",
                                        "properties": {
                                            "type": "double"
                                        }
                                    }
                                ],
                                "count": 2.0,
                                "function": "minecraft:set_count"
                            },
                            {
                                "function": "minecraft:explosion_decay"
                            }
                        ],
                        "name": f"{NS}:{id_name}"
                    }
                ],
                "rolls": 1.0
            }
        ],
        "random_sequence": f"{NS}:blocks/{id_name}"
    }


def door_loot_table(id_name):
    return {
        "type": "minecraft:block",
        "pools": [
            {
                "bonus_rolls": 0.0,
                "entries": [
                    {
                        "type": "minecraft:item",
                        "conditions": [
                            {
                                "block": f"{NS}:{id_name}",
                                "condition": "minecraft:block_state_property",
                                "properties": {
                                    "half": "lower"
                                }
                            }
                        ],
                        "name": f"{NS}:{id_name}"
                    }
                ],
                "conditions": [
                    {
                        "condition": "minecraft:survives_explosion"
                    }
                ],
                "rolls": 1.0
            }
        ],
        "random_sequence": f"{NS}:blocks/{id_name}"
    }


# ==================== 1. 贴图绘制 (Pixel Art) ====================

def make_plaster_texture():
    rng = np.random.default_rng(20261102)
    base = np.array([244, 239, 230], dtype=float)
    noise = rng.normal(0, 3.5, (16, 16, 1))
    yy, xx = np.mgrid[:16, :16]
    wave = np.sin(yy * 0.8 + xx * 0.2) * 1.8
    rgb = np.clip(base + noise + wave[:, :, None], 228, 252).astype(np.uint8)
    im = Image.fromarray(rgb, "RGB").convert("RGBA")
    im.save(TEXTURE_DIR / "journey_white_plaster.png")


def make_wainscot_texture():
    im = Image.new("RGBA", (16, 16))
    d = ImageDraw.Draw(im)
    d.rectangle([(0, 0), (15, 0)], fill=(72, 54, 40, 255))
    d.rectangle([(0, 1), (15, 1)], fill=(96, 74, 55, 255))
    d.rectangle([(0, 2), (15, 2)], fill=(58, 42, 30, 255))
    for x in range(16):
        col = x % 4
        for y in range(3, 16):
            if col == 0:
                color = (68, 52, 38, 255)
            elif col == 1:
                color = (118, 92, 68, 255)
            elif col == 2:
                noise = (x * 7 + y * 13) % 5
                color = (106 + noise, 82 + noise, 60 + noise, 255)
            else:
                color = (92, 70, 52, 255)
            d.point((x, y), fill=color)
    im.save(TEXTURE_DIR / "journey_wood_wainscot.png")


def make_kawara_roof_texture():
    im = Image.new("RGBA", (16, 16))
    d = ImageDraw.Draw(im)
    for row_y in (0, 8):
        for x in range(16):
            bx = x % 4
            for dy in range(8):
                y = row_y + dy
                if dy == 0:
                    c = 78 if bx in (1, 2) else 65
                elif dy == 7:
                    c = 36
                else:
                    if bx == 0:
                        c = 44
                    elif bx == 1:
                        c = 68
                    elif bx == 2:
                        c = 58
                    else:
                        c = 48
                c += ((x * 3 + y * 7) % 5) - 2
                c = max(30, min(85, c))
                d.point((x, y), fill=(c - 2, c, c + 3, 255))
    im.save(TEXTURE_DIR / "journey_kawara_roof.png")


def make_tatami_textures():
    im_top = Image.new("RGBA", (16, 16))
    d = ImageDraw.Draw(im_top)
    for x in range(16):
        is_border = (x in (0, 1) or x in (14, 15))
        for y in range(16):
            if is_border:
                c = 34 if (x + y) % 3 == 0 else 24
                d.point((x, y), fill=(c, c + 2, c + 6, 255))
            else:
                col = x % 2
                base_g = 180 if col == 0 else 168
                n = ((x * 3 + y * 7) % 5) - 2
                d.point((x, y), fill=(base_g + 14 + n, base_g + n, base_g - 42 + n, 255))
    im_top.save(TEXTURE_DIR / "journey_tatami_top.png")

    im_side = Image.new("RGBA", (16, 16))
    d_s = ImageDraw.Draw(im_side)
    for y in range(16):
        for x in range(16):
            if y < 3:
                d_s.point((x, y), fill=(175, 165, 125, 255))
            else:
                c = 145 + ((x * 7 + y * 11) % 9) - 4
                d_s.point((x, y), fill=(c, c - 10, c - 35, 255))
    im_side.save(TEXTURE_DIR / "journey_tatami_side.png")


def make_lantern_textures():
    im_sl = Image.new("RGBA", (16, 16))
    d_sl = ImageDraw.Draw(im_sl)
    for y in range(16):
        for x in range(16):
            n = ((x * 9 + y * 13) % 7) - 3
            if 5 <= x <= 10 and 5 <= y <= 10:
                if (x in (7, 8) and y in (7, 8)):
                    d_sl.point((x, y), fill=(255, 210, 120, 255))
                else:
                    d_sl.point((x, y), fill=(80, 75, 70, 255))
            else:
                moss = 15 if (x + y * 2) % 11 == 0 else 0
                d_sl.point((x, y), fill=(125 + n - moss, 128 + n + moss, 126 + n, 255))
    im_sl.save(TEXTURE_DIR / "journey_stone_lantern.png")


# ==================== 2. 模型与方块状态生成 ====================

def cube_all(name, texture_name=None):
    tex = f"{NS}:block/{texture_name or name}"
    write_json(MODEL_BLOCK_DIR / f"{name}.json", {
        "parent": "minecraft:block/cube_all",
        "textures": {"all": tex}
    })
    write_json(BLOCKSTATE_DIR / f"{name}.json", {
        "variants": {"": {"model": f"{NS}:block/{name}"}}
    })
    write_json(MODEL_ITEM_DIR / f"{name}.json", {
        "parent": f"{NS}:block/{name}"
    })
    write_json(ITEM_DIR / f"{name}.json", {
        "model": {"type": "minecraft:model", "model": f"{NS}:item/{name}"}
    })
    write_json(LOOT_DIR / f"{name}.json", simple_loot_table(name))


def log_block(name, side_tex, top_tex):
    write_json(MODEL_BLOCK_DIR / f"{name}.json", {
        "parent": "minecraft:block/cube_column",
        "textures": {
            "end": f"{NS}:block/{top_tex}",
            "side": f"{NS}:block/{side_tex}"
        }
    })
    write_json(MODEL_BLOCK_DIR / f"{name}_horizontal.json", {
        "parent": "minecraft:block/cube_column_horizontal",
        "textures": {
            "end": f"{NS}:block/{top_tex}",
            "side": f"{NS}:block/{side_tex}"
        }
    })
    write_json(BLOCKSTATE_DIR / f"{name}.json", {
        "variants": {
            "axis=x": {"model": f"{NS}:block/{name}_horizontal", "x": 90, "y": 90},
            "axis=y": {"model": f"{NS}:block/{name}"},
            "axis=z": {"model": f"{NS}:block/{name}_horizontal", "x": 90}
        }
    })
    write_json(MODEL_ITEM_DIR / f"{name}.json", {"parent": f"{NS}:block/{name}"})
    write_json(ITEM_DIR / f"{name}.json", {"model": {"type": "minecraft:model", "model": f"{NS}:item/{name}"}})
    write_json(LOOT_DIR / f"{name}.json", simple_loot_table(name))


def stairs_block(name, base_tex):
    tex = f"{NS}:block/{base_tex}"
    write_json(MODEL_BLOCK_DIR / f"{name}.json", {
        "parent": "minecraft:block/stairs",
        "textures": {"bottom": tex, "top": tex, "side": tex}
    })
    write_json(MODEL_BLOCK_DIR / f"{name}_inner.json", {
        "parent": "minecraft:block/inner_stairs",
        "textures": {"bottom": tex, "top": tex, "side": tex}
    })
    write_json(MODEL_BLOCK_DIR / f"{name}_outer.json", {
        "parent": "minecraft:block/outer_stairs",
        "textures": {"bottom": tex, "top": tex, "side": tex}
    })
    # 与原版楼梯方块状态相同：下半的“左”转角、上半的“右”转角各再转 90°，否则西北、东南转角会错位
    variants = {}
    for facing, base in [("east", 0), ("south", 90), ("west", 180), ("north", 270)]:
        for half in ("bottom", "top"):
            for shape in ["straight", "inner_left", "inner_right", "outer_left", "outer_right"]:
                m_suffix = "_inner" if "inner" in shape else "_outer" if "outer" in shape else ""
                y = base
                if half == "bottom" and shape.endswith("left"):
                    y -= 90
                if half == "top" and shape.endswith("right"):
                    y += 90
                y %= 360
                entry = {"model": f"{NS}:block/{name}{m_suffix}"}
                if half == "top":
                    entry["x"] = 180
                if y:
                    entry["y"] = y
                if half == "top" or y:
                    entry["uvlock"] = True
                variants[f"facing={facing},half={half},shape={shape}"] = entry
    write_json(BLOCKSTATE_DIR / f"{name}.json", {"variants": variants})
    write_json(MODEL_ITEM_DIR / f"{name}.json", {"parent": f"{NS}:block/{name}"})
    write_json(ITEM_DIR / f"{name}.json", {"model": {"type": "minecraft:model", "model": f"{NS}:item/{name}"}})
    write_json(LOOT_DIR / f"{name}.json", simple_loot_table(name))


def slab_block(name, base_tex):
    tex = f"{NS}:block/{base_tex}"
    write_json(MODEL_BLOCK_DIR / f"{name}.json", {
        "parent": "minecraft:block/slab",
        "textures": {"bottom": tex, "top": tex, "side": tex}
    })
    write_json(MODEL_BLOCK_DIR / f"{name}_top.json", {
        "parent": "minecraft:block/slab_top",
        "textures": {"bottom": tex, "top": tex, "side": tex}
    })
    write_json(BLOCKSTATE_DIR / f"{name}.json", {
        "variants": {
            "type=bottom": {"model": f"{NS}:block/{name}"},
            "type=double": {"model": f"{NS}:block/{base_tex}"},
            "type=top": {"model": f"{NS}:block/{name}_top"}
        }
    })
    write_json(MODEL_ITEM_DIR / f"{name}.json", {"parent": f"{NS}:block/{name}"})
    write_json(ITEM_DIR / f"{name}.json", {"model": {"type": "minecraft:model", "model": f"{NS}:item/{name}"}})
    write_json(LOOT_DIR / f"{name}.json", slab_loot_table(name))


def fence_block(name, base_tex):
    tex = f"{NS}:block/{base_tex}"
    write_json(MODEL_BLOCK_DIR / f"{name}_post.json", {
        "parent": "minecraft:block/fence_post",
        "textures": {"texture": tex}
    })
    write_json(MODEL_BLOCK_DIR / f"{name}_side.json", {
        "parent": "minecraft:block/fence_side",
        "textures": {"texture": tex}
    })
    write_json(MODEL_BLOCK_DIR / f"{name}_inventory.json", {
        "parent": "minecraft:block/fence_inventory",
        "textures": {"texture": tex}
    })
    write_json(BLOCKSTATE_DIR / f"{name}.json", {
        "multipart": [
            {"apply": {"model": f"{NS}:block/{name}_post"}},
            {"when": {"north": "true"}, "apply": {"model": f"{NS}:block/{name}_side", "uvlock": True}},
            {"when": {"east": "true"}, "apply": {"model": f"{NS}:block/{name}_side", "y": 90, "uvlock": True}},
            {"when": {"south": "true"}, "apply": {"model": f"{NS}:block/{name}_side", "y": 180, "uvlock": True}},
            {"when": {"west": "true"}, "apply": {"model": f"{NS}:block/{name}_side", "y": 270, "uvlock": True}}
        ]
    })
    write_json(MODEL_ITEM_DIR / f"{name}.json", {"parent": f"{NS}:block/{name}_inventory"})
    write_json(ITEM_DIR / f"{name}.json", {"model": {"type": "minecraft:model", "model": f"{NS}:item/{name}"}})
    write_json(LOOT_DIR / f"{name}.json", simple_loot_table(name))


def fence_gate_block(name, base_tex):
    tex = f"{NS}:block/{base_tex}"
    write_json(MODEL_BLOCK_DIR / f"{name}.json", {
        "parent": "minecraft:block/template_fence_gate",
        "textures": {"texture": tex}
    })
    write_json(MODEL_BLOCK_DIR / f"{name}_open.json", {
        "parent": "minecraft:block/template_fence_gate_open",
        "textures": {"texture": tex}
    })
    write_json(MODEL_BLOCK_DIR / f"{name}_wall.json", {
        "parent": "minecraft:block/template_fence_gate_wall",
        "textures": {"texture": tex}
    })
    write_json(MODEL_BLOCK_DIR / f"{name}_wall_open.json", {
        "parent": "minecraft:block/template_fence_gate_wall_open",
        "textures": {"texture": tex}
    })
    variants = {}
    for facing, y in [("south", None), ("west", 90), ("north", 180), ("east", 270)]:
        for in_wall in [False, True]:
            for open_state in [False, True]:
                key = f"facing={facing},in_wall={str(in_wall).lower()},open={str(open_state).lower()}"
                m = f"{NS}:block/{name}"
                if in_wall: m += "_wall"
                if open_state: m += "_open"
                entry = {"model": m, "uvlock": True}
                if y: entry["y"] = y
                variants[key] = entry
    write_json(BLOCKSTATE_DIR / f"{name}.json", {"variants": variants})
    write_json(MODEL_ITEM_DIR / f"{name}.json", {"parent": f"{NS}:block/{name}"})
    write_json(ITEM_DIR / f"{name}.json", {"model": {"type": "minecraft:model", "model": f"{NS}:item/{name}"}})
    write_json(LOOT_DIR / f"{name}.json", simple_loot_table(name))


def tatami_blocks():
    write_json(MODEL_BLOCK_DIR / "journey_tatami.json", {
        "parent": "minecraft:block/cube",
        "textures": {
            "particle": f"{NS}:block/journey_tatami_top",
            "up": f"{NS}:block/journey_tatami_top",
            "down": f"{NS}:block/journey_tatami_side",
            "north": f"{NS}:block/journey_tatami_side",
            "south": f"{NS}:block/journey_tatami_side",
            "east": f"{NS}:block/journey_tatami_side",
            "west": f"{NS}:block/journey_tatami_side"
        }
    })
    write_json(BLOCKSTATE_DIR / "journey_tatami.json", {
        "variants": {
            "axis=z": {"model": f"{NS}:block/journey_tatami"},
            "axis=x": {"model": f"{NS}:block/journey_tatami", "y": 90},
            "axis=y": {"model": f"{NS}:block/journey_tatami"}
        }
    })
    write_json(MODEL_ITEM_DIR / "journey_tatami.json", {"parent": f"{NS}:block/journey_tatami"})
    write_json(ITEM_DIR / "journey_tatami.json", {"model": {"type": "minecraft:model", "model": f"{NS}:item/journey_tatami"}})
    write_json(LOOT_DIR / "journey_tatami.json", simple_loot_table("journey_tatami"))

    write_json(MODEL_BLOCK_DIR / "journey_tatami_slab.json", {
        "parent": "minecraft:block/slab",
        "textures": {
            "bottom": f"{NS}:block/journey_tatami_side",
            "top": f"{NS}:block/journey_tatami_top",
            "side": f"{NS}:block/journey_tatami_side"
        }
    })
    write_json(MODEL_BLOCK_DIR / "journey_tatami_slab_top.json", {
        "parent": "minecraft:block/slab_top",
        "textures": {
            "bottom": f"{NS}:block/journey_tatami_side",
            "top": f"{NS}:block/journey_tatami_top",
            "side": f"{NS}:block/journey_tatami_side"
        }
    })
    write_json(BLOCKSTATE_DIR / "journey_tatami_slab.json", {
        "variants": {
            "type=bottom": {"model": f"{NS}:block/journey_tatami_slab"},
            "type=double": {"model": f"{NS}:block/journey_tatami"},
            "type=top": {"model": f"{NS}:block/journey_tatami_slab_top"}
        }
    })
    write_json(MODEL_ITEM_DIR / "journey_tatami_slab.json", {"parent": f"{NS}:block/journey_tatami_slab"})
    write_json(ITEM_DIR / "journey_tatami_slab.json", {"model": {"type": "minecraft:model", "model": f"{NS}:item/journey_tatami_slab"}})
    write_json(LOOT_DIR / "journey_tatami_slab.json", slab_loot_table("journey_tatami_slab"))


def shoji_window_blocks():
    name = "journey_shoji_window"
    write_json(MODEL_BLOCK_DIR / f"{name}.json", {
        "parent": "minecraft:block/block",
        "render_type": "minecraft:cutout",
        "textures": {
            "particle": f"{NS}:block/{name}",
            "window": f"{NS}:block/{name}"
        },
        "elements": [
            {
                "from": [0, 0, 7],
                "to": [16, 16, 9],
                "faces": {
                    "north": {"uv": [0, 0, 16, 16], "texture": "#window"},
                    "south": {"uv": [16, 0, 0, 16], "texture": "#window"},
                    "east": {"uv": [7, 0, 9, 16], "texture": "#window"},
                    "west": {"uv": [7, 0, 9, 16], "texture": "#window"},
                    "up": {"uv": [0, 7, 16, 9], "texture": "#window"},
                    "down": {"uv": [0, 7, 16, 9], "texture": "#window"}
                }
            }
        ]
    })
    write_json(BLOCKSTATE_DIR / f"{name}.json", {
        "variants": {
            "facing=north": {"model": f"{NS}:block/{name}"},
            "facing=south": {"model": f"{NS}:block/{name}"},
            "facing=east": {"model": f"{NS}:block/{name}", "y": 90},
            "facing=west": {"model": f"{NS}:block/{name}", "y": 90}
        }
    })
    write_json(MODEL_ITEM_DIR / f"{name}.json", {"parent": f"{NS}:block/{name}"})
    write_json(ITEM_DIR / f"{name}.json", {"model": {"type": "minecraft:model", "model": f"{NS}:item/{name}"}})
    write_json(LOOT_DIR / f"{name}.json", simple_loot_table(name))


def lantern_blocks():
    # 和纸灯笼的模型、贴图与物品改由 tools/fox_props_art.py 绘制，这里只写掉落表
    write_json(LOOT_DIR / "journey_paper_lantern.json", simple_loot_table("journey_paper_lantern"))

    # 2. 矮石灯笼
    write_json(MODEL_BLOCK_DIR / "journey_stone_lantern.json", {
        "parent": "minecraft:block/block",
        "render_type": "minecraft:cutout",
        "textures": {
            "particle": f"{NS}:block/journey_stone_lantern",
            "stone": f"{NS}:block/journey_stone_lantern"
        },
        "elements": [
            {"from": [3, 0, 3], "to": [13, 3, 13], "faces": {f: {"uv": [3, 13, 13, 16], "texture": "#stone"} for f in ("north", "south", "east", "west", "up", "down")}},
            {"from": [5, 3, 5], "to": [11, 6, 11], "faces": {f: {"uv": [5, 10, 11, 13], "texture": "#stone"} for f in ("north", "south", "east", "west", "up", "down")}},
            {"from": [4, 6, 4], "to": [12, 7, 12], "faces": {f: {"uv": [4, 9, 12, 10], "texture": "#stone"} for f in ("north", "south", "east", "west", "up", "down")}},
            {"from": [5, 7, 5], "to": [11, 11, 11], "faces": {f: {"uv": [5, 5, 11, 9], "texture": "#stone"} for f in ("north", "south", "east", "west", "up", "down")}},
            {"from": [2, 11, 2], "to": [14, 13, 14], "faces": {f: {"uv": [2, 3, 14, 5], "texture": "#stone"} for f in ("north", "south", "east", "west", "up", "down")}},
            {"from": [6, 13, 6], "to": [10, 16, 10], "faces": {f: {"uv": [6, 0, 10, 3], "texture": "#stone"} for f in ("north", "south", "east", "west", "up", "down")}}
        ]
    })
    write_json(BLOCKSTATE_DIR / "journey_stone_lantern.json", {
        "variants": {"": {"model": f"{NS}:block/journey_stone_lantern"}}
    })
    write_json(MODEL_ITEM_DIR / "journey_stone_lantern.json", {"parent": f"{NS}:block/journey_stone_lantern"})
    write_json(ITEM_DIR / "journey_stone_lantern.json", {"model": {"type": "minecraft:model", "model": f"{NS}:item/journey_stone_lantern"}})
    write_json(LOOT_DIR / "journey_stone_lantern.json", simple_loot_table("journey_stone_lantern"))

    # 3. 高石灯笼
    write_json(MODEL_BLOCK_DIR / "journey_stone_lantern_tall_lower.json", {
        "parent": "minecraft:block/block",
        "render_type": "minecraft:cutout",
        "textures": {"stone": f"{NS}:block/journey_stone_lantern", "particle": f"{NS}:block/journey_stone_lantern"},
        "elements": [
            {"from": [2, 0, 2], "to": [14, 4, 14], "faces": {f: {"uv": [2, 12, 14, 16], "texture": "#stone"} for f in ("north", "south", "east", "west", "up", "down")}},
            {"from": [5, 4, 5], "to": [11, 14, 11], "faces": {f: {"uv": [5, 4, 11, 14], "texture": "#stone"} for f in ("north", "south", "east", "west", "up", "down")}},
            {"from": [3, 14, 3], "to": [13, 16, 13], "faces": {f: {"uv": [3, 2, 13, 4], "texture": "#stone"} for f in ("north", "south", "east", "west", "up", "down")}}
        ]
    })
    write_json(MODEL_BLOCK_DIR / "journey_stone_lantern_tall_upper.json", {
        "parent": "minecraft:block/block",
        "render_type": "minecraft:cutout",
        "textures": {"stone": f"{NS}:block/journey_stone_lantern", "particle": f"{NS}:block/journey_stone_lantern"},
        "elements": [
            {"from": [4, 0, 4], "to": [12, 6, 12], "faces": {f: {"uv": [4, 4, 12, 10], "texture": "#stone"} for f in ("north", "south", "east", "west", "up", "down")}},
            {"from": [1, 6, 1], "to": [15, 9, 15], "faces": {f: {"uv": [1, 1, 15, 4], "texture": "#stone"} for f in ("north", "south", "east", "west", "up", "down")}},
            {"from": [5, 9, 5], "to": [11, 13, 11], "faces": {f: {"uv": [5, 0, 11, 4], "texture": "#stone"} for f in ("north", "south", "east", "west", "up", "down")}}
        ]
    })
    write_json(BLOCKSTATE_DIR / "journey_stone_lantern_tall.json", {
        "variants": {
            "half=lower": {"model": f"{NS}:block/journey_stone_lantern_tall_lower"},
            "half=upper": {"model": f"{NS}:block/journey_stone_lantern_tall_upper"}
        }
    })
    write_json(MODEL_ITEM_DIR / "journey_stone_lantern_tall.json", {"parent": f"{NS}:block/journey_stone_lantern_tall_lower"})
    write_json(ITEM_DIR / "journey_stone_lantern_tall.json", {"model": {"type": "minecraft:model", "model": f"{NS}:item/journey_stone_lantern_tall"}})
    write_json(LOOT_DIR / "journey_stone_lantern_tall.json", door_loot_table("journey_stone_lantern_tall"))


def update_lang():
    entries = {
        "block.logos_dimension.journey_white_plaster": ("白灰泥墙", "White Plaster Wall"),
        "item.logos_dimension.journey_white_plaster": ("白灰泥墙", "White Plaster Wall"),
        "block.logos_dimension.journey_wood_wainscot": ("木质腰墙", "Wood Wainscot Wall"),
        "item.logos_dimension.journey_wood_wainscot": ("木质腰墙", "Wood Wainscot Wall"),
        "block.logos_dimension.journey_kawara_roof": ("灰黑陶瓦", "Black Slate Kawara Roof"),
        "item.logos_dimension.journey_kawara_roof": ("灰黑陶瓦", "Black Slate Kawara Roof"),
        "block.logos_dimension.journey_kawara_stairs": ("灰黑陶瓦楼梯", "Kawara Roof Stairs"),
        "item.logos_dimension.journey_kawara_stairs": ("灰黑陶瓦楼梯", "Kawara Roof Stairs"),
        "block.logos_dimension.journey_kawara_slab": ("灰黑陶瓦台阶", "Kawara Roof Slab"),
        "item.logos_dimension.journey_kawara_slab": ("灰黑陶瓦台阶", "Kawara Roof Slab"),
        "block.logos_dimension.journey_lacquer_log": ("朱漆原木", "Vermilion Lacquer Log"),
        "item.logos_dimension.journey_lacquer_log": ("朱漆原木", "Vermilion Lacquer Log"),
        "block.logos_dimension.journey_lacquer_planks": ("朱漆木板", "Vermilion Lacquer Planks"),
        "item.logos_dimension.journey_lacquer_planks": ("朱漆木板", "Vermilion Lacquer Planks"),
        "block.logos_dimension.journey_lacquer_stairs": ("朱漆楼梯", "Vermilion Lacquer Stairs"),
        "item.logos_dimension.journey_lacquer_stairs": ("朱漆楼梯", "Vermilion Lacquer Stairs"),
        "block.logos_dimension.journey_lacquer_slab": ("朱漆台阶", "Vermilion Lacquer Slab"),
        "item.logos_dimension.journey_lacquer_slab": ("朱漆台阶", "Vermilion Lacquer Slab"),
        "block.logos_dimension.journey_lacquer_fence": ("朱漆栅栏", "Vermilion Lacquer Fence"),
        "item.logos_dimension.journey_lacquer_fence": ("朱漆栅栏", "Vermilion Lacquer Fence"),
        "block.logos_dimension.journey_lacquer_fence_gate": ("朱漆栅栏门", "Vermilion Lacquer Fence Gate"),
        "item.logos_dimension.journey_lacquer_fence_gate": ("朱漆栅栏门", "Vermilion Lacquer Fence Gate"),
        "block.logos_dimension.journey_tatami": ("榻榻米", "Tatami Mat"),
        "item.logos_dimension.journey_tatami": ("榻榻米", "Tatami Mat"),
        "block.logos_dimension.journey_tatami_slab": ("榻榻米台阶", "Tatami Mat Slab"),
        "item.logos_dimension.journey_tatami_slab": ("榻榻米台阶", "Tatami Mat Slab"),
        "block.logos_dimension.journey_shoji_door": ("障子拉门", "Shoji Sliding Door"),
        "item.logos_dimension.journey_shoji_door": ("障子拉门", "Shoji Sliding Door"),
        "block.logos_dimension.journey_shoji_window": ("障子纸窗", "Shoji Paper Window"),
        "item.logos_dimension.journey_shoji_window": ("障子纸窗", "Shoji Paper Window"),
        "block.logos_dimension.journey_paper_lantern": ("和纸灯笼", "Japanese Paper Lantern"),
        "item.logos_dimension.journey_paper_lantern": ("和纸灯笼", "Japanese Paper Lantern"),
        "block.logos_dimension.journey_stone_lantern": ("石灯笼", "Stone Lantern"),
        "item.logos_dimension.journey_stone_lantern": ("石灯笼", "Stone Lantern"),
        "block.logos_dimension.journey_stone_lantern_tall": ("春日高石灯笼", "Tall Kasuga Stone Lantern"),
        "item.logos_dimension.journey_stone_lantern_tall": ("春日高石灯笼", "Tall Kasuga Stone Lantern"),
        # Phase 2 entries
        "block.logos_dimension.journey_hot_spring_stone": ("温泉地热石", "Hot Spring Geothermal Stone"),
        "item.logos_dimension.journey_hot_spring_stone": ("温泉地热石", "Hot Spring Geothermal Stone"),
        "block.logos_dimension.journey_fox_statue": ("灵狐石像", "Fox Spirit Stone Statue"),
        "item.logos_dimension.journey_fox_statue": ("灵狐石像", "Fox Spirit Stone Statue"),
        "block.logos_dimension.journey_stone_basin": ("手水钵", "Stone Water Basin"),
        "item.logos_dimension.journey_stone_basin": ("手水钵", "Stone Water Basin"),
        "block.logos_dimension.journey_bamboo_spout": ("竹出水口", "Bamboo Water Spout"),
        "item.logos_dimension.journey_bamboo_spout": ("竹出水口", "Bamboo Water Spout"),
        "block.logos_dimension.journey_stepping_stone": ("庭院踏步石", "Garden Stepping Stone"),
        "item.logos_dimension.journey_stepping_stone": ("庭院踏步石", "Garden Stepping Stone"),
        "block.logos_dimension.journey_shrine_hokora": ("石龛神社", "Roadside Shrine Hokora"),
        "item.logos_dimension.journey_shrine_hokora": ("石龛神社", "Roadside Shrine Hokora"),
        "block.logos_dimension.journey_noren": ("和风暖帘", "Japanese Noren Curtain"),
        "item.logos_dimension.journey_noren": ("和风暖帘", "Japanese Noren Curtain"),
        "block.logos_dimension.journey_shimenawa": ("注连绳与纸垂", "Sacred Shimenawa Rope"),
        "item.logos_dimension.journey_shimenawa": ("注连绳与纸垂", "Sacred Shimenawa Rope"),
    }
    for lang, idx in [("zh_cn", 0), ("en_us", 1)]:
        p = ASSETS / "lang" / f"{lang}.json"
        data = json.loads(p.read_text(encoding="utf-8"))
        for k, v in entries.items():
            data[k] = v[idx]
        write_json(p, data)


def main():
    print("正在生成贴图...")
    make_plaster_texture()
    make_wainscot_texture()
    make_kawara_roof_texture()
    make_tatami_textures()
    make_lantern_textures()
    # 朱漆木贴图、障子门窗贴图与门模型、和纸灯笼，以及狐像、手水钵、暖帘等庭院小品与温泉蒸汽，改由 tools/fox_props_art.py 绘制

    print("正在生成模型与方块状态...")
    # 墙面
    cube_all("journey_white_plaster")
    cube_all("journey_wood_wainscot")
    # 灰瓦三件套
    cube_all("journey_kawara_roof")
    stairs_block("journey_kawara_stairs", "journey_kawara_roof")
    slab_block("journey_kawara_slab", "journey_kawara_roof")
    # 朱漆木全套
    log_block("journey_lacquer_log", "journey_lacquer_log", "journey_lacquer_log_top")
    cube_all("journey_lacquer_planks")
    stairs_block("journey_lacquer_stairs", "journey_lacquer_planks")
    slab_block("journey_lacquer_slab", "journey_lacquer_planks")
    fence_block("journey_lacquer_fence", "journey_lacquer_planks")
    fence_gate_block("journey_lacquer_fence_gate", "journey_lacquer_planks")
    # 榻榻米
    tatami_blocks()
    # 门窗
    shoji_window_blocks()
    # 照明
    lantern_blocks()
    # 语言文件
    update_lang()
    print("全部和风方块与粒子资产生成完毕！")


if __name__ == "__main__":
    main()
