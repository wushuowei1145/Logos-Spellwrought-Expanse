#version 330

// 白梅的奥义「狐嫁入 · 水镜形代断」的全屏后期（docs/SHIRAUME_COMPANION.md 第 11 节，ShiraumeUltimateScreen）。依次是：
//   夜雨水镜的倒影 → 潜进水镜（整个画面上下翻；镜头同时滚转 180°，合起来就是左右颠倒、站得正的镜中世界）
//   → 浮世绘木刻（色阶、限定色、粗墨线、和纸纹、地上的青海波，天上一轮朱月）
//   → 千纸、因果丝、掌前的纸形代和居合那一刀 → 破镜（存下的镜中画面裂开、转着飞散，露出后面翻正的现世）
//   → 现世黑白高反差（只留淡粉和朱红）、斜贯全屏的因果斩（两侧错开）和九尾。
// 贴在东西上的纹理（和纸纹、青海波、水洼的边、月面）都按深度还原的世界坐标或视线方向取，不贴在屏幕上；
// 纸人、因果丝、刀光、碎片、九尾是画面上的图形，跟着她、敌人在画面上的位置走，几刻就过去。不写字。

uniform sampler2D InSampler;    // 这一帧画面的副本
uniform sampler2D DepthSampler; // 半透明之前的深度（地形、她和敌人；没有玻璃和水）
uniform sampler2D HeldSampler;  // 破镜前最后一帧的镜中画面

layout(std140) uniform ShiraumeUltimate {
    mat4 InvViewProj;  // 裁剪空间 → 相对镜头的坐标（按镜头真正的朝向，也就是上下翻之前的画面）
    vec4 Screen;       // 宽、高（像素），第几刻，深度是否 0 到 1
    vec4 Camera;       // 镜头的世界坐标 x（每 4096 格重复）、y、z（每 4096 格重复），种子
    vec4 Look;         // x 夜雨的压暗去色，y 水面线（画面 0–1，下面是倒影），z 水漫过画面几成，w 倒影的暗度
    vec4 Puddle;       // xyz 水洼中心（相对镜头），w 半径（格，0 没有）
    vec4 Ripple;       // xy 雨滴落点（画面），z 落下以后几刻（小于 0 没有），w 水面高度（相对镜头）
    vec4 Style;        // x 浮世绘几成，y 镜中（1 时整个画面上下翻），z 现世黑白高反差几成，w 隐藏闪电的闪烁
    vec4 Moon;         // xyz 月亮的方向（世界），w 半径（弧度）
    vec4 MoonOn;       // x 月亮几成，yzw -
    vec4 Hand;         // xy 她捻形代的手（画面），z 纸人多高（画面高度的几成），w 0 没有、1 拿着、2 斩开了
    vec4 Doll;         // x 斩开以后烧到几成，y 居合那一刀开始以后几刻（小于 0 没有），zw -
    vec4 Counts;       // x 因果丝几根，y 因果丝几成，z 千纸几张，w 千纸几成
    vec4 Threads[4];   // 被贴住的敌人在画面上的位置，一个 vec4 放两个
    vec4 Papers[16];   // 千纸：xy 画面位置，z 多高（画面高度的几成），w 转角（弧度）
    vec4 Shatter;      // x 碎到几成（小于 0 没有），yz 从哪裂开（画面），w -
    vec4 Slash;        // x 因果斩落下以后几刻（小于 0 没有），yz 斩痕经过的点（画面），w 斜度（弧度，0 水平、正的往右上）
    vec4 Slash2;       // x 斩痕两侧错开多少（像素），y 九尾几成，z 九尾长到几成，w -
    vec4 Tails;        // xy 九尾的根（画面），z 九尾多长（画面高度的几成），w -
};

in vec2 texCoord;
out vec4 fragColor;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
const float TAU = 6.28318530718;
const float PI = 3.14159265359;
// 世界坐标每隔这么远重复一次（Java 先取了模），纹理的频率都是 2 的幂，折返处接得上
const float PERIOD = 4096.0;
const vec3 PAPER = vec3(0.961, 0.922, 0.839);      // #F5EBD6 宣纸
const vec3 PAPER_WARM = vec3(0.925, 0.835, 0.690); // 近地平线的旧纸色
const vec3 INK = vec3(0.102, 0.090, 0.094);        // #1A1718 浓墨
const vec3 VERMILION = vec3(0.78, 0.20, 0.18);     // 朱
const vec3 SAKURA = vec3(1.0, 0.62, 0.77);         // #FF9EC4 淡粉狐火
const vec3 SAKURA_CORE = vec3(1.0, 0.94, 0.97);    // 狐火的白芯
const vec3 INDIGO = vec3(0.18, 0.29, 0.43);        // 藍摺
const vec3 PINE = vec3(0.45, 0.51, 0.36);          // 松葉
const vec3 KATASHIRO = vec3(0.985, 0.97, 0.935);   // 形代的白纸

// ---------------------------------------------------------------- 小工具

float suLuma(vec3 c) {
    return dot(c, LUMA);
}

