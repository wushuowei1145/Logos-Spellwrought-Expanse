#version 330

// 区域画风。门和球都不往世界里画几何：用深度还原每个像素的位置，门是「视线碰到物体之前有没有穿过门框」，
// 球是「到球心的距离」。比门近的东西穿不过去，自然不会被染色，所以不需要模板缓冲。

uniform sampler2D InSampler;
uniform sampler2D DepthSampler;       // 半透明之前的深度：玻璃和水不会打断门的判定
uniform sampler2D HandDepthSampler;   // 这一帧最后的深度：不开光影时只有手

const int MAX_DOORS = 4;

layout(std140) uniform WorldStyle {
    mat4 InvViewProj;                 // 从裁剪空间还原到相对镜头的坐标
    vec4 DoorCenter[MAX_DOORS];       // xyz 相对镜头，w 这扇门通向的画风
    vec4 DoorRight[MAX_DOORS];        // xyz 半宽，w 为 1 表示是镜头所在区域自己的门
    vec4 DoorUp[MAX_DOORS];
    vec4 Wave;                        // xyz 球心（相对镜头），w 半径，小于 0 表示没有波
    vec4 State;                       // x 镜头所在区域的画风，y 波外的画风，z 波外的档位，w 深度是否 0 到 1
    vec4 Extra;                       // x 门数，y 镜头所在群系的画风，z 它淡到几成，w 视距（格）
    vec4 Camera;                      // x、z 镜头位置（每 4096 格重复），y 镜头高度，w 秒
    vec4 FogTint;                     // rgb 这一帧的雾色：国画的纸色跟着它分昼夜
    vec4 Wind;                        // x、z 风吹过的路程（每 4096 格重复）：云气减去它，笔触不减；y 投影的纵向缩放
};

in vec2 texCoord;
out vec4 fragColor;

// 档位：0 原画，PREVIEW 透过门看到的预览，1 完全体
const float PREVIEW = 0.5;
// 超过这个距离都算一样远，波走到这里就把天空也换掉了
const float WAVE_REACH = 96.0;

