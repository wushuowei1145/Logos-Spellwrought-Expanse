#version 330

// 连携切入的合成（docs/CHAIN_CUTIN.md）：主画面当底，压暗战场；按分割线切出斜格，格里是黑底、星空和角色特写（逆光描边）；
// 分割线是白芯加主题色外焰；落位时中间炸开白光云，停留时光尘飘落；最后全屏白。
// 角色是 Java 那边单独画好的贴图（PortraitN，已预乘透明度），这里只取样。格子、星空、光尘都是画在屏幕上的 2D 图形：
// 它们出现时世界已经被黑底盖住，不会出现「世界在转、纹理不动」的晕。

uniform sampler2D InSampler;
uniform sampler2D Portrait0;
uniform sampler2D Portrait1;
uniform sampler2D Portrait2;
uniform sampler2D Portrait3;
uniform sampler2D Portrait4;

layout(std140) uniform ChainCutin {
    vec4 Screen;      // 宽、高（像素），现在第几刻，种子
    vec4 Phase;       // 战场压暗，黑底，星空显形，画不画角色
    vec4 Light;       // 中间白光云，全屏白，分割线粗细倍数，分割线强度（白光散去时是残影）
    vec4 Misc;        // 分割线几道，角色放大倍数，震动 x、y（画面比例）
    vec4 Extra;       // 曝光，黑底盖满全屏（1）还是只盖格子（0），角色贴图往画面下面多画了多少（画面高度），角色贴图宽（画面宽度）
    vec4 Lines[6];    // 底端 x，顶端 x，从底下长到多高，是不是最外的边
    vec4 LineColor[6];// 外焰的颜色
    vec4 Slots[5];    // 角色站在哪（x），离开原位多少（画面高度，正数往上），-，这一格有没有角色
    vec4 SlotColor[5];// 这一格的主色（底板的色调、逆光）
};

in vec2 texCoord;
out vec4 fragColor;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