float suAspect() {
    return Screen.x / Screen.y;
}

float suHash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

// 值噪声；period 的分量大于 0 时那个方向每 period 个周期重复一次
float suNoise(vec2 x, vec2 period) {
    vec2 i = floor(x), f = fract(x);
    vec2 u = f * f * (3.0 - 2.0 * f);
    vec2 j = i + 1.0;
    if (period.x > 0.0) {
        i.x = mod(i.x, period.x);
        j.x = mod(j.x, period.x);
    }
    if (period.y > 0.0) {
        i.y = mod(i.y, period.y);
        j.y = mod(j.y, period.y);
    }
    float a = suHash(i), b = suHash(vec2(j.x, i.y)), c = suHash(vec2(i.x, j.y)), d = suHash(j);
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

// 贴在世界上的噪声。freq 是每格几个周期（2 的幂），坐标取模的那一刻才接得上
float suWorldNoise(vec2 x, vec2 freq) {
    return suNoise(x * freq, PERIOD * freq);
}

mat2 suRot(float a) {
    float c = cos(a), s = sin(a);
    return mat2(c, s, -s, c);
}

float suDepth(vec2 uv) {
    return texture(DepthSampler, uv).r;
}

// 相对镜头的位置
vec3 suRelative(vec2 uv, float d) {
    float z = Screen.w > 0.5 ? d : d * 2.0 - 1.0;
    vec4 h = InvViewProj * vec4(uv * 2.0 - 1.0, z, 1.0);
    return h.xyz / h.w;
}

// 世界坐标：x、z 每 4096 格重复
vec3 suWorld(vec3 rel) {
    return vec3(mod(rel.x + Camera.x, PERIOD), rel.y + Camera.y, mod(rel.z + Camera.z, PERIOD));
}

float suDistanceAt(vec2 uv) {
    float d = suDepth(uv);
    if (d >= 1.0) return 1e4;
    return length(suRelative(uv, d));
}

// 从深度还原法线：左右、上下各取离得近的那一边，跨过轮廓时不会歪；朝向镜头
vec3 suNormal(vec2 uv, vec2 px, vec3 rel) {
    vec3 r = suRelative(uv + vec2(px.x, 0.0), suDepth(uv + vec2(px.x, 0.0))) - rel;
    vec3 l = rel - suRelative(uv - vec2(px.x, 0.0), suDepth(uv - vec2(px.x, 0.0)));
    vec3 u = suRelative(uv + vec2(0.0, px.y), suDepth(uv + vec2(0.0, px.y))) - rel;
    vec3 d = rel - suRelative(uv - vec2(0.0, px.y), suDepth(uv - vec2(0.0, px.y)));
    vec3 n = normalize(cross(dot(r, r) < dot(l, l) ? r : l, dot(u, u) < dot(d, d) ? u : d));
    return dot(n, rel) > 0.0 ? -n : n;
}

// ---------------------------------------------------------------- 月亮

// 天上的一轮月：按视线方向画，跟着世界转。printed 为 1 时是浮世绘的平涂朱月，0 时是现世的绯红满月
vec3 suMoon(vec3 c, vec3 rel, bool sky, float printed) {
    if (MoonOn.x <= 0.0 || !sky) return c;
    vec3 dir = normalize(rel);
    vec3 m = normalize(Moon.xyz);
    float r = Moon.w;
    float ang = acos(clamp(dot(dir, m), -1.0, 1.0));
    if (ang > r * 5.0) return c;
    vec3 ax = normalize(cross(abs(m.y) < 0.99 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0), m));
    vec3 ay = cross(m, ax);
    vec2 q = vec2(dot(dir, ax), dot(dir, ay)) / sin(r);
    float mottled = suNoise(q * 2.5 + 11.0, vec2(0.0)) * 0.6 + suNoise(q * 6.0 + 3.0, vec2(0.0)) * 0.4;
    vec3 real = mix(vec3(0.55, 0.08, 0.11), vec3(0.92, 0.30, 0.30), smoothstep(0.3, 0.75, mottled));
    real = mix(real, vec3(1.0, 0.58, 0.52), smoothstep(0.7, 1.0, length(q)) * 0.35);
    vec3 print = mix(VERMILION, VERMILION * 0.86, smoothstep(0.55, 0.7, mottled) * 0.6);
    vec3 face = mix(real, print, printed);
    float disc = 1.0 - smoothstep(r * 0.985, r, ang);
    float halo = exp(-max(ang - r, 0.0) / (r * 0.6)) * (1.0 - disc) * (1.0 - printed);
    c += vec3(0.85, 0.15, 0.18) * halo * 0.4 * MoonOn.x;
    // 浮世绘的月边一圈墨线
    float rim = printed * (1.0 - smoothstep(0.0, r * 0.03, abs(ang - r * 0.985)));
    c = mix(c, face, disc * MoonOn.x);
    return mix(c, INK, rim * 0.8 * MoonOn.x);
}

// ---------------------------------------------------------------- 现世：夜雨、黑白高反差

