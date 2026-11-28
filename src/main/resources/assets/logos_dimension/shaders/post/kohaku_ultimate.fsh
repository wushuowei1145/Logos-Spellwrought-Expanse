#version 330

// 琥珀奥义「星辰花炼成 · 凝时琥珀」自己的全屏后期（docs/KOHAKU_COMPANION.md 第 20 节，KohakuUltimateScreen、KohakuUltimateLook）：
// 凝时（反色一下、RGB 色散、同心波纹、琥珀染色）→ 抓下的那一帧沿漩涡坍缩进琥珀晶石 → 晶石在她手边、指尖一碰裂开 → 全屏 Voronoi 碎镜 → 碎片朝镜头飞散。
// 晶石、碎镜都是以画面上的晶石（或画面中心）为中心的图形，几刻就过去，不是贴在屏幕上不动的纹理。不写字。
// 函数名都带 k 前缀，不和 GLSL 内置函数重名。

uniform sampler2D InSampler;      // 活的画面
uniform sampler2D FrozenSampler;  // 凝时那一刻抓下的画面

layout(std140) uniform KohakuUltimate {
    vec4 Screen;   // 宽、高（像素），第几刻，种子
    vec4 Look1;    // 反色、色散、波纹进度（小于 0 没有）、琥珀染色
    vec4 Look2;    // 坍缩进度（小于 0 没有）、晶石显出几成、裂纹、四芒星
    vec4 Look3;    // 碎镜盖住几成、裂缝爬到哪、飞散进度（小于 0 没有）、底下用抓下的那一帧（1）
    vec4 Focus;    // 波纹中心 xy、晶石中心 zw（画面 0–1，左下是 0）
    vec4 Gem;      // 晶石半高（画面高的几成）、在画面正中（1）、歪几度（弧度）、-
    vec4 Amber;    // 琥珀色
};

in vec2 texCoord;
out vec4 fragColor;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
const float PI = 3.14159265359;
const vec3 GOLD_WHITE = vec3(1.0, 0.95, 0.82);

float kHash(vec2 p) {
    p = fract(p * vec2(234.34, 435.345) + Screen.w * 0.0173);
    p += dot(p, p + 34.23);
    return fract(p.x * p.y);
}

vec2 kHash2(vec2 p) {
    return vec2(kHash(p), kHash(p + 17.31));
}

mat2 kRot(float a) {
    float c = cos(a), s = sin(a);
    return mat2(c, -s, s, c);
}

float kAspect() {
    return Screen.x / Screen.y;
}

vec3 kLive(vec2 uv) {
    return texture(InSampler, uv).rgb;
}

vec3 kFrozen(vec2 uv) {
    return texture(FrozenSampler, clamp(uv, vec2(0.001), vec2(0.999))).rgb;
}

vec3 kScene(vec2 uv) {
    return Look3.w > 0.5 ? kFrozen(uv) : kLive(uv);
}

// 按亮度染成琥珀色（暗处还是暗的），留一点原来的颜色
vec3 kAmberize(vec3 c, float k) {
    float l = dot(c, LUMA);
    return mix(c, l * Amber.rgb * 1.5 + c * 0.12, k);
}

// ---------------------------------------------------------------- 凝时：色散、波纹、反色

vec3 kTimeStop(vec2 uv) {
    float aspect = kAspect();
    vec2 d = (uv - Focus.xy) * vec2(aspect, 1.0);
    float r = length(d);
    vec2 dir = r > 1e-4 ? d / r : vec2(0.0);
    float prog = Look1.z;
    // 三圈同心波纹往外荡，越荡越淡
    float disp = 0.0, ring = 0.0;
    for (int k = 0; k < 3; k++) {
        float R = prog * 2.0 - float(k) * 0.34;
        if (R <= 0.0) continue;
        float x = (r - R) * 9.0;
        float w = exp(-x * x);
        disp += sin(x * 3.0) * w * 0.02 * (1.0 - prog * 0.6);
        ring += w * (1.0 - prog * 0.7);
    }
    vec2 u = uv + dir * disp / vec2(aspect, 1.0);
    // RGB 三个通道沿半径错开：越往外错得越多
    vec2 s = dir * Look1.y * (0.004 + 0.014 * r) / vec2(aspect, 1.0);
    vec3 c = vec3(kScene(u + s).r, kScene(u).g, kScene(u - s).b);
    c = kAmberize(c, Look1.w * 0.55);
    c += Amber.rgb * ring * 0.32 * Look1.w;
    // 第一拍反色（冷暖一下子反冲）
    return mix(c, vec3(1.0) - c, Look1.x);
}

// ---------------------------------------------------------------- 晶石

