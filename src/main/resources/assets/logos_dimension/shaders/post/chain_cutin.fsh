#version 330

// 连携切入的合成（docs/CHAIN_CUTIN.md）：主画面当底，压暗战场；按分割线切出斜格，格里是她自己颜色的能量底（速度线、身后的光、
// 能量雾、远处的光点）、角色特写（逆光描边，冲进冲出时拖影）和飘在她前面的光屑；分割线是白芯加主题色外焰；落位时中间炸开白光云；
// 收尾是过曝加一层封了顶的光幕，不是一张全白。
// 角色是 Java 那边单独画好的贴图（PortraitN，已预乘透明度），这里只取样。格子、能量底、光屑都是画在屏幕上的 2D 图形：
// 它们出现时世界已经被黑底盖住，不会出现「世界在转、纹理不动」的晕。

uniform sampler2D InSampler;
uniform sampler2D Portrait0;
uniform sampler2D Portrait1;
uniform sampler2D Portrait2;
uniform sampler2D Portrait3;
uniform sampler2D Portrait4;

layout(std140) uniform ChainCutin {
    vec4 Screen;        // 宽、高（像素），现在第几刻，种子
    vec4 Phase;         // 战场压暗，黑底，能量底显形，画不画角色
    vec4 Light;         // 中间白光云，光幕，分割线粗细倍数，分割线强度（光幕退下去时是残影）
    vec4 Misc;          // 分割线几道，曝光，震动 x、y（画面比例）
    vec4 Extra;         // 黑底盖满全屏（1）还是只盖格子（0），角色贴图往画面下面多画了多少（画面高度），角色贴图宽（画面宽度），-
    vec4 VeilTint;      // 光幕四周的颜色（小队主色的平均）
    vec4 Lines[6];      // 底端 x，顶端 x，从底下长到多高，是不是最外的边
    vec4 LineColor[6];  // 外焰的颜色
    vec4 Slots[5];      // 角色站在哪（x），离开原位多少（画面高度，正数往上），拖影（刚才的位置减现在），这一格有没有角色
    vec4 SlotColor[5];  // 这一格的主色，冲进来往哪走（-1 往下，1 往上）
    vec4 SlotNight[5];  // 这一格的底色
    vec4 Motes[5];      // 光屑的颜色，什么光屑（CutinMotes.Kind 的序号）
};

in vec2 texCoord;
out vec4 fragColor;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
// 光幕最亮处（中间）和四周的亮度：屏幕混合，再亮也不是全白；从中间往外衰减得快，像一团光炸开，不是一张白卡
const float VEIL_CENTER = 0.55;
const float VEIL_EDGE = 0.12;

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

// 第 i 格的角色离开原位 slide 时，画面上 q 处在她贴图上的坐标。贴图从画面底下 Extra.y 处画到画面顶。
vec2 portraitUv(int i, vec2 q, float slide) {
    float ext = Extra.y;
    return vec2((q.x - Slots[i].x) / Extra.z + 0.5, (q.y - slide + ext) / (1.0 + ext));
}

// 贴图的上下边淡掉：冲进来、甩出去时露出来的不是一道硬边
vec4 portraitFaded(int i, vec2 uv) {
    return portraitAt(i, uv) * smoothstep(0.0, 0.06, uv.y) * smoothstep(1.0, 0.97, uv.y);
}