vec3 suNight(vec3 c, float w) {
    float l = suLuma(c);
    vec3 n = mix(c, vec3(l), 0.6) * vec3(0.6, 0.63, 0.82) * 0.62;
    return mix(c, n, w);
}

// 漆黑的现世：天压成墨黑，地和人是高反差的黑白，只留淡粉、朱红（狐火、月亮、她的衣裳）
vec3 suMono(vec3 c, float w, bool sky) {
    float l = suLuma(c);
    float k = clamp((l - 0.5) * 2.2 + 0.5, 0.0, 1.0);
    k = k * k * (3.0 - 2.0 * k);
    float mx = max(c.r, max(c.g, c.b)), mn = min(c.r, min(c.g, c.b));
    float sat = mx > 1e-3 ? (mx - mn) / mx : 0.0;
    float pink = smoothstep(0.22, 0.45, sat) * smoothstep(0.03, 0.14, c.r - max(c.g, c.b * 0.9));
    vec3 grey = sky ? vec3(0.05, 0.045, 0.06) : vec3(mix(0.04, 0.8, k)) * vec3(0.95, 0.94, 0.98);
    vec3 kept = mix(grey, c * 1.1, pink);
    return mix(c, kept, w);
}

// ---------------------------------------------------------------- 浮世绘木刻

// 五阶：浓墨、焦茶、灰褐、浅米、宣纸
vec3 suPalette(float l) {
    const float e = 0.022;
    vec3 c = INK;
    c = mix(c, vec3(0.29, 0.25, 0.23), smoothstep(0.15 - e, 0.15 + e, l));
    c = mix(c, vec3(0.57, 0.51, 0.44), smoothstep(0.31 - e, 0.31 + e, l));
    c = mix(c, vec3(0.86, 0.79, 0.67), smoothstep(0.50 - e, 0.50 + e, l));
    c = mix(c, PAPER, smoothstep(0.70 - e, 0.70 + e, l));
    return c;
}

// 色相 0–1
float suHue(vec3 c) {
    float mx = max(c.r, max(c.g, c.b)), mn = min(c.r, min(c.g, c.b));
    float d = mx - mn;
    if (d < 1e-4) return 0.0;
    float h;
    if (mx == c.r) h = mod((c.g - c.b) / d, 6.0);
    else if (mx == c.g) h = (c.b - c.r) / d + 2.0;
    else h = (c.r - c.g) / d + 4.0;
    return h / 6.0;
}

// 色阶加限定色：红和粉留成朱与淡粉，蓝成藍摺，绿成松葉，别的都落进米色到墨的五阶里
vec3 suTone(vec3 raw) {
    float l = clamp(pow(suLuma(raw), 0.82) * 1.08, 0.0, 1.0);
    vec3 tone = suPalette(l);
    float mx = max(raw.r, max(raw.g, raw.b)), mn = min(raw.r, min(raw.g, raw.b));
    float sat = mx > 1e-3 ? (mx - mn) / mx : 0.0;
    float h = suHue(raw);
    float w = smoothstep(0.22, 0.5, sat) * smoothstep(0.08, 0.2, mx);
    vec3 hue = tone;
    if (h < 0.06 || h > 0.84) hue = l > 0.55 ? mix(SAKURA, PAPER, 0.3) : VERMILION * mix(0.75, 1.1, l);
    else if (h > 0.48 && h < 0.75) hue = INDIGO * mix(0.7, 1.5, l);
    else if (h > 0.17 && h < 0.48) hue = PINE * mix(0.6, 1.35, l);
    return mix(tone, hue, w * 0.85);
}

// 粗墨线：周围有像素比这里远得多，这里就是东西的边（人、敌人、方块的外轮廓）
float suContour(vec2 uv, float dist) {
    vec2 px = 1.0 / Screen.xy * (Screen.y / 720.0) * 1.7;
    float jump = 0.0;
    for (int k = 0; k < 8; k++) {
        float a = float(k) * 0.7853982;
        jump = max(jump, suDistanceAt(uv + vec2(cos(a), sin(a)) * px) - dist);
    }
    float threshold = max(0.6, dist * 0.05);
    return smoothstep(threshold, threshold * 1.8, jump);
}

// 细墨线：颜色的边（衣裳、脸），阈值高，免得方块的纹理满屏是线
float suEdges(vec2 uv) {
    vec2 px = 1.0 / Screen.xy * (Screen.y / 720.0);
    float tl = suLuma(texture(InSampler, uv + px * vec2(-1.0, 1.0)).rgb);
    float t = suLuma(texture(InSampler, uv + px * vec2(0.0, 1.0)).rgb);
    float tr = suLuma(texture(InSampler, uv + px * vec2(1.0, 1.0)).rgb);
    float l = suLuma(texture(InSampler, uv + px * vec2(-1.0, 0.0)).rgb);
    float r = suLuma(texture(InSampler, uv + px * vec2(1.0, 0.0)).rgb);
    float bl = suLuma(texture(InSampler, uv + px * vec2(-1.0, -1.0)).rgb);
    float b = suLuma(texture(InSampler, uv + px * vec2(0.0, -1.0)).rgb);
    float br = suLuma(texture(InSampler, uv + px * vec2(1.0, -1.0)).rgb);
    float gx = (tr + 2.0 * r + br) - (tl + 2.0 * l + bl);
    float gy = (tl + 2.0 * t + tr) - (bl + 2.0 * b + br);
    return smoothstep(0.55, 0.9, length(vec2(gx, gy)));
}