float luma(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

vec3 relativeAt(vec2 uv) {
    float d = texture(DepthSampler, uv).r;
    float z = State.w > 0.5 ? d : d * 2.0 - 1.0;
    vec4 h = InvViewProj * vec4(uv * 2.0 - 1.0, z, 1.0);
    return h.xyz / h.w;
}

bool crossesDoor(int i, vec3 rel) {
    vec3 right = DoorRight[i].xyz, up = DoorUp[i].xyz;
    vec3 n = cross(right, up);
    float along = dot(rel, n);
    if (abs(along) < 1e-6) return false;
    float t = dot(DoorCenter[i].xyz, n) / along;
    if (t <= 0.0 || t >= 1.0) return false;
    vec3 p = rel * t - DoorCenter[i].xyz;
    return abs(dot(p, right) / dot(right, right)) < 1.0 && abs(dot(p, up) / dot(up, up)) < 1.0;
}

// 黑白。预览只去色，完全体再拉高对比、压一圈暗角
vec3 monochrome(vec3 c, vec2 uv, float level) {
    float full = smoothstep(PREVIEW, 1.0, level);
    float l = luma(c);
    l = clamp((l - 0.5) * mix(1.1, 1.45, full) + 0.5, 0.0, 1.0);
    l *= 1.0 - full * 0.35 * smoothstep(0.35, 0.95, length(uv - 0.5) * 1.25);
    return mix(c, vec3(l), smoothstep(0.0, PREVIEW, level));
}

// 把画面涂抹成色块：Kuwahara，取四个象限里颜色最平的那个的平均
vec3 painterly(vec2 uv, vec2 size) {
    vec2 px = 1.0 / size * (size.y / 720.0) * 1.6;
    const int R = 3;
    vec3 best = vec3(0.0);
    float bestVariance = 1e9;
    for (int q = 0; q < 4; q++) {
        vec2 side = vec2(q % 2 == 0 ? 1.0 : -1.0, q < 2 ? 1.0 : -1.0);
        vec3 sum = vec3(0.0), squares = vec3(0.0);
        for (int y = 0; y <= R; y++) {
            for (int x = 0; x <= R; x++) {
                vec3 c = texture(InSampler, uv + side * vec2(x, y) * px).rgb;
                sum += c;
                squares += c * c;
            }
        }
        float n = float((R + 1) * (R + 1));
        vec3 mean = sum / n;
        vec3 v = squares / n - mean * mean;
        float variance = v.r + v.g + v.b;
        if (variance < bestVariance) {
            bestVariance = variance;
            best = mean;
        }
    }
    return best;
}

// 童话风。预览：提饱和、暗部偏紫、描墨线；完全体：再涂抹成色块，亮部泛光，加一圈柔和的暗角
vec3 fairyTale(vec3 c, vec2 uv, vec2 size, bool hand, float level) {
    float full = smoothstep(PREVIEW, 1.0, level);
    vec3 base = full > 0.0 ? mix(c, painterly(uv, size), full) : c;

    float l = luma(base);
    vec3 styled = mix(vec3(l), base, 1.65);
    styled = mix(styled * vec3(0.86, 0.78, 1.08) + vec3(0.07, 0.03, 0.12), styled * vec3(1.08, 1.03, 0.94), smoothstep(0.15, 0.75, l));
    styled += full * pow(max(l - 0.55, 0.0), 2.0) * vec3(1.1, 0.95, 0.75);

    // 描边用原始深度的二阶差分：透视下平面的深度在屏幕上是线性的，所以差分恒为零，
    // 掠射角下的远处地面不会被误判成边缘，只有真正的轮廓和转折才有线。手不在这份深度里，不描
    if (!hand) {
        vec2 px = 1.5 * size.y / 720.0 / size;
        float c = texture(DepthSampler, uv).r;
        float dx = abs(texture(DepthSampler, uv + vec2(px.x, 0.0)).r + texture(DepthSampler, uv - vec2(px.x, 0.0)).r - 2.0 * c);
        float dy = abs(texture(DepthSampler, uv + vec2(0.0, px.y)).r + texture(DepthSampler, uv - vec2(0.0, px.y)).r - 2.0 * c);
        styled *= 1.0 - smoothstep(0.03, 0.12, max(dx, dy) / max(1.0 - c, 1e-6)) * 0.8;
    }
    styled *= 1.0 - full * 0.22 * smoothstep(0.4, 1.0, length(uv - 0.5) * 1.25);
    return mix(c, clamp(styled, 0.0, 1.0), smoothstep(0.0, PREVIEW, level));
}

// ---------------------------------------------------------------- 国画
// 思路照 sacshadow/3D_ChineseInkPaintingStyleShader（MIT）：底子是纸，墨只落在外轮廓（勾，两道，粗的那道更碎，是飞白）、
// 侧对镜头的面（边缘光）和中间调上的笔触（皴擦），远处的线淡。那边是逐物体的着色器，这里是后期：
// 位置和法线都从深度还原。所有纹理都贴在世界坐标上，转视角时不会有纹理停在屏幕上不动。

const vec3 PAPER = vec3(0.93, 0.90, 0.82);    // 宣纸，偏暖
const vec3 INK = vec3(0.07, 0.07, 0.08);      // 焦墨
// 淡彩：留几成原来的颜色，0 是纯水墨
const float COLOR_KEEP = 0.6;
// 云带：峰腰的云海和谷底的薄雾，单位是格
const vec4 BELT = vec4(104.0, 120.0, 134.0, 152.0);
const vec4 VALLEY = vec4(50.0, 58.0, 64.0, 80.0);
// 云气算到多远为止
const float MIST_REACH = 320.0;
// 群系画风的球形遮罩：离镜头这么远以内是原画，往外渐变，到第二个数以外才是完整的国画（天空算最远）
const vec2 BIOME_MASK = vec2(64.0, 128.0);
// 世界坐标每隔这么远重复一次，和 Java 那边的取模一致
const float WORLD_PERIOD = 4096.0;

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

// 值噪声；period 的分量大于 0 时那个方向每 period 格重复一次
float valueNoise(vec2 x, vec2 period) {
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
    float a = hash12(i), b = hash12(vec2(j.x, i.y)), c = hash12(vec2(i.x, j.y)), d = hash12(j);
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

// 贴在世界上的噪声。freq 是每格几个周期，各分量须是 2 的幂次分之一，坐标取模的那一刻才接得上
float worldNoise(vec2 x, vec2 freq) {
    return valueNoise(x * freq, WORLD_PERIOD * freq);
}

// 纸和墨的明暗跟着雾色走：白天雾色的亮度约 0.78，夜里纸也是暗的
float brightness() {
    return clamp(luma(FogTint.rgb) / 0.78, 0.04, 1.1);
}

vec3 paperTone() {
    return mix(FogTint.rgb, PAPER * brightness(), 0.85);
}

float band(float y, vec4 edges) {
    return smoothstep(edges.x, edges.y, y) * (1.0 - smoothstep(edges.z, edges.w, y));
}

// p 是减去风之后的世界坐标
float cloudDensity(vec3 p) {
    vec2 q = p.xz / 64.0;
    float n = valueNoise(q, vec2(64.0)) * 0.65 + valueNoise(q * 2.0, vec2(128.0)) * 0.35;
    return band(p.y, BELT) * smoothstep(0.47, 0.77, n) + band(p.y, VALLEY) * 0.15 * smoothstep(0.4, 0.7, n);
}

// 沿视线积云气。只在云带所在的高度里取样，近处几格不算，屋里和脚边不会起雾
float mist(vec3 rel, float dist) {
    vec3 dir = normalize(rel);
    float len = min(dist, min(Extra.w, MIST_REACH));
    float t0 = 0.0, t1 = len;
    if (abs(dir.y) > 1e-4) {
        float a = (VALLEY.x - Camera.y) / dir.y, b = (BELT.w - Camera.y) / dir.y;
        t0 = max(min(a, b), 0.0);
        t1 = min(max(a, b), len);
    } else if (Camera.y < VALLEY.x || Camera.y > BELT.w) {
        return 0.0;
    }
    if (t1 <= t0) return 0.0;
    // 云团是 64 格大小的，十步够了；这一段是整个国画里最费的
    const int STEPS = 10;
    float stride = (t1 - t0) / float(STEPS);
    vec3 origin = vec3(Camera.x - Wind.x, Camera.y, Camera.z - Wind.z);
    float optical = 0.0;
    for (int i = 0; i < STEPS; i++) {
        float t = t0 + stride * (float(i) + 0.5);
        optical += cloudDensity(origin + dir * t) * smoothstep(6.0, 40.0, t);
    }
    return 1.0 - exp(-optical * stride * 0.005);
}

// 墨的浓淡由明暗定：受光的顶面留白，向光的侧面一层淡墨，背光的侧面和树荫落重墨。
// 原版白天地面的亮度中位数约 0.3、最暗一成约 0.18。按雾色的明暗归一，夜里也分得出层次
float inkDensity(vec3 c, float bright) {
    float x = clamp(luma(c) / max(bright, 0.08), 0.0, 1.0);
    return pow(1.0 - smoothstep(0.10, 0.50, x), 1.2);
}

// 树叶：偏绿、又比受光的草地暗（原版白天叶子亮度约 0.25，草地顶面约 0.45）
float leafy(vec3 c, float bright) {
    return smoothstep(0.012, 0.045, c.g - max(c.r, c.b)) * (1.0 - smoothstep(0.30, 0.38, luma(c) / max(bright, 0.08)));
}

// 晕染：周围大半是浓墨或树叶的地方也染上墨，树叶缝里漏出来的纸就补上了，浓墨的边也软一些。
// 只看周围有几成是墨，零星的暗点（石头纹理的缝）不会洇开
float bleed(vec2 uv, vec2 px, float bright, float r) {
    float dark = 0.0;
    for (int k = 0; k < 8; k++) {
        float a = float(k) * 0.7853982 + 0.3927;
        vec3 c = texture(InSampler, uv + vec2(cos(a), sin(a)) * r * px).rgb;
        dark += max(smoothstep(0.55, 0.85, inkDensity(c, bright)), leafy(c, bright));
    }
    return smoothstep(0.45, 0.8, dark / 8.0);
}

// 一个面抹成一片墨：只在同一个平面上取平均，方块的纹理没了，面与面之间的明暗还在。
// 方块世界的面都是平的，按「采样点离这个面有多远」取舍，正好不会跨过棱角和前后的轮廓。
// 半径跟着一格纹理在屏幕上有多大走：近处纹理大就抹得开，远处小就收着
vec3 faceWash(vec2 uv, vec2 size, vec3 rel, vec3 n, float dist) {
    float texel = size.y * Wind.y / (2.0 * max(dist, 0.5) * 16.0);
    float radius = clamp(texel * 4.0, 2.0, 24.0);
    float tolerance = 0.04 + dist * 0.004;
    vec3 sum = texture(InSampler, uv).rgb;
    float total = 1.0;
    for (int k = 0; k < 16; k++) {
        float a = float(k) * 2.3999632;
        vec2 o = uv + vec2(cos(a), sin(a)) * radius * sqrt((float(k) + 0.5) / 16.0) / size;
        float w = 1.0 - smoothstep(tolerance, tolerance * 2.0, abs(dot(relativeAt(o) - rel, n)));
        sum += texture(InSampler, o).rgb * w;
        total += w;
    }
    return sum / total;
}

// 镜头到这个像素有多远，天空给一个很远的数
float distanceAt(vec2 uv) {
    if (texture(DepthSampler, uv).r >= 1.0) return 1e5;
    return length(relativeAt(uv));
}

// 从深度还原法线：左右、上下各取离得近的那一边，跨过轮廓时不会歪；朝向镜头
vec3 normalAt(vec2 uv, vec2 px, vec3 rel) {
    vec3 r = relativeAt(uv + vec2(px.x, 0.0)) - rel, l = rel - relativeAt(uv - vec2(px.x, 0.0));
    vec3 u = relativeAt(uv + vec2(0.0, px.y)) - rel, d = rel - relativeAt(uv - vec2(0.0, px.y));
    vec3 n = normalize(cross(dot(r, r) < dot(l, l) ? r : l, dot(u, u) < dot(d, d) ? u : d));
    return dot(n, rel) > 0.0 ? -n : n;
}

// 勾：只勾外轮廓。周围有像素比这里远得多，这里就是物体的边；一两格的台阶不算，不然满屏都是方块线
float contour(vec2 uv, vec2 px, float dist, float radius) {
    float jump = 0.0;
    for (int k = 0; k < 8; k++) {
        float a = float(k) * 0.7853982;
        jump = max(jump, distanceAt(uv + vec2(cos(a), sin(a)) * radius * px) - dist);
    }
    float threshold = max(1.5, dist * 0.06);
    return smoothstep(threshold, threshold * 2.0, jump);
}

// 皴：崖壁上竖着的长笔触，横向窄、竖向长，有斧劈、披麻皴的意思
float cun(vec3 world, vec3 n) {
    float side = 1.0 - smoothstep(0.2, 0.5, abs(n.y));
    vec2 face = vec2(abs(n.x) > abs(n.z) ? world.z : world.x, world.y);
    float streak = worldNoise(face, vec2(0.5, 0.0625)) * 0.7 + worldNoise(face + 7.0, vec2(2.0, 0.25)) * 0.3;
    return side * smoothstep(0.52, 0.72, streak);
}

// 点苔：峰顶、崖边朝上的面上零星的墨点
float moss(vec3 world, vec3 n) {
    return smoothstep(0.6, 0.9, n.y) * smoothstep(100.0, 130.0, world.y) * smoothstep(0.8, 0.88, worldNoise(world.xz, vec2(0.5)));
}

// ambient 为真时是整片群系的画风，带球形遮罩：近处保持原画，看得清脚下和身边的东西
vec3 inkWash(vec3 c, vec2 uv, vec2 size, bool hand, float level, bool ambient) {
    bool sky = !hand && texture(DepthSampler, uv).r >= 1.0;
    vec3 rel = hand ? vec3(0.0) : relativeAt(uv);
    float dist = hand ? 0.0 : (sky ? 1e5 : length(rel));
    float mask = ambient ? smoothstep(BIOME_MASK.x, BIOME_MASK.y, dist) : 1.0;
    // 遮罩以内什么都不用算
    if (mask <= 0.0) return c;
    float tone = smoothstep(0.0, PREVIEW, level) * mask;
    float full = smoothstep(PREVIEW, 1.0, level);
    vec3 paper = paperTone();
    float bright = brightness();
    // 墨和纸一起跟着天色暗下去，夜里墨也比纸深，不会反过来发亮
    vec3 ink = INK * min(bright, 1.0);
    vec2 px = size.y / 720.0 / size;

    if (sky) {
        // 天空留白成纸，往天顶带一点花青的淡染（跟着视线方向，转头时和天一起转）；太阳、月亮留一点
        vec3 wash = mix(paper, paper * vec3(0.9, 0.95, 1.01), smoothstep(0.05, 0.8, normalize(rel).y) * 0.6);
        vec3 col = wash + max(c - FogTint.rgb, 0.0) * 0.3;
        // 树冠里漏出来的天也染上墨，一团松针就是一团墨
        col = mix(col, ink, bleed(uv, px, bright, 3.5) * 0.8);
        return mix(c, clamp(col, 0.0, 1.0), (0.85 * full + 0.35 * (1.0 - full)) * tone);
    }

    vec3 world = Camera.xyz + rel;
    // 参考里的做法：三个平面的坐标加在一起，一张噪声就大致贴住了各个朝向的面
    vec2 tri = vec2(2.0 * world.x + world.y, world.y + 2.0 * world.z);
    vec3 n = hand ? vec3(0.0, 0.0, 1.0) : normalAt(uv, 1.0 / size, rel);
    vec3 base = hand ? c : faceWash(uv, size, rel, n, dist);
    // 树叶：原版叶子是镂空的贴图，勾线会在叶缝里画满小格子，所以不勾，整团落浓墨（墨团）
    float foliage = leafy(base, bright);
    float density = inkDensity(base, bright);
    density = mix(density, 0.6 + 0.35 * density, foliage);

    if (!hand) {
        float near = 1.0 - smoothstep(48.0, 140.0, dist);
        // 墨色不匀：一片墨里有浓有淡，贴在世界上
        density *= mix(0.82, 1.12, worldNoise(tri, vec2(0.0625)));
        // 边缘浓、中间淡：轮廓里侧一圈墨更重，往面里淡下去
        float rimWash = 0.5 * contour(uv, px, dist, 6.0) + 0.5 * contour(uv, px, dist, 12.0);
        density += rimWash * 0.3 * (1.0 - density) * (1.0 - foliage);
        // 皴擦落在中间调上，太亮的留白、太暗的已经是墨
        float middle = smoothstep(0.12, 0.4, density) * (1.0 - smoothstep(0.75, 0.95, density));
        density = mix(density, 0.85, cun(world, n) * middle * near * 0.6 * (1.0 - foliage));
        density = max(density, moss(world, n) * near * 0.85);
        density = max(density, bleed(uv, px, bright, mix(2.0, 5.0, worldNoise(tri, vec2(0.5)))) * 0.85);
        // 侧对镜头的面落墨（参考里的 inline）
        float rim = 1.0 - abs(dot(n, normalize(rel)));
        density = max(density, smoothstep(0.45, 0.55, (rim - 0.9) / 0.1 + worldNoise(tri, vec2(0.5)) * 0.5) * 0.8 * near);
        // 远山：越远墨越淡
        density *= 1.0 - smoothstep(80.0, Extra.w * 0.95, dist) * 0.45 * full;
    }
    density = clamp(density, 0.0, 1.0);

    // 纸上落墨，再留几成原来的颜色作淡彩，绿往石绿、花青偏；墨越浓颜色越少，远处没有颜色
    vec3 chroma = base - vec3(luma(base));
    chroma = vec3(chroma.r * 0.85, chroma.g, chroma.b + max(chroma.g, 0.0) * 0.45);
    vec3 col = mix(paper, ink, density) + chroma * COLOR_KEEP * min(bright, 1.0) * (1.0 - 0.6 * density) * (1.0 - smoothstep(40.0, 200.0, dist));

    if (!hand) {
        // 勾两道：细的湿笔断续、粗细随笔势变，粗的干笔更碎（飞白）；远处的线淡；树叶不勾
        float pressure = worldNoise(tri + 57.0, vec2(0.25));
        float wet = contour(uv, px, dist, mix(1.0, 2.2, pressure)) * smoothstep(0.3, 0.42, worldNoise(tri, vec2(0.25)));
        float dry = contour(uv, px, dist, 3.4) * smoothstep(0.55, 0.65, worldNoise(tri + 31.0, vec2(0.5)));
        vec3 lineInk = mix(ink, mix(ink, paper, 0.7), smoothstep(16.0, 160.0, dist));
        col = mix(col, lineInk, max(wet, dry * 0.7) * 0.85 * (1.0 - foliage));
        // 视距边上化进纸里，看不见区块的断口
        col = mix(col, paper, smoothstep(Extra.w * 0.75, Extra.w * 0.95, dist) * full);
        // 峰腰的云带
        if (full > 0.0) col = mix(col, paper * 1.02, mist(rel, dist) * full);
    }
    return mix(c, clamp(col, 0.0, 1.0), tone);
}

// 原画：镜头所在群系有画风时就是它
vec3 original(vec3 c, vec2 uv, vec2 size, bool hand) {
    int style = int(Extra.y + 0.5);
    if (style == 0 || Extra.z <= 0.0) return c;
    if (style == 1) return monochrome(c, uv, Extra.z);
    if (style == 3) return inkWash(c, uv, size, hand, Extra.z, true);
    return fairyTale(c, uv, size, hand, Extra.z);
}

vec3 applyStyle(int style, float level, vec3 c, vec2 uv, vec2 size, bool hand) {
    if (style == 0 || level <= 0.0) return original(c, uv, size, hand);
    if (style == 1) return monochrome(c, uv, level);
    if (style == 3) return inkWash(c, uv, size, hand, level, false);
    return fairyTale(c, uv, size, hand, level);
}

vec3 ringColor(int style) {
    return style == 2 ? vec3(0.4, 0.32, 0.47) : vec3(0.35);
}

void main() {
    vec2 uv = texCoord;
    vec2 size = vec2(textureSize(InSampler, 0));
    vec3 scene = texture(InSampler, uv).rgb;

    // 不开光影时手画在世界之后，用最后的深度认出来；手算在镜头的位置上，走进去之前不染，走进去之后一起变
    bool hand = texture(HandDepthSampler, uv).r < 1.0;
    vec3 rel = hand ? vec3(0.0) : relativeAt(uv);

    int insideStyle = int(State.x + 0.5);

    // 穿过门看到的：门里的人看到外面的原画，门外的人看到区域的预览
    for (int i = 0; i < int(Extra.x + 0.5); i++) {
        if (crossesDoor(i, rel)) {
            bool own = DoorRight[i].w > 0.5;
            fragColor = vec4(own ? original(scene, uv, size, hand) : applyStyle(int(DoorCenter[i].w + 0.5), PREVIEW, scene, uv, size, hand), 1.0);
            return;
        }
    }

    // 直接看到的：波内是现在的样子，波外还是进门前的样子
    vec3 inner = applyStyle(insideStyle, 1.0, scene, uv, size, hand);
    if (Wave.w < 0.0) {
        fragColor = vec4(inner, 1.0);
        return;
    }
    float reach = min(length(rel), WAVE_REACH);
    float dist = length(rel * (reach / max(length(rel), 1e-4)) - Wave.xyz);
    float feather = 0.6 + Wave.w * 0.06;
    float t = smoothstep(Wave.w, Wave.w - feather, dist);
    vec3 color = t >= 1.0 ? inner : mix(applyStyle(int(State.y + 0.5), State.z, scene, uv, size, hand), inner, t);
    // 波前一圈光
    float rim = t * (1.0 - t) * 4.0;
    fragColor = vec4(color + rim * ringColor(max(insideStyle, int(State.y + 0.5))), 1.0);
}
