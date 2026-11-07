#version 330

// 全屏后期：黑白闪和电影蒙版。只读最终画面的颜色、半透明之前的深度和手的深度，
// 不碰光影包的缓冲，所以不开光影、Sodium、Iris 走的都是同一条路。

uniform sampler2D InSampler;
uniform sampler2D DepthSampler;       // 半透明之前的深度
uniform sampler2D HandDepthSampler;   // 这一帧最后的深度：不开光影时只有手

layout(std140) uniform ScreenEffect {
    mat4 InvViewProj;   // 从裁剪空间还原到相对镜头的坐标
    vec4 Light;         // xyz 光源（相对镜头），w 光源的深度，和深度缓冲同一种值
    vec4 Frame;         // x 这一拍是什么，y 线条的随机种子，z 发光的强度，w 抽象程度
    vec4 Look;          // x 冲击缩放，y 色差，z 电影蒙版，w 光吞没的半径（按画面高度）
    vec4 Screen;        // xy 光源在画面上的位置，z 深度是否 0 到 1
};

in vec2 texCoord;
flat in float SceneLum;
out vec4 fragColor;

const float PI = 3.14159265;
const float TAU = 6.28318531;
// 一圈切成多少根细线：细到几个像素才像排线，粗了就成砖块
const float LINES = 1500.0;
const int RING = 12;
// 影子线往光源方向找几步
const int STEPS = 10;

// 这一拍是什么：和 ScreenFlash.Frame 对应
const float WHITE = 1.0, BLACK = 2.0, GLOW = 3.0, SILHOUETTE = 4.0, ENGULF = 5.0, SWAP = 6.0;