// 和纸：细颗粒加几缕长纤维，按世界坐标取，挑东西朝向的那两个轴
float suFiber(vec3 world, vec3 n) {
    vec2 p = abs(n.y) > 0.6 ? world.xz : (abs(n.x) > abs(n.z) ? world.zy : world.xy);
    float grain = suWorldNoise(p, vec2(8.0)) * 0.5 + suWorldNoise(p + 3.7, vec2(16.0)) * 0.5;
    float fiber = suWorldNoise(p + 9.1, vec2(4.0, 0.5));
    return (grain - 0.5) * 0.07 + smoothstep(0.72, 0.9, fiber) * 0.04;
}

// 青海波：地上一排排叠起来的同心弧（半径 0.5 的圆，行距 0.25、隔行错开半个），后一排压在前一排上
float suWaves(vec2 p) {
    p /= 1.1;
    float rowLow = ceil((p.y - 0.5) / 0.25), rowHigh = floor((p.y + 0.5) / 0.25);
    for (float row = rowHigh; row >= rowLow; row -= 1.0) {
        float off = mod(row, 2.0) * 0.5;
        float cx = floor(p.x - off + 0.5) + off;
        vec2 c = vec2(cx, row * 0.25);
        float d = length(p - c);
        if (d < 0.5) {
            float f = d / 0.5 * 4.0;
            float ring = 1.0 - smoothstep(0.06, 0.16, abs(f - floor(f + 0.5)));
            float outer = 1.0 - smoothstep(0.45, 0.49, d);
            return max(ring * step(0.3, f), 1.0 - outer);
        }
    }
    return 0.0;
}

// 浮世绘的天：旧纸色，两道横着的霞（すやり霞：一段段的带子，带里略深，上下边勾细墨），一轮平涂的朱月
vec3 suUkiyoSky(vec3 rel) {
    vec3 dir = normalize(rel);
    vec3 c = mix(PAPER, PAPER_WARM, smoothstep(0.35, -0.05, dir.y));
    float az = atan(dir.z, dir.x) / TAU;
    float el = asin(clamp(dir.y, -1.0, 1.0));
    for (int k = 0; k < 2; k++) {
        float center = k == 0 ? 0.09 : 0.27, half_ = k == 0 ? 0.032 : 0.022;
        float y = el + (suNoise(vec2(az * 16.0 + float(k) * 5.0, 0.5), vec2(16.0, 0.0)) - 0.5) * 0.05;
        float seg = smoothstep(0.5, 0.56, suNoise(vec2(az * 8.0 + float(k) * 3.1, 1.5), vec2(8.0, 0.0)));
        float inside = 1.0 - smoothstep(half_ - 0.003, half_ + 0.003, abs(y - center));
        float edge = 1.0 - smoothstep(0.0, 0.0035, abs(abs(y - center) - half_));
        c = mix(c, vec3(0.93, 0.80, 0.74), inside * seg * 0.6);
        c = mix(c, INK, edge * seg * 0.55);
    }
    c *= 1.0 + (suNoise(vec2(az * 512.0, el * 160.0), vec2(512.0, 0.0)) - 0.5) * 0.05;
    return suMoon(c, rel, true, 1.0);
}

vec3 suUkiyo(vec2 src, vec3 raw, vec3 rel, bool sky, float d) {
    if (sky) return suUkiyoSky(rel);
    vec2 px = 1.0 / Screen.xy;
    float dist = length(rel);
    vec3 n = suNormal(src, px * (Screen.y / 720.0), rel);
    vec3 world = suWorld(rel);
    vec3 c = suTone(raw);
    // 朝上的地面：浅浅的藍、一层青海波
    float ground = smoothstep(0.75, 0.92, n.y) * (1.0 - smoothstep(12.0, 26.0, dist));
    if (ground > 0.0) {
        c = mix(c, mix(c, vec3(0.80, 0.82, 0.80), 0.35), ground);
        c = mix(c, INDIGO * 0.8, suWaves(world.xz) * 0.5 * ground);
    }
    c *= 1.0 + suFiber(world, n);
    float ink = max(suContour(src, dist), suEdges(src) * 0.55);
    return mix(c, INK, ink * 0.92);
}

// ---------------------------------------------------------------- 画面