// 晶石的轮廓：竖着的六边双锥（单位：晶石半高）。返回到轮廓的有向距离（里面是负的）
float kGemShape(vec2 q) {
    vec2 a = abs(q);
    // 上下两个尖：两条斜边；中间两条竖边
    float side = a.x - 0.56;
    vec2 n = normalize(vec2(1.0, 0.56 / 0.62));
    float slope = dot(a - vec2(0.0, 1.0), n);
    return max(side, slope);
}

// 晶面编号：中间一块台面，外面六块
float kFacet(vec2 q) {
    if (kGemShape(q / 0.46) < 0.0) return 6.0;
    float a = atan(q.y, q.x);
    return floor((a + PI) / (PI / 3.0));
}

// 晶石：q 是相对晶石中心的坐标（画面高为 1），R 是半高。返回颜色和盖住多少（外面那一圈光晕也算一点）
vec4 kGem(vec2 q, float R, float crack, float glint) {
    q = kRot(Gem.z) * q;
    vec2 g = q / R;
    float d = kGemShape(g) * R;
    if (d > 0.0) {
        // 外面一圈琥珀色的光晕
        float halo = exp(-d / (0.08 * R + 0.004)) * 0.55;
        return vec4(Amber.rgb * 1.2 + GOLD_WHITE * 0.2, clamp(halo, 0.0, 1.0) * Look2.y);
    }
    float facet = kFacet(g);
    float shade = 0.72 + 0.5 * kHash(vec2(facet, 3.7));
    // 里面是凝住的那一帧：整张画面装进晶石，每一面折射得错开一点
    vec2 refr = (kHash2(vec2(facet, 9.1)) - 0.5) * 0.06;
    vec2 pic = g / vec2(1.12, 2.0) + 0.5 + refr;
    vec3 c = kAmberize(kFrozen(pic), 0.78) * shade * 1.1;
    // 外轮廓的亮边、台面一圈、晶面之间的棱（台面外面的六条）
    float edge = exp(d / (0.012 * R + 0.0015));
    float table = kGemShape(g / 0.46);
    float a = atan(g.y, g.x);
    float sector = abs(fract((a + PI) / (PI / 3.0)) - 0.5);
    float ridge = (1.0 - smoothstep(0.0, 0.025, (0.5 - sector) * length(g))) * step(0.0, table);
    float inner = exp(-abs(table) * 30.0);
    c += GOLD_WHITE * (edge * 0.9 + inner * 0.35 + ridge * 0.3);
    // 斜着一道高光，慢慢扫过去
    float band = dot(g, normalize(vec2(1.0, 0.7))) - mod(Screen.z * 0.05, 3.0) + 1.5;
    c += GOLD_WHITE * exp(-band * band * 40.0) * 0.35;
    // 裂纹：从正中一道折线贯穿，两条小岔
    if (crack > 0.0) {
        vec2 cd = normalize(vec2(0.55, 1.0));
        vec2 cn = vec2(-cd.y, cd.x);
        float along = dot(g, cd);
        float jag = (kHash(vec2(floor(along * 14.0), 2.0)) - 0.5) * 0.08 + (fract(along * 14.0) - 0.5) * 0.05 * sign(kHash(vec2(floor(along * 14.0), 5.0)) - 0.5);
        float across = abs(dot(g, cn) - jag);
        float reach = crack * 1.25;
        float line = (1.0 - smoothstep(0.012, 0.03, across)) * step(abs(along), reach);
        vec2 b1 = g - cd * 0.3;
        float br1 = (1.0 - smoothstep(0.01, 0.025, abs(dot(b1, normalize(vec2(1.0, -0.4)))))) * step(0.0, dot(b1, vec2(1.0, -0.4))) * step(length(b1), crack * 0.45);
        vec2 b2 = g + cd * 0.45;
        float br2 = (1.0 - smoothstep(0.01, 0.025, abs(dot(b2, normalize(vec2(-1.0, 0.3)))))) * step(0.0, dot(b2, vec2(-1.0, 0.3))) * step(length(b2), crack * 0.4);
        float k = max(line, max(br1, br2));
        c = mix(c, vec3(1.0), k);
        c += GOLD_WHITE * exp(-across * 18.0) * step(abs(along), reach) * 0.35;
    }
    return vec4(c, Look2.y);
}

// 四芒星：指尖碰到的那一下，横竖两道长光加一团芯（相对晶石中心，画面高为 1）
vec3 kGlint(vec2 q, float k, float R) {
    if (k <= 0.001) return vec3(0.0);
    vec2 a = abs(kRot(0.12) * q);
    float arms = exp(-a.y * 220.0) * exp(-a.x * 2.4) + exp(-a.x * 220.0) * exp(-a.y * 3.2);
    float diag = exp(-abs(a.x - a.y) * 160.0) * exp(-length(a) * 9.0) * 0.5;
    float core = exp(-dot(q, q) / (R * R * 0.04));
    return GOLD_WHITE * (arms * 1.3 + diag + core * 1.4) * k;
}