// 第 i 格的角色（预乘透明度），带逆光描边和一点对比度；冲得快时沿着来路拖出残影（取样位置逐像素错开一点，看不出一层层的台阶）。
vec4 character(int i, vec2 q, vec3 tint) {
    vec2 uv = portraitUv(i, q, Slots[i].y);
    vec4 c = portraitFaded(i, uv);
    float smear = Slots[i].z;
    if (abs(smear) > 0.002) {
        float jitter = cutinHash(q * Screen.xy + Screen.z);
        vec4 acc = c;
        float total = 1.0;
        for (int k = 0; k < 12; k++) {
            float t = (float(k) + jitter) / 12.0;
            float w = 1.0 - t * 0.7;
            acc += portraitFaded(i, portraitUv(i, q, Slots[i].y + smear * t)) * w;
            total += w;
        }
        c = acc / total;
    }
    if (c.a <= 0.001) return vec4(0.0);

    // 逆光：透明的边往里几个像素亮起来，上方（光从上后方来）更亮。取样夹在贴图里，贴图的边不算轮廓
    vec2 texel = vec2(1.0 / (Extra.z * Screen.x), 1.0 / (Screen.y * (1.0 + Extra.y))) * (3.5 * Screen.y / 1080.0);
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

// 格子里她身后的能量底（i < 0 是格子外面盖满的那一圈）：她自己的夜色当底；身后一团光往外放射光束；大团的能量雾慢慢流；
// 顺着格子斜边的速度线（冲进来时又多又亮，停留时少一点、还在走）；远处闪着的小光点。都顺着她冲进来的方向走，比她慢（远）。
vec3 energy(vec2 q, int i, float aspect) {
    vec3 tint = i >= 0 ? SlotColor[i].rgb : vec3(0.55, 0.6, 0.95);
    vec3 night = i >= 0 ? SlotNight[i].rgb : vec3(0.05, 0.05, 0.12);
    float dir = i >= 0 ? SlotColor[i].w : -1.0;
    float cx = i >= 0 ? Slots[i].x : 0.5;
    float slide = i >= 0 ? Slots[i].y : 0.0;
    float rush = i >= 0 ? clamp(abs(Slots[i].z) * 25.0, 0.0, 1.0) : 0.0;
    float t = Screen.z;
    float reveal = Phase.z;
    vec2 p = vec2((q.x - cx) * aspect, q.y);

    vec3 col = mix(night * 0.45, night * 1.3, q.y);

    // 身后的光：方向用单位向量取噪声，左右没有接缝
    vec2 d = p - vec2(0.0, 0.66);
    float r = length(d);
    float glow = exp(-r * r / 0.045);
    float rays = cutinValue(d / max(r, 1e-4) * 2.6 + vec2(t * 0.03, Screen.w + float(i) * 3.1));
    rays = pow(rays, 3.0) * 2.2 * exp(-r * 1.4) * smoothstep(0.02, 0.12, r);
    col += tint * (glow * 0.5 + rays * 0.5) * reveal;

    // 能量雾
    float flow = slide * 0.35 + dir * t * 0.004;
    float fog = cutinValue(vec2(p.x * 2.5, (p.y - flow) * 1.6) + vec2(Screen.w, float(i) * 5.0));
    col += tint * fog * fog * 0.22 * reveal;

    // 速度线：和两边的分割线一样斜
    float slope = i >= 0 ? ((Lines[i].y - Lines[i].x) + (Lines[i + 1].y - Lines[i + 1].x)) * 0.5 : 0.0;
    float u = (q.x - slope * q.y) * aspect * 70.0;
    float column = floor(u);
    float h = cutinHash(vec2(column, float(i) * 13.0 + Screen.w));
    float h2 = cutinHash(vec2(column + 7.3, float(i) + 1.0));
    float across = smoothstep(0.12, 0.0, abs(fract(u) - 0.5 - (h2 - 0.5) * 0.6));
    float along = fract((q.y * (0.8 + h2) - slide * 1.4 - dir * t * (0.02 + 0.02 * h)) * 1.3 + h * 7.0);
    float len = 0.25 + 0.45 * h2 + 0.3 * rush;
    float dash = smoothstep(0.0, 0.05, along) * smoothstep(len, len - 0.15, along);
    float active = step(0.62 - rush * 0.25, h);
    float side = 0.4 + 0.6 * smoothstep(0.03, 0.17, abs(p.x));
    col += mix(tint, vec3(1.0), 0.3) * across * dash * active * side * (0.2 + 0.8 * rush);

    // 远处的小光点
    vec2 g = (p - vec2(0.0, slide * 0.6 + dir * t * 0.003)) / 0.06;
    vec2 id = floor(g);
    float hb = cutinHash(id + float(i) * 17.0 + Screen.w);
    vec2 o = (vec2(cutinHash(id + 2.3), cutinHash(id + 5.9)) - 0.5) * 0.5;
    float spot = smoothstep(0.22, 0.08, length(fract(g) - 0.5 - o)) * step(0.86, hb);
    col += mix(tint, vec3(1.0), 0.5) * spot * (0.6 + 0.4 * sin(t * 0.7 + hb * 30.0)) * 0.5 * reveal;
    return col;
}

// 光屑每刻往哪走（画面高度）：狐火、符纸往上飘，光叶往斜下落，火星往上蹿，星芒慢慢漂。
vec2 moteDrift(int kind) {
    if (kind == 0) return vec2(0.0, 0.006);
    if (kind == 1) return vec2(-0.002, -0.005);
    if (kind == 2) return vec2(0.0, 0.0035);
    if (kind == 3) return vec2(0.0015, 0.014);
    return vec2(0.0, 0.002);
}

// 一颗光屑：f 是它在自己格子里的坐标（-0.5..0.5）。返回亮度和白芯（负数是朱砂）。
vec2 moteShape(int kind, vec2 f, float h, float t) {
    if (kind == 0) {
        // 狐火：圆的火团，上面拖一条收尖的尾巴，外面一圈虚光，一闪一闪
        vec2 q = f / 0.4;
        float up = max(q.y, 0.0);
        float d = length(vec2(q.x / max(1.0 - up * 0.75, 0.15), q.y > 0.0 ? q.y * 0.45 : q.y));
        float flicker = 0.7 + 0.3 * sin(t * 0.9 + h * 40.0);
        float halo = exp(-dot(q, q) * 3.0) * 0.45;
        return vec2(max(smoothstep(0.7, 0.25, d), halo) * flicker, smoothstep(0.4, 0.0, d));
    }
    if (kind == 1) {
        // 光叶：细长的叶片打着转，中间一道叶脉
        float a = t * 0.12 + h * 6.28;
        vec2 q = mat2(cos(a), -sin(a), sin(a), cos(a)) * f / 0.28;
        float e = length(vec2(q.x * 2.6, q.y));
        float rib = smoothstep(0.08, 0.0, abs(q.x)) * step(e, 0.9);
        return vec2(smoothstep(0.9, 0.7, e), rib * 0.6);
    }
    if (kind == 2) {
        // 符纸：小纸条晃着、翻着，正面一道朱砂，背面暗一点
        float a = sin(t * 0.07 + h * 6.28) * 0.5;
        vec2 q = mat2(cos(a), -sin(a), sin(a), cos(a)) * f / 0.46;
        float flip = cos(t * 0.22 + h * 12.0);
        q.x /= max(abs(flip), 0.12);
        float b = max(abs(q.x) / 0.32, abs(q.y) / 0.85);
        float paper = smoothstep(1.0, 0.9, b) * (flip > 0.0 ? 1.0 : 0.7);
        float glow = exp(-max(b - 1.0, 0.0) * 6.0) * 0.3;
        float ink = smoothstep(0.08, 0.03, abs(q.x)) * step(abs(q.y), 0.55) * step(0.0, flip);
        return vec2(max(paper, glow), -ink);
    }
    if (kind == 3) {
        // 火星：小亮点，往下拖一道短尾，外面一圈虚光
        vec2 q = f / 0.14;
        float d = length(vec2(q.x, q.y < 0.0 ? q.y * 0.28 : q.y));
        float halo = exp(-dot(q, q) * 0.35) * 0.35;
        return vec2(max(smoothstep(1.0, 0.3, d), halo), smoothstep(0.45, 0.0, length(q)));
    }
    // 星芒：十字的四角光，一亮一暗
    vec2 q = abs(f / 0.32);
    float star = max(0.0, 1.0 - (q.x * 7.0 + q.y * 1.1)) + max(0.0, 1.0 - (q.y * 7.0 + q.x * 1.1));
    float core = exp(-dot(q, q) * 60.0);
    float twinkle = 0.5 + 0.5 * sin(t * 0.6 + h * 30.0);
    return vec2(clamp(star + core, 0.0, 1.0) * twinkle, core * twinkle);
}

// 飘在第 i 格角色前面的光屑（预乘透明度）：中景是清楚的形状，近景是虚掉的大光斑；都比她冲得快（离镜头近）。
// 冲得快时不画（一帧跳一大截只会一闪一闪），脸前面淡一点。
vec4 motes(vec2 q, int i, float aspect) {
    int kind = int(Motes[i].w + 0.5);
    vec3 color = Motes[i].rgb;
    float t = Screen.z;
    vec2 p = vec2((q.x - Slots[i].x) * aspect, q.y);
    vec2 drift = moteDrift(kind) * t;

    vec4 mid = vec4(0.0);
    vec2 g = (p - drift - vec2(0.0, Slots[i].y * 1.3)) / 0.075;
    vec2 id = floor(g);
    float h = cutinHash(id + float(i) * 31.0 + Screen.w);
    if (h > 0.85) {
        vec2 o = (vec2(cutinHash(id + 4.1), cutinHash(id + 8.7)) - 0.5) * 0.35;
        vec2 f = fract(g) - 0.5 - o - vec2(sin(t * 0.15 + h * 20.0) * 0.06, 0.0);
        vec2 m = moteShape(kind, f, h, t);
        vec3 c = mix(color, vec3(1.0), clamp(m.y, 0.0, 1.0) * 0.7);
        c = mix(c, vec3(0.85, 0.16, 0.12), clamp(-m.y, 0.0, 1.0));
        float a = clamp(m.x, 0.0, 1.0) * 0.85;
        mid = vec4(c * a, a);
    }

    vec4 near = vec4(0.0);
    g = (p - drift * 1.6 - vec2(0.0, Slots[i].y * 1.7)) / 0.18;
    id = floor(g);
    h = cutinHash(id + float(i) * 47.0 + Screen.w + 3.0);
    if (h > 0.84) {
        vec2 o = (vec2(cutinHash(id + 1.9), cutinHash(id + 6.2)) - 0.5) * 0.3;
        float a = smoothstep(0.3, 0.12, length(fract(g) - 0.5 - o)) * 0.28;
        near = vec4(color * a, a);
    }

    float calm = 1.0 - clamp(abs(Slots[i].z) * 30.0, 0.0, 1.0);
    float face = smoothstep(0.05, 0.16, length((p - vec2(0.0, 0.68)) * vec2(1.0, 0.8)));
    return (near + mid * (1.0 - near.a)) * calm * face * Phase.z;
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
    float scale = Screen.y / 1080.0;
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

    float plate = Phase.y * ((Extra.x > 0.5 || slot >= 0) ? 1.0 : 0.0);
    if (plate > 0.0) {
        vec3 layer = energy(q, slot, aspect);
        if (slot >= 0) {
            if (Phase.w > 0.5 && Slots[slot].w > 0.5) {
                vec4 c = character(slot, q, SlotColor[slot].rgb);
                layer = layer * (1.0 - c.a) + c.rgb;
            }
            vec4 m = motes(q, slot, aspect);
            layer = layer * (1.0 - m.a) + m.rgb;
        }
        col = mix(col, layer, plate);
    }

    col += beams(q, aspect);

    // 落位时中间横着炸开的白光云
    vec2 c = vec2((q.x - 0.5) * aspect / 0.95, (q.y - 0.46) / 0.3);
    float cloud = exp(-dot(c, c) * 1.4) * (0.8 + 0.4 * cutinValue(vec2(q.x * aspect, q.y) * 4.0 + Screen.z * 0.05));
    col += (vec3(1.0) - clamp(col, 0.0, 1.0)) * clamp(cloud * Light.x, 0.0, 1.0);

    // 曝光：亮处先烧白、饱和度掉（溢出来的光分给另外两个通道）
    col *= Misc.y;
    float hot = max(max(col.r, col.g), col.b) - 1.0;
    col = min(col + max(hot, 0.0) * 0.5, vec3(1.0));

    // 光幕：屏幕混合，中间暖白、四周带一点小队的颜色、也暗一些；正中横着一道细光条
    float veil = clamp(Light.y, 0.0, 1.0);
    vec2 vq = vec2((q.x - 0.5) * aspect, q.y - 0.47);
    float radial = exp(-dot(vq, vq) * 1.8);
    vec3 sheet = mix(VeilTint.rgb, vec3(1.0, 0.97, 0.92), radial) * mix(VEIL_EDGE, VEIL_CENTER, radial);
    col = 1.0 - (1.0 - col) * (1.0 - sheet * veil);
    float streak = exp(-abs(q.y - 0.47) * Screen.y / (14.0 * scale)) * exp(-abs(q.x - 0.5) * aspect * 1.6);
    col = min(col + vec3(1.0, 0.96, 0.9) * streak * veil * 0.3, vec3(1.0));

    fragColor = vec4(col, 1.0);
}