// 一个像素在 src（上下翻之前的画面）那一点该是什么样：现世（夜雨、黑白、月）或者浮世绘
vec3 suScene(vec2 src) {
    vec3 raw = texture(InSampler, src).rgb;
    float d = suDepth(src);
    bool sky = d >= 1.0;
    // 天上取射线上任意一点就够（远平面可能在无穷远）
    vec3 rel = suRelative(src, sky ? 0.5 : d);
    vec3 c = raw;
    if (Look.x > 0.0) c = suNight(c, Look.x);
    if (Style.z > 0.0) c = suMono(c, Style.z, sky);
    c = suMoon(c, rel, sky, 0.0);
    if (Style.x > 0.001) c = mix(c, suUkiyo(src, raw, rel, sky, d), Style.x);
    return c;
}

// 水洼：她脚前那一滩，边是按世界坐标取的噪声
float suPuddle(vec2 uv) {
    if (Puddle.w <= 0.0) return 0.0;
    float d = suDepth(uv);
    if (d >= 1.0) return 0.0;
    vec3 rel = suRelative(uv, d);
    if (abs(rel.y - Ripple.w) > 0.4) return 0.0;
    vec3 w = suWorld(rel);
    float r = length(rel.xz - Puddle.xz);
    float edge = Puddle.w * (0.75 + 0.45 * suWorldNoise(w.xz, vec2(0.5)));
    return 1.0 - smoothstep(edge - 0.3, edge, r);
}

// 雨滴落下的涟漪：斜着看的水面，圈压扁；返回取倒影时错开多少，crest 是波峰（泛淡粉）
vec2 suRipple(vec2 uv, out float crest) {
    crest = 0.0;
    if (Ripple.z < 0.0) return vec2(0.0);
    vec2 d = (uv - Ripple.xy) * vec2(suAspect(), 3.2);
    float r = length(d);
    float front = Ripple.z * 0.05 + 0.01;
    float env = smoothstep(front + 0.02, front - 0.04, r) * exp(-r * 2.6) * exp(-Ripple.z * 0.07);
    float w = sin(r * 70.0 - Ripple.z * 2.6);
    crest = max(w, 0.0) * env;
    return normalize(d + 1e-5) * w * env * 0.008;
}

// ---------------------------------------------------------------- 纸形代、因果丝、居合

// 纸形代：圆头、平伸的两臂、往下张开的长衣，衣摆剪平。q 以纸人中心为原点、半高为 1、y 朝上；edge 是到边的距离
float suDollShape(vec2 q, out float edge) {
    float head = length(q - vec2(0.0, 0.64)) - 0.24;
    vec2 aq = abs(q - vec2(0.0, 0.24));
    float arms = max(aq.x - 0.64 + aq.y * 0.4, aq.y - 0.09);
    float w = mix(0.19, 0.42, clamp((0.34 - q.y) / 1.25, 0.0, 1.0));
    float body = max(abs(q.x) - w, max(q.y - 0.38, -0.92 - q.y));
    float d = min(head, min(arms, body));
    edge = d;
    return 1.0 - smoothstep(-0.015, 0.015, d);
}

// 一张纸人：白纸、墨边、正中一道折痕。返回颜色和盖住多少
vec4 suDoll(vec2 uv, vec2 pos, float height, float angle) {
    vec2 p = (uv - pos) * vec2(suAspect(), 1.0);
    vec2 q = suRot(-angle) * p / max(height * 0.5, 1e-4);
    if (dot(q, q) > 2.5) return vec4(0.0);
    float edge;
    float cover = suDollShape(q, edge);
    float line = 1.0 - smoothstep(0.03, 0.08, abs(edge));
    float fold = (1.0 - smoothstep(0.0, 0.03, abs(q.x))) * step(q.y, 0.38) * step(-0.92, q.y) * 0.35;
    vec3 col = mix(KATASHIRO, INK, max(line * 0.9, fold));
    return vec4(col, max(cover, line * (1.0 - smoothstep(0.06, 0.08, abs(edge)))));
}

vec3 suPapers(vec3 c, vec2 uv) {
    if (Counts.w <= 0.0) return c;
    for (int i = 0; i < 16; i++) {
        if (float(i) >= Counts.z) break;
        vec4 p = Papers[i];
        if (p.z <= 0.0) continue;
        vec4 d = suDoll(uv, p.xy, p.z, p.w);
        c = mix(c, d.rgb, d.a * Counts.w);
    }
    return c;
}

vec3 suThreads(vec3 c, vec2 uv) {
    if (Counts.y <= 0.0 || Counts.x < 1.0) return c;
    vec2 asp = vec2(suAspect(), 1.0);
    vec2 p = uv * asp, h = Hand.xy * asp;
    float px = 1.0 / Screen.y;
    float core = 0.0, glow = 0.0;
    for (int i = 0; i < 8; i++) {
        if (float(i) >= Counts.x) break;
        vec4 pair = Threads[i / 2];
        vec2 e = (i % 2 == 0 ? pair.xy : pair.zw) * asp;
        vec2 ab = h - e;
        float len2 = max(dot(ab, ab), 1e-8);
        float t = clamp(dot(p - e, ab) / len2, 0.0, 1.0);
        // 绷紧的弦：中间轻轻地颤
        vec2 nrm = vec2(-ab.y, ab.x) / sqrt(len2);
        vec2 q = e + ab * t + nrm * sin(t * PI) * sin(Screen.z * 2.3 + float(i) * 1.7) * 0.0035;
        float d = length(p - q);
        core = max(core, 1.0 - smoothstep(px * 0.7, px * 1.9, d));
        glow = max(glow, exp(-d / (px * 6.0)) * 0.65);
    }
    c = mix(c, vec3(1.0, 0.34, 0.46), glow * Counts.y);
    return mix(c, vec3(1.0, 0.88, 0.92), core * Counts.y);
}