// ---------------------------------------------------------------- 坍缩进琥珀

// 漆黑的虚空：一圈圈往里卷的流线，和往中心飞的琥珀碎晶
vec3 kVoid(vec2 p, float prog) {
    float r = length(p), a = atan(p.y, p.x);
    float swirl = a * 3.0 + log(r + 0.04) * 4.5 + Screen.z * 0.9;
    float streak = pow(0.5 + 0.5 * sin(swirl * 2.0), 16.0) * smoothstep(0.04, 0.5, r);
    vec3 c = vec3(0.022, 0.016, 0.011) + Amber.rgb * streak * 0.16;
    // 碎晶：按方位分成 16 份，每份一片从外往里飞
    float N = 16.0;
    float sector = floor((a + PI) / (2.0 * PI / N));
    for (int j = -1; j <= 1; j++) {
        float s = sector + float(j);
        float h = kHash(vec2(mod(s, N), 4.2));
        float ang = (s + 0.5 + (h - 0.5) * 0.6) * (2.0 * PI / N) - PI;
        float rad = mix(1.25, 0.12, fract(h * 7.0 + prog * 1.4));
        vec2 ctr = vec2(cos(ang), sin(ang)) * rad;
        vec2 l = kRot(-ang) * (p - ctr);
        float size = 0.05 + 0.05 * h;
        float dia = abs(l.x) / (size * 1.6) + abs(l.y) / (size * 0.7);
        float fill = 1.0 - smoothstep(0.9, 1.0, dia);
        vec3 tone = mix(Amber.rgb, GOLD_WHITE, 0.25 + 0.4 * (1.0 - dia)) * (0.8 + 0.4 * h);
        c = mix(c, tone, fill * smoothstep(0.1, 0.25, rad));
    }
    return c;
}

vec3 kCollapse(vec2 uv) {
    float aspect = kAspect();
    float prog = Look2.x;
    vec2 p = (uv - 0.5) * vec2(aspect, 1.0);
    float r = length(p);
    float e = prog * prog;
    // 画面缩到晶石那么大，越靠中心扭得越厉害（漩涡）
    float scale = mix(1.0, Gem.x * 1.6, e);
    float twist = e * 5.5 * exp(-r / max(0.05, scale * 0.8)) + prog * 0.6;
    vec2 q = kRot(-twist) * p / scale;
    vec2 pic = q / vec2(aspect, 1.0) + 0.5;
    vec3 c = kVoid(p, prog);
    bool inside = all(greaterThan(pic, vec2(0.0))) && all(lessThan(pic, vec2(1.0)));
    if (inside) {
        vec2 edge = min(pic, 1.0 - pic);
        float rim = 1.0 - smoothstep(0.0, 0.03, min(edge.x, edge.y));
        vec3 img = kAmberize(kFrozen(pic), 0.35 + 0.45 * prog) * (1.0 - 0.35 * prog);
        img += Amber.rgb * rim * (0.6 + prog);
        c = img;
    }
    // 晶石的轮廓最后几成里收拢出来，把画面关在里面
    if (Look2.y > 0.0) {
        vec4 g = kGem(p, Gem.x, 0.0, 0.0);
        float d = kGemShape(kRot(Gem.z) * p / Gem.x);
        float outside = smoothstep(0.0, 0.02, d) * Look2.y;
        c = mix(c, kVoid(p, prog), outside);
        c = mix(c, g.rgb, g.a * (d < 0.0 ? 1.0 : 0.6));
    }
    return c;
}

// ---------------------------------------------------------------- 碎镜

// Voronoi：x 到最近的点的距离，到裂缝的距离，所在格的编号
vec4 kVoronoi(vec2 x) {
    vec2 n = floor(x), f = fract(x);
    float md = 8.0;
    vec2 mr = vec2(0.0), mg = vec2(0.0);
    for (int j = -1; j <= 1; j++) {
        for (int i = -1; i <= 1; i++) {
            vec2 g = vec2(float(i), float(j));
            vec2 r = g + kHash2(n + g) - f;
            float d = dot(r, r);
            if (d < md) {
                md = d;
                mr = r;
                mg = g;
            }
        }
    }
    float me = 8.0;
    for (int j = -2; j <= 2; j++) {
        for (int i = -2; i <= 2; i++) {
            vec2 g = mg + vec2(float(i), float(j));
            vec2 r = g + kHash2(n + g) - f;
            if (dot(mr - r, mr - r) > 1e-5) me = min(me, dot(0.5 * (mr + r), normalize(r - mr)));
        }
    }
    return vec4(sqrt(md), me, n + mg);
}

