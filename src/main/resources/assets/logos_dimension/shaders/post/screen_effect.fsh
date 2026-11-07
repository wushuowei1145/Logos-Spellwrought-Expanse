#version 330

// 全屏后期：黑白闪和电影蒙版。只读最终画面的颜色，不碰深度和光影包的缓冲，
// 所以不开光影、Sodium、Iris 走的都是同一条路。

uniform sampler2D InSampler;

layout(std140) uniform ScreenEffect {
    vec2 Center;   // 冲击点在画面上的位置
    float Mode;    // 0 原画，1 白底黑，2 黑底白
    float Seed;    // 每一拍换一次，线条跟着抖
    float Punch;   // 冲击缩放，0.05 就是放大到 105%
    float Split;   // 色差，按画面高度的比例
    float Grade;   // 电影蒙版的强度
};

in vec2 texCoord;
flat in float SceneLum;
out vec4 fragColor;

const float PI = 3.14159265;
const float TAU = 6.28318531;
// 一圈切成多少根细线：细到几个像素才像排线，粗了就成砖块
const float LINES = 1500.0;
const int RING = 12;

float luma(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

float hash(float x) {
    return fract(sin(x * 91.17 + Seed * 37.73) * 43758.5453);
}

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

// 1 是墨，0 是纸。足够暗的地方全黑；轮廓和半明半暗处只有一部分放射线落墨，就成了参考图里
// 沿受力方向的排线。每根线随机错开采样点，轮廓才是撕裂的尖刺而不是光滑的边。
float inkAmount(vec2 uv, vec2 size) {
    float aspect = size.x / size.y;
    vec2 d = (uv - Center) * vec2(aspect, 1.0);
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
    if (Mode < 1.5) return vec3(1.0 - ink);

    // 黑底白：负片之外再拉几根贯穿全屏、两头收尖的白色硬线
    float aspect = size.x / size.y;
    vec2 d = (uv - Center) * vec2(aspect, 1.0);
    float r = length(d);
    float x = (atan(d.y, d.x) + PI) / TAU * 260.0;
    float width = 0.5 * step(0.93, hash(floor(x) * 2.71)) * smoothstep(0.06, 0.3, r);
    float speed = step(abs(fract(x) - 0.5), width * (1.0 - smoothstep(0.3, 1.1, r)));
    return vec3(max(ink, speed));
}

vec3 grade(vec3 c, vec2 uv) {
    float l = luma(c);
    vec3 g = mix(vec3(l), c, 0.72);
    g = (g - 0.5) * 1.14 + 0.5;
    // 暗部偏冷，亮部偏暖
    g *= mix(vec3(0.95, 1.0, 1.07), vec3(1.05, 1.0, 0.92), smoothstep(0.1, 0.8, l));
    float v = smoothstep(0.32, 0.92, length((uv - 0.5) * vec2(1.25, 1.0)) * 1.25);
    g *= 1.0 - v * 0.55;
    return mix(c, clamp(g, 0.0, 1.0), Grade);
}

void main() {
    vec2 size = vec2(textureSize(InSampler, 0));
    vec2 uv = Center + (texCoord - Center) / (1.0 + Punch);

    vec3 color;
    if (Mode > 0.5) {
        color = impact(uv, size);
    } else if (Split > 0.0) {
        vec2 dir = uv - Center;
        float len = max(length(dir), 1e-4);
        vec2 o = dir / len * Split;
        color = vec3(texture(InSampler, uv + o).r, texture(InSampler, uv).g, texture(InSampler, uv - o).b);
    } else {
        color = texture(InSampler, uv).rgb;
    }

    fragColor = vec4(Mode > 0.5 ? color : grade(color, texCoord), 1.0);
}