// 掌前那一张：拿着；斩开以后上下两半错开、转着，从边上烧成淡粉狐火
vec3 suHandDoll(vec3 c, vec2 uv) {
    if (Hand.w < 0.5 || Hand.z <= 0.0) return c;
    if (Hand.w < 1.5) {
        vec4 d = suDoll(uv, Hand.xy, Hand.z, -0.08);
        return mix(c, d.rgb, d.a);
    }
    float k = Doll.x;
    for (int half_ = 0; half_ < 2; half_++) {
        bool top = half_ == 0;
        float apart = smoothstep(0.0, 0.5, k);
        vec2 off = (top ? vec2(-0.10, 0.08) : vec2(0.08, -0.12)) * Hand.z * apart;
        float spin = (top ? 0.35 : -0.3) * apart - 0.08;
        vec2 p = (uv - Hand.xy - off / vec2(suAspect(), 1.0)) * vec2(suAspect(), 1.0);
        vec2 q = suRot(-spin) * p / max(Hand.z * 0.5, 1e-4);
        if (dot(q, q) > 2.5) continue;
        float edge;
        float cover = suDollShape(q, edge) * (top ? step(0.12, q.y) : step(q.y, 0.12));
        float n = suNoise(q * 3.2 + (top ? 5.0 : 9.0), vec2(0.0)) * 0.7 + suNoise(q * 7.0 + 2.0, vec2(0.0)) * 0.3;
        float burnt = k * 1.25;
        float left = smoothstep(burnt, burnt + 0.04, n);
        float ember = (smoothstep(burnt - 0.02, burnt + 0.05, n) - smoothstep(burnt + 0.05, burnt + 0.16, n)) * step(0.02, k);
        float line = (1.0 - smoothstep(0.03, 0.08, abs(edge))) * (top ? step(0.12, q.y) : step(q.y, 0.12));
        vec3 col = mix(KATASHIRO, INK, line * 0.9);
        c = mix(c, col, max(cover, line * 0.8) * left);
        c = mix(c, mix(SAKURA, SAKURA_CORE, 0.4), ember * cover);
        c += SAKURA * ember * cover * 0.6;
    }
    return c;
}

// 居合：横着一道微弯的粉白刀光，从左往右一闪而过，穿过纸人的腰；毛笔飞白（顺着刀走的方向断断续续）
vec3 suIaido(vec3 c, vec2 uv) {
    float t = Doll.y;
    if (t < 0.0 || t > 9.0) return c;
    float asp = suAspect();
    vec2 p = uv * vec2(asp, 1.0), h = Hand.xy * vec2(asp, 1.0);
    float x = p.x - h.x;
    float span = asp * 0.62;
    float u = x / span;
    float y = p.y - h.y - Hand.z * 0.03 + 0.07 * u * u;
    float head = -1.0 + 2.6 * clamp(t / 1.4, 0.0, 1.0);
    float shown = step(u, head) * (1.0 - smoothstep(0.85, 1.0, abs(u)));
    float life = 1.0 - smoothstep(2.0, 9.0, t);
    float width = (0.016 * (1.0 - u * u) + 0.002) * mix(1.0, 0.5, smoothstep(1.5, 6.0, t));
    float brush = smoothstep(0.25, 0.55, suNoise(vec2(u * 4.0, y / max(width, 1e-4) * 1.5), vec2(0.0)));
    float a = abs(y);
    float core = (1.0 - smoothstep(width * 0.3, width * 0.6, a)) * mix(0.7, 1.0, brush);
    float rim = (1.0 - smoothstep(width * 0.6, width, a)) * brush;
    float glow = exp(-a / (width * 2.0 + 0.002));
    float k = shown * life;
    c += SAKURA * glow * 0.45 * k;
    c = mix(c, SAKURA, rim * k);
    return mix(c, SAKURA_CORE, core * k);
}

// ---------------------------------------------------------------- 破镜

// 碎片的中心：5 × 3 的格子里各抖一下
vec2 suSeed(int i) {
    vec2 cell = vec2(float(i % 5), float(i / 5));
    vec2 j = vec2(suHash(cell + 1.3 + Camera.w * 0.001), suHash(cell + 7.1 + Camera.w * 0.001));
    return (cell + 0.2 + 0.6 * j) / vec2(5.0, 3.0);
}