float cutinHash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float cutinValue(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = cutinHash(i);
    float b = cutinHash(i + vec2(1.0, 0.0));
    float c = cutinHash(i + vec2(0.0, 1.0));
    float d = cutinHash(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float lineX(int i, float y) {
    return mix(Lines[i].x, Lines[i].y, y);
}

vec4 portraitAt(int i, vec2 uv) {
    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) return vec4(0.0);
    if (i == 0) return texture(Portrait0, uv);
    if (i == 1) return texture(Portrait1, uv);
    if (i == 2) return texture(Portrait2, uv);
    if (i == 3) return texture(Portrait3, uv);
    return texture(Portrait4, uv);
}

// 黑底上的星空暗纹：深藏青渐变，带一点格子的主色；角色身后一团背光；稀疏的星星闪着。
vec3 backdrop(vec2 q, vec3 tint, float cx, float aspect) {
    vec3 base = mix(vec3(0.012, 0.012, 0.035), vec3(0.05, 0.045, 0.13), q.y);
    base = mix(base, tint * 0.16, 0.3);
    vec2 d = vec2((q.x - cx) * aspect, (q.y - 0.6) * 1.2);
    base += tint * exp(-dot(d, d) / 0.07) * 0.5;
    base += tint * 0.07 * cutinValue(vec2(q.x * aspect, q.y) * 3.0 + vec2(Screen.w * 0.01, Screen.z * 0.01));

    vec2 cell = q * Screen.xy / (22.0 * Screen.y / 1080.0);
    vec2 id = floor(cell);
    vec2 f = fract(cell) - 0.5;
    float h = cutinHash(id + Screen.w);
    vec2 off = vec2(cutinHash(id + 1.7), cutinHash(id + 3.1)) - 0.5;
    float star = smoothstep(0.11, 0.0, length(f - off * 0.6)) * step(0.88, h);
    float twinkle = 0.55 + 0.45 * sin(Screen.z * 0.9 + h * 40.0);
    base += vec3(0.85, 0.9, 1.0) * star * twinkle * (0.5 + h);
    return base;
}

// 第 i 格的角色（预乘透明度），带逆光描边和一点对比度。
vec4 character(int i, vec2 q, vec3 tint) {
    float cx = Slots[i].x;
    vec2 anchor = vec2(cx, 0.45);
    vec2 r = (q - anchor) / Misc.y + anchor;
    r.y -= Slots[i].y;
    float pw = Extra.w;
    float ext = Extra.z;
    // 贴图从画面底下 ext 处画到画面顶
    vec2 uv = vec2((r.x - cx) / pw + 0.5, (r.y + ext) / (1.0 + ext));
    vec4 c = portraitAt(i, uv);
    // 贴图的上下边淡掉：冲进来、甩出去时露出来的不是一道硬边
    c *= smoothstep(0.0, 0.06, uv.y) * smoothstep(1.0, 0.97, uv.y);
    if (c.a <= 0.001) return vec4(0.0);

    // 逆光：透明的边往里几个像素亮起来，上方（光从上后方来）更亮。取样夹在贴图里，贴图的边不算轮廓
    vec2 texel = vec2(1.0 / (pw * Screen.x), 1.0 / (Screen.y * (1.0 + ext))) * (3.5 * Screen.y / 1080.0);
    float edge = 0.0;
    for (int k = 0; k < 8; k++) {
        float a = float(k) * 0.7853982;
        vec2 dir = vec2(cos(a), sin(a));
        float n = portraitAt(i, clamp(uv + dir * texel, vec2(0.0), vec2(1.0))).a;
        edge = max(edge, (1.0 - n) * (0.5 + 0.5 * max(dir.y, 0.0)));
    }
    vec3 rgb = c.rgb / max(c.a, 0.001);
    float l = dot(rgb, LUMA);
    rgb = mix(vec3(l), rgb, 1.15);
    rgb = clamp((rgb - 0.5) * 1.12 + 0.5, 0.0, 1.0);
    rgb += mix(tint, vec3(1.0), 0.35) * edge * 1.3;
    return vec4(rgb * c.a, c.a);
}

// 分割线：白芯加主题色外焰，外焰的边按噪声起伏、向上滚动；还没贯穿时上端有个亮尖。
vec3 beams(vec2 q, float aspect) {
    vec3 acc = vec3(0.0);
    int count = int(Misc.x + 0.5);
    float scale = Screen.y / 1080.0;
    float w = Light.z * scale;
    for (int i = 0; i < 6; i++) {
        if (i >= count) break;
        float grow = Lines[i].z;
        if (grow <= 0.0) continue;
        float lx = lineX(i, q.y);
        float slope = (Lines[i].y - Lines[i].x) * aspect;
        float dpx = abs(q.x - lx) * Screen.x / sqrt(1.0 + slope * slope);
        float along = smoothstep(grow + 0.012, grow - 0.02, q.y);
        if (along <= 0.0) continue;
        float n1 = cutinValue(vec2(q.y * Screen.y / (40.0 * scale) - Screen.z * 0.8, float(i) * 7.31 + Screen.w));
        float n2 = cutinValue(vec2(q.y * Screen.y / (12.0 * scale) - Screen.z * 1.7, float(i) * 3.7));
        float core = smoothstep(2.4 * w, 0.7 * w, dpx);
        float flame = exp(-dpx / ((7.0 + 9.0 * n1 + 4.0 * n2) * w));
        vec3 color = LineColor[i].rgb;
        acc += (vec3(1.0) * core * 1.1 + color * flame * 1.05) * along;
        if (grow < 0.999) {
            float tip = exp(-length(vec2(dpx, (q.y - grow) * Screen.y)) / (12.0 * w));
            acc += (vec3(1.0) + color) * tip * 0.8;
        }
    }
    return acc * Light.w;
}

void main() {
    float aspect = Screen.x / Screen.y;
    vec3 world = texture(InSampler, texCoord).rgb;

    // 战场：去色、压暗、加暗角
    float luma = dot(world, LUMA);
    vec2 v = texCoord - 0.5;
    v.x *= aspect;
    float vignette = smoothstep(0.35, 1.1, length(v));
    vec3 col = mix(world, vec3(luma) * 0.45, Phase.x * 0.85);
    col *= 1.0 - Phase.x * vignette * 0.6;

    // 2D 层跟着落位的轻震一起动
    vec2 q = texCoord + Misc.zw;
    int count = int(Misc.x + 0.5);
    int slot = -1;
    for (int i = 0; i < 5; i++) {
        if (i + 1 >= count) break;
        if (q.x >= lineX(i, q.y) && q.x < lineX(i + 1, q.y)) {
            slot = i;
            break;
        }
    }

    float plate = Phase.y * ((Extra.y > 0.5 || slot >= 0) ? 1.0 : 0.0);
    vec3 tint = slot >= 0 ? SlotColor[slot].rgb : vec3(0.55, 0.6, 0.95);
    if (plate > 0.0) {
        float cx = slot >= 0 ? Slots[slot].x : 0.5;
        vec3 layer = backdrop(q, tint, cx, aspect) * Phase.z;
        if (slot >= 0 && Phase.w > 0.5 && Slots[slot].w > 0.5) {
            vec4 c = character(slot, q, tint);
            layer = layer * (1.0 - c.a) + c.rgb;
        }
        // 光尘：斜着往左下飘
        vec2 dq = vec2(q.x * aspect, q.y) + vec2(0.35, 1.0) * Screen.z * 0.01;
        vec2 g = dq * 16.0;
        vec2 id = floor(g);
        vec2 f = fract(g) - 0.5;
        float h = cutinHash(id + 11.0 + Screen.w);
        vec2 o = (vec2(cutinHash(id + 2.3), cutinHash(id + 5.9)) - 0.5) * 0.7;
        float dust = smoothstep(0.07, 0.0, length(f - o)) * step(0.82, h);
        layer += mix(vec3(1.0), tint, 0.4) * dust * Phase.z * 0.7;
        layer *= Extra.x;
        col = mix(col, layer, plate);
    }

    col += beams(q, aspect);

    // 落位时中间横着炸开的白光云
    vec2 c = vec2((q.x - 0.5) * aspect / 0.95, (q.y - 0.46) / 0.3);
    float cloud = exp(-dot(c, c) * 1.4) * (0.8 + 0.4 * cutinValue(vec2(q.x * aspect, q.y) * 4.0 + Screen.z * 0.05));
    col += (vec3(1.0) - clamp(col, 0.0, 1.0)) * clamp(cloud * Light.x, 0.0, 1.0);

    col = mix(col, vec3(1.0), clamp(Light.y, 0.0, 1.0));
    fragColor = vec4(col, 1.0);
}