// 碎片越靠中心越碎：把坐标往外推一下再切
vec2 kShardSpace(vec2 p) {
    float r = length(p);
    return p * (1.0 + 1.2 / (1.0 + r * 5.0)) * 6.5;
}

vec3 kShardColor(vec2 p, vec4 v, float spread, float aspect) {
    vec2 id = v.zw;
    float h = kHash(id + 1.3);
    float r = length(p);
    float cracked = 1.0 - smoothstep(spread * 1.3 - 0.12, spread * 1.3, r);
    // 每一块折射的角度不一样：错开一点再取那一帧
    vec2 off = (kHash2(id + 7.7) - 0.5) * 0.06 * cracked;
    vec2 pic = (p + off) / vec2(aspect, 1.0) + 0.5;
    vec3 c = kAmberize(kFrozen(pic), 0.3 + 0.35 * h) * (0.82 + 0.36 * h);
    // 裂缝：一道细白线，往两边透出白金色的光
    float e = v.y / 6.5;
    float line = 1.0 - smoothstep(0.0012, 0.0035, e);
    float glow = exp(-e * 70.0);
    c = mix(c, vec3(1.0), line * cracked);
    c += (Amber.rgb * 0.8 + GOLD_WHITE * 0.4) * glow * cracked * 0.9;
    // 中心喷出来的强光
    c += GOLD_WHITE * exp(-r * 4.0) * 0.45 * spread;
    return c;
}

vec3 kShatter(vec2 uv) {
    float aspect = kAspect();
    vec2 p = (uv - 0.5) * vec2(aspect, 1.0);
    return kShardColor(p, kVoronoi(kShardSpace(p)), Look3.y, aspect);
}

// 碎片空间里的一点 s 对应画面上哪一点（kShardSpace 的反函数，按半径迭代几次就够准）
vec2 kFromShard(vec2 s) {
    float len = length(s);
    float rc = len / 13.0;
    for (int i = 0; i < 5; i++) rc = len / (6.5 * (1.0 + 1.2 / (1.0 + rc * 5.0)));
    return len > 1e-5 ? s / len * rc : vec2(0.0);
}

// 飞散：每一块从中心往外飞、朝镜头变大、转一点，块与块之间露出活的画面（星辰花、冲击帧在那里）
vec3 kBurst(vec2 uv) {
    float aspect = kAspect();
    float b = Look3.z;
    vec2 p = (uv - 0.5) * vec2(aspect, 1.0);
    float grow = 1.0 + 2.4 * b * b;
    // 先猜这一点落在哪一块：整体往外放大 grow 倍以前它在哪
    vec4 v0 = kVoronoi(kShardSpace(p / grow));
    vec2 id = v0.zw;
    float h = kHash(id + 3.3);
    vec2 c = kFromShard(id + kHash2(id));
    float scale = 1.0 + b * (0.6 + 1.2 * h);
    float spin = (h - 0.5) * 2.4 * b;
    // 这一块飞到了 c * grow，自己放大 scale 倍、转 spin：反推这一点原来在这一块的哪里
    vec2 q = c + kRot(-spin) * (p - c * grow) / scale;
    vec4 v = kVoronoi(kShardSpace(q));
    float alive = 1.0 - smoothstep(0.45 + 0.3 * h, 1.0, b);
    if (any(notEqual(v.zw, id)) || alive <= 0.0) return kLive(uv);
    vec3 col = kShardColor(q, v, 1.0, aspect);
    // 边缘先烧亮再散掉
    col += GOLD_WHITE * (1.0 - smoothstep(0.0, 0.01, v.y / 6.5)) * b;
    return mix(kLive(uv), col, alive);
}

void main() {
    vec2 uv = texCoord;
    float aspect = kAspect();
    vec3 c = kScene(uv);
    if (Look1.z >= 0.0) c = kTimeStop(uv);
    if (Look2.x >= 0.0) {
        c = kCollapse(uv);
    } else if (Look2.y > 0.0 && Gem.x > 0.0001) {
        vec2 q = (uv - Focus.zw) * vec2(aspect, 1.0);
        vec4 g = kGem(q, Gem.x, Look2.z, Look2.w);
        c = mix(c, g.rgb, clamp(g.a, 0.0, 1.0));
        c += kGlint(q, Look2.w, Gem.x);
    }
    if (Look3.z >= 0.0) c = kBurst(uv);
    else if (Look3.x > 0.0) c = mix(c, kShatter(uv), Look3.x);
    fragColor = vec4(c, 1.0);
}