// p 落在哪一块，以及离最近的边多远（画面高度）
int suCell(vec2 p, out float edge) {
    vec2 asp = vec2(suAspect(), 1.0);
    float best = 1e9, second = 1e9;
    int id = 0;
    for (int i = 0; i < 15; i++) {
        vec2 d = (p - suSeed(i)) * asp;
        float dd = dot(d, d);
        if (dd < best) {
            second = best;
            best = dd;
            id = i;
        } else if (dd < second) {
            second = dd;
        }
    }
    edge = (sqrt(second) - sqrt(best)) * 0.5;
    return id;
}

// 存下的镜中画面先裂（裂纹从斩开的那一点往外爬），再一块块转着飞散、往下掉，露出后面翻正的现世；带几滴墨
vec3 suShatter(vec3 c, vec2 uv) {
    float k = Shatter.x;
    if (k < 0.0) return c;
    vec2 asp = vec2(suAspect(), 1.0);
    vec2 center = Shatter.yz;
    float fly = clamp((k - 0.2) / 0.8, 0.0, 1.0);
    vec3 result = c;
    bool covered = false;
    for (int i = 0; i < 15; i++) {
        vec2 seed = suSeed(i);
        vec2 dir = (seed - center) * asp;
        dir = length(dir) < 1e-4 ? vec2(0.0, 1.0) : normalize(dir);
        float r = suHash(vec2(float(i), 3.3) + Camera.w * 0.001);
        vec2 off = (dir * (0.12 * fly + (0.7 + 0.5 * r) * fly * fly) + vec2(0.0, -1.2) * fly * fly) / asp;
        float spin = (r - 0.5) * 2.6 * fly;
        float scale = 1.0 - 0.3 * fly;
        vec2 local = (suRot(-spin) * ((uv - seed - off) * asp) / scale) / asp + seed;
        if (local.x < 0.0 || local.x > 1.0 || local.y < 0.0 || local.y > 1.0) continue;
        float edge;
        if (suCell(local, edge) != i) continue;
        float alpha = 1.0 - smoothstep(0.7, 1.0, fly);
        vec3 shard = texture(HeldSampler, local).rgb;
        // 裂纹：离斩开的那一点近的先裂；飞起来以后边上亮一道
        float reach = smoothstep(0.0, 0.2, k) * 1.6;
        float crack = (1.0 - smoothstep(0.0015, 0.004, edge)) * step(length((local - center) * asp), reach);
        shard = mix(shard, vec3(1.0, 0.95, 0.98), crack * 0.85);
        shard = mix(shard, SAKURA_CORE, (1.0 - smoothstep(0.0, 0.006, edge)) * step(0.001, fly) * 0.6);
        result = mix(c, shard, alpha);
        covered = true;
        break;
    }
    // 墨滴：从裂开的那一点往外甩
    for (int j = 0; j < 10; j++) {
        float a = suHash(vec2(float(j), 1.7) + Camera.w * 0.001) * TAU;
        float speed = 0.35 + 0.6 * suHash(vec2(float(j), 5.1));
        vec2 pos = center + vec2(cos(a), sin(a)) * speed * fly / asp + vec2(0.0, -0.3) * fly * fly;
        float size = (0.012 + 0.02 * suHash(vec2(float(j), 8.9))) * (1.0 - fly * 0.5);
        float drop = 1.0 - smoothstep(size * 0.8, size, length((uv - pos) * asp));
        result = mix(result, INK, drop * smoothstep(0.05, 0.2, k) * (1.0 - smoothstep(0.75, 1.0, fly)));
    }
    return result;
}

// ---------------------------------------------------------------- 因果斩、九尾

// 斩痕两侧整个错开一点（空间被一刀切错位），回弹由 Java 按刻算好
vec2 suSlashShift(vec2 uv) {
    if (Slash.x < 0.0 || Slash2.x <= 0.0) return vec2(0.0);
    vec2 px = uv * Screen.xy, at = Slash.yz * Screen.xy;
    vec2 d = vec2(cos(Slash.w), sin(Slash.w)), n = vec2(-d.y, d.x);
    float across = dot(px - at, n);
    float along = dot(px - at, d) / Screen.y;
    across += 0.10 * along * along * Screen.y;
    float side = clamp(across / 2.0, -1.0, 1.0);
    return d * side * Slash2.x * 0.5 / Screen.xy;
}

// 斜贯全屏的月牙斩痕：中间粗两头细，白芯淡粉边，一刻多从一头划到另一头
vec3 suCausal(vec3 c, vec2 uv) {
    float t = Slash.x;
    if (t < 0.0) return c;
    vec2 px = uv * Screen.xy, at = Slash.yz * Screen.xy;
    vec2 d = vec2(cos(Slash.w), sin(Slash.w)), n = vec2(-d.y, d.x);
    float along = dot(px - at, d) / Screen.y;
    float across = dot(px - at, n) / Screen.y + 0.10 * along * along;
    float half_ = 1.35;
    float head = mix(-half_, half_ * 1.1, clamp(t / 1.5, 0.0, 1.0));
    float shown = step(along, head) * (1.0 - smoothstep(half_ * 0.8, half_, abs(along)));
    float u = along / half_;
    float width = (0.032 * (1.0 - u * u) + 0.004) * mix(1.0, 0.35, smoothstep(2.0, 11.0, t));
    float life = 1.0 - smoothstep(6.0, 14.0, t);
    float a = abs(across);
    float core = 1.0 - smoothstep(width * 0.3, width * 0.6, a);
    float rim = 1.0 - smoothstep(width * 0.6, width, a);
    float glow = exp(-a / (width * 2.2 + 0.004));
    float k = shown * life * (Style.w > 0.5 ? 0.8 : 1.0);
    c += SAKURA * glow * 0.5 * k;
    c = mix(c, SAKURA * 1.05, rim * k);
    return mix(c, SAKURA_CORE, core * k);
}