float luma(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

float hash(float x) {
    return fract(sin(x * 91.17 + Frame.y * 37.73) * 43758.5453);
}

bool isMode(float mode) {
    return abs(Frame.x - mode) < 0.5;
}

// ---- 旧的两拍：白底黑、黑底白，只看颜色的明暗 ----

// 取一圈样本：x 是平均明度，yz 是明度的梯度。方块贴图的像素一过阈值就是满屏麻点，
// 圈要够大才能只留下大块的明暗；同一圈样本顺便算出轮廓，不用多采样。
vec3 ringLuma(vec2 uv, vec2 texel) {
    float sum = luma(texture(InSampler, uv).rgb);
    vec2 gradient = vec2(0.0);
    for (int i = 0; i < RING; i++) {
        float a = float(i) * TAU / float(RING);
        vec2 dir = vec2(cos(a), sin(a));
        float l = luma(texture(InSampler, uv + dir * texel).rgb);
        sum += l;
        gradient += dir * l;
    }
    return vec3(sum / float(RING + 1), gradient / float(RING) * 2.0);
}

// 1 是墨，0 是纸。足够暗的地方全黑；轮廓和半明半暗处只有一部分放射线落墨，就成了
// 沿受力方向的排线。每根线随机错开采样点，轮廓才是撕裂的尖刺而不是光滑的边。
float inkAmount(vec2 uv, vec2 size) {
    float aspect = size.x / size.y;
    vec2 d = (uv - Screen.xy) * vec2(aspect, 1.0);
    float r = max(length(d), 1e-4);
    float a = (atan(d.y, d.x) + PI) / TAU;
    float line = floor(a * LINES);
    float h = hash(line);
    float sector = hash(floor(a * 90.0) * 7.3);

    vec2 dir = d / r / vec2(aspect, 1.0);
    vec2 sampleUv = uv + dir * ((h - 0.5) * (0.012 + r * 0.075) + (sector - 0.5) * 0.03);
    vec3 ring = ringLuma(sampleUv, size.y / 720.0 * 11.0 / size);

    // 分界压到平均明度以下，大部分画面留白，只有真正暗的东西变成墨
    float threshold = clamp(SceneLum * 0.62, 0.05, 0.5);
    float dark = smoothstep(threshold * 1.15, threshold * 0.8, ring.x);
    float edge = smoothstep(0.035, 0.1, length(ring.yz));

    float stroke = hash(line * 1.37 + floor(r * 7.0 + h * 11.0) * 13.1);
    float ink = step(stroke, max(dark, edge * 0.85));

    // 冲击点的四角星和尖刺，每根尖刺都收成尖的
    float spokes = a * 72.0;
    float taper = 1.0 - abs(fract(spokes) * 2.0 - 1.0);
    float spike = 0.03 + 0.2 * pow(hash(floor(spokes) * 3.1), 6.0) * taper * taper;
    float axis = pow(abs(cos(2.0 * a * TAU)), 420.0) * 0.42;
    if (r < spike + axis) ink = 1.0;
    return ink;
}

vec3 impact(vec2 uv, vec2 size) {
    float ink = inkAmount(uv, size);
    if (isMode(WHITE)) return vec3(1.0 - ink);

    // 黑底白：负片之外再拉几根贯穿全屏、两头收尖的白色硬线
    float aspect = size.x / size.y;
    vec2 d = (uv - Screen.xy) * vec2(aspect, 1.0);
    float r = length(d);
    float x = (atan(d.y, d.x) + PI) / TAU * 260.0;
    float width = 0.5 * step(0.93, hash(floor(x) * 2.71)) * smoothstep(0.06, 0.3, r);
    float speed = step(abs(fract(x) - 0.5), width * (1.0 - smoothstep(0.3, 1.1, r)));
    return vec3(max(ink, speed));
}

// ---- 四个阶段：发光、剪影、吞没、翻转 ----

// 手在最前面：不开光影时手在最后的深度里，算成离镜头最近
float depthAt(vec2 uv) {
    if (texture(HandDepthSampler, uv).r < 1.0) return 0.0;
    return texture(DepthSampler, uv).r;
}

vec3 positionAt(vec2 uv, float d) {
    float z = Screen.z > 0.5 ? d : d * 2.0 - 1.0;
    vec4 h = InvViewProj * vec4(uv * 2.0 - 1.0, z, 1.0);
    return h.xyz / h.w;
}

// 背光面：比光源近，而且表面背对光源。只看远近的话，光源前面的整片地面都会变黑；
// 朝向用左右、上下两侧里离得近的那一侧算，跨过轮廓时不会算歪
bool backlit(vec2 uv, vec2 px) {
    float d = depthAt(uv);
    if (d >= 1.0 || d >= Light.w) return false;
    if (d <= 0.0) return true;
    vec3 p = positionAt(uv, d);
    vec3 right = positionAt(uv + vec2(px.x, 0.0), depthAt(uv + vec2(px.x, 0.0))) - p;
    vec3 left = p - positionAt(uv - vec2(px.x, 0.0), depthAt(uv - vec2(px.x, 0.0)));
    vec3 up = positionAt(uv + vec2(0.0, px.y), depthAt(uv + vec2(0.0, px.y))) - p;
    vec3 down = p - positionAt(uv - vec2(0.0, px.y), depthAt(uv - vec2(0.0, px.y)));
    vec3 n = normalize(cross(dot(right, right) < dot(left, left) ? right : left, dot(up, up) < dot(down, down) ? up : down));
    if (dot(n, p) > 0.0) n = -n;
    // 只有真正背对光源的面才算：光源前面的地面朝上、迎着光，是亮的，上面才落得下影子线
    return dot(n, normalize(Light.xyz - p)) < -0.05;
}

// 深度突变的地方就是物体的轮廓：透视下平面的深度在屏幕上是线性的，二阶差分为零，只有轮廓和转折有值
bool contour(vec2 uv, vec2 px) {
    float c = depthAt(uv);
    float dx = abs(depthAt(uv + vec2(px.x, 0.0)) + depthAt(uv - vec2(px.x, 0.0)) - 2.0 * c);
    float dy = abs(depthAt(uv + vec2(0.0, px.y)) + depthAt(uv - vec2(0.0, px.y)) - 2.0 * c);
    return max(dx, dy) / max(1.0 - c, 1e-5) > 0.06;
}

// 光吞没的范围：光源周围一团，边缘收成一根根尖刺，再加上下左右四道长芒
bool engulfed(float r, float a, float radius, float abstraction) {
    if (radius <= 0.0) return false;
    float spokes = a * 72.0;
    float taper = 1.0 - abs(fract(spokes) * 2.0 - 1.0);
    float spike = (0.04 + 0.22 * abstraction) * pow(hash(floor(spokes) * 3.1), 6.0) * taper * taper;
    float axis = pow(abs(cos(2.0 * a * TAU)), 420.0) * (0.3 + 0.35 * abstraction);
    return r < radius + spike + axis;
}

// 1 是墨，0 是纸。背光面是黑色剪影；剪影背离光源的一侧拉出影子线，轮廓也拉成尖刺，
// 所有线都指向光源。越往后线越长、采样点错得越开，形状越来越抽象
float stageInk(vec2 uv, vec2 size) {
    vec2 px = 1.0 / size;
    float aspect = size.x / size.y;
    vec2 d = (uv - Screen.xy) * vec2(aspect, 1.0);
    float r = max(length(d), 1e-4);
    float a = (atan(d.y, d.x) + PI) / TAU;
    float line = floor(a * LINES);
    float h = hash(line), h2 = hash(line * 1.37 + 3.1);
    float abstraction = Frame.w;
    vec2 toLight = -d / r / vec2(aspect, 1.0);

    if (engulfed(r, a, Look.w, abstraction)) return 0.0;

    vec2 base = uv + toLight * (h - 0.5) * (0.008 + abstraction * 0.05);
    bool body = backlit(base, px);
    float ink = body ? 1.0 : 0.0;

    if (body) {
        // 朝光的一侧留一道白边，身体里零星几道顺着光的长白缝
        if (!backlit(base + toLight * px.y * (3.0 + abstraction * 10.0), px)) ink = 0.0;
        if (hash(line * 5.1 + floor(r * 6.0 + h * 3.0)) > 0.985 - abstraction * 0.04) ink = 0.0;
    } else {
        // 影子线：朝光源方向找一段，碰到背光面或轮廓，这里就落在它背离光源拉出的线里
        float reach = (0.015 + 0.2 * h2 * h2) * (0.4 + abstraction * 1.4);
        for (int i = 1; i <= STEPS; i++) {
            vec2 q = base + toLight * reach * float(i) / float(STEPS);
            if (backlit(q, px) || contour(q, px)) {
                ink = 1.0;
                break;
            }
        }
        // 线是断续的
        ink *= step(0.22 - abstraction * 0.12, hash(line * 2.3 + floor(r * 18.0 + h * 7.0)));
        if (contour(base, px)) ink = 1.0;
        // 画面本来就暗的地方（影子、洞口）：按暗的程度落一部分放射线，成了排线。分界跟着平均明度走，夜里也成立
        float shade = ringLuma(base, size.y / 720.0 * 7.0 / size).x;
        float threshold = clamp(SceneLum * 0.55, 0.04, 0.45);
        float dark = smoothstep(threshold, threshold * 0.5, shade);
        if (hash(line * 1.37 + floor(r * 7.0 + h * 11.0) * 13.1) < dark) ink = 1.0;
        // 光源以外零星几根放射细线
        float speed = step(0.988 - abstraction * 0.025, hash(floor(a * LINES * 0.5) * 0.37));
        ink = max(ink, speed * smoothstep(Look.w, Look.w + 0.12, r));
    }
    return ink;
}

// 还是彩色：画面整体变亮，光源一团白光加放射的光芒，靠近光源的东西被吞没
vec3 glow(vec2 uv, vec2 size) {
    vec3 c = texture(InSampler, uv).rgb;
    float s = Frame.z;
    float aspect = size.x / size.y;
    vec2 d = (uv - Screen.xy) * vec2(aspect, 1.0);
    float r = length(d);
    float a = (atan(d.y, d.x) + PI) / TAU;
    vec3 lifted = c * (1.0 + 0.7 * s) + 0.1 * s;
    float bloom = exp(-r * r / (0.01 + 0.05 * s)) * s;
    float rays = pow(hash(floor(a * 160.0) * 1.7), 10.0) * smoothstep(0.9, 0.0, r) * s;
    float core = engulfed(r, a, Look.w, 0.0) ? 1.0 : 0.0;
    return mix(lifted, vec3(1.0), clamp(core + bloom * 1.2 + rays, 0.0, 1.0));
}

vec3 grade(vec3 c, vec2 uv) {
    float l = luma(c);
    vec3 g = mix(vec3(l), c, 0.72);
    g = (g - 0.5) * 1.14 + 0.5;
    // 暗部偏冷，亮部偏暖
    g *= mix(vec3(0.95, 1.0, 1.07), vec3(1.05, 1.0, 0.92), smoothstep(0.1, 0.8, l));
    float v = smoothstep(0.32, 0.92, length((uv - 0.5) * vec2(1.25, 1.0)) * 1.25);
    g *= 1.0 - v * 0.55;
    return mix(c, clamp(g, 0.0, 1.0), Look.z);
}

void main() {
    vec2 size = vec2(textureSize(InSampler, 0));
    vec2 uv = Screen.xy + (texCoord - Screen.xy) / (1.0 + Look.x);

    vec3 color;
    if (isMode(WHITE) || isMode(BLACK)) {
        color = impact(uv, size);
    } else if (isMode(GLOW)) {
        color = glow(uv, size);
    } else if (isMode(SILHOUETTE) || isMode(ENGULF)) {
        color = vec3(1.0 - stageInk(uv, size));
    } else if (isMode(SWAP)) {
        // 翻转：光吞没的范围更大，上一拍黑的被吞成白的；光以外整个反过来，上一拍亮的暗下去
        float aspect = size.x / size.y;
        vec2 d = (uv - Screen.xy) * vec2(aspect, 1.0);
        bool lit = engulfed(length(d), (atan(d.y, d.x) + PI) / TAU, Look.w, Frame.w);
        color = vec3(lit ? 1.0 : stageInk(uv, size));
    } else if (Look.y > 0.0) {
        vec2 dir = uv - Screen.xy;
        float len = max(length(dir), 1e-4);
        vec2 o = dir / len * Look.y;
        color = vec3(texture(InSampler, uv + o).r, texture(InSampler, uv).g, texture(InSampler, uv - o).b);
    } else {
        color = texture(InSampler, uv).rgb;
    }

    fragColor = vec4(Frame.x > 0.5 ? color : grade(color, texCoord), 1.0);
}