// 九尾：从斩痕后面扇形地冲天炸开的九道淡粉狐火巨尾，尾尖往外卷、白。返回尾巴的颜色和盖住多少，halo 是外面一圈光
vec4 suTails(vec2 uv, out float halo) {
    halo = 0.0;
    if (Slash2.y <= 0.0 || Tails.z <= 0.0) return vec4(0.0);
    vec2 p = (uv - Tails.xy) * vec2(suAspect(), 1.0) / Tails.z;
    float grow = Slash2.z;
    vec3 col = vec3(0.0);
    float alpha = 0.0, glow = 0.0;
    for (int i = 0; i < 9; i++) {
        float fan = (float(i) - 4.0) / 4.0;
        float sway = sin(Screen.z * 0.35 + float(i) * 1.3) * 0.06;
        vec2 q = suRot(fan * 1.2 + sway) * p;
        float len = grow * (0.78 + 0.22 * (1.0 - fan * fan));
        float s = q.y / max(len, 1e-3);
        if (s < -0.05 || s > 1.08) continue;
        float sc = clamp(s, 0.0, 1.0);
        float curl = 0.22 * sc * sc * (fan >= 0.0 ? 1.0 : -1.0);
        float x = q.x + curl * len + 0.035 * sin(sc * 7.0 - Screen.z * 0.5 + float(i)) * sc * len;
        float w = len * (0.13 * pow(sin(PI * min(sc * 0.92 + 0.04, 1.0)), 0.6) * (1.0 - 0.4 * sc) + 0.008);
        float body = (1.0 - smoothstep(w * 0.85, w, abs(x))) * smoothstep(-0.05, 0.05, s);
        float core = 1.0 - smoothstep(w * 0.12, w * 0.5, abs(x));
        float tip = smoothstep(0.72, 0.95, sc);
        float flick = suNoise(vec2(x / max(w, 1e-3) * 1.5 + float(i) * 4.1, s * 5.0 - Screen.z * 0.6), vec2(0.0));
        float a = body * (0.62 + 0.3 * flick);
        glow = max(glow, exp(-max(abs(x) - w, 0.0) / (w * 0.6 + 0.01)) * smoothstep(-0.05, 0.1, s) * (1.0 - smoothstep(1.0, 1.08, s)));
        if (a > alpha) {
            alpha = a;
            col = mix(SAKURA, SAKURA_CORE, max(core * 0.8, tip * 0.75));
        }
    }
    halo = glow * Slash2.y;
    return vec4(col, alpha * Slash2.y);
}

void main() {
    vec2 uv = texCoord;
    vec2 suv = clamp(uv + suSlashShift(uv), 0.0, 1.0);
    // 镜中：整个画面上下翻（镜头同时滚转了 180°，合起来是左右颠倒、站得正的镜中世界）
    vec2 src = Style.y > 0.5 ? vec2(suv.x, 1.0 - suv.y) : suv;
    vec3 c = suScene(src);

    // 倒影：水面线下面取翻过来的那一点；潜进水里时水面往上漫过整个画面，水面线挪到正中，就是整个画面上下翻
    float flood = Look.z;
    if (Look.w > 0.0 || flood > 0.0) {
        float line = mix(Look.y, 0.5, flood);
        float top = mix(Look.y, 1.05, flood);
        float below = 1.0 - smoothstep(top - 0.004, top + 0.004, suv.y);
        float mask = below * max(suPuddle(suv), flood);
        if (mask > 0.001) {
            float crest;
            vec2 off = suRipple(suv, crest);
            vec2 rs = vec2(suv.x, 2.0 * line - suv.y) + off;
            vec3 r = suScene(clamp(rs, 0.0, 1.0)) * (rs.y > 1.0 ? 0.6 : 1.0);
            vec3 water = mix(r, r * vec3(0.5, 0.54, 0.76), Look.w) * (1.0 - 0.35 * Look.w);
            water += SAKURA * crest * 0.6 * (1.0 - flood);
            c = mix(c, water, mask);
        }
    }

    c = suThreads(c, uv);
    c = suPapers(c, uv);
    c = suHandDoll(c, uv);
    c = suIaido(c, uv);
    c = suShatter(c, uv);
    float halo;
    vec4 tails = suTails(uv, halo);
    c = mix(c, tails.rgb, clamp(tails.a, 0.0, 1.0)) + SAKURA * halo * 0.12;
    c = suCausal(c, uv);
    fragColor = vec4(c, 1.0);
}
