#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>

// 刀光的一层（docs/WEAPON_TRAILS.md）。形状遮罩 × 流动噪声，随年龄从尾部侵蚀散开，外缘一道热亮的刃线，
// 颜色按“热度”在三色之间取：最亮处 CORE，过渡 MID，将散处 EDGE。各层的参数由管线的 #define 写死。
// 预乘混合：颜色按 alpha 加上去，背景按 alpha × OCCLUDE 压暗。OCCLUDE 为 0 是纯发光（暗处好看，亮天空里会洗成白），
// 为 1 是照常盖上去（白天也看得出颜色）；一般的身子取中间，刃线取小值。
//
// Sampler0：形状遮罩（灰度，横向从刀光头到尾，纵向从刀尖外缘到柄）
// Sampler1：噪声（灰度，可平铺）
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;

// 管线只能定义整数与浮点，三色按分量传进来
#define CORE vec3(CORE_R, CORE_G, CORE_B)
#define MID vec3(MID_R, MID_G, MID_B)
#define EDGE vec3(EDGE_R, EDGE_G, EDGE_B)

in vec2 trailUv;
in vec4 tint;
in float arc;
in float seed;
out vec4 fragColor;

void main() {
    float s = trailUv.x;
    float t = trailUv.y;
    float time = GameTime * 1200.0;

    // 噪声沿刀光方向拉长、往尾部流动；两层不同速度相乘，纹理不重复
    vec2 nuv = vec2(arc * NOISE_SCALE - time * SCROLL, t * NOISE_STRETCH + seed * 7.0);
    float n1 = texture(Sampler1, nuv).r;
    float n2 = texture(Sampler1, nuv * vec2(0.61, 1.37) + vec2(time * SCROLL * 0.37, 0.5)).r;
    float n = mix(n1, n1 * n2 * 2.0, 0.5);

    // 遮罩被噪声扭开一点，边缘不再是死板的弧线
    vec2 muv = vec2(s, 1.0 - t) + (vec2(n1, n2) - 0.5) * DISTORT;
    float mask = texture(Sampler0, clamp(muv, vec2(0.001), vec2(0.999))).r;

    // 年龄侵蚀：越老越被噪声吃掉；顶点 alpha 是整道刀光的淡出
    float body = mask * mix(1.0, n * 1.8, DETAIL);
    float erode = s * s * EROSION + (1.0 - tint.a) * 1.2;
    float v = body - erode;
    float alpha = smoothstep(0.0, SOFT, v);

    // 外缘刃线：贴着刀尖外缘的一道细亮线，刚扫过时最亮
    float rimLine = exp(-pow((1.0 - t) * RIM_WIDTH, 2.0)) * (1.0 - s) * (1.0 - s);
    float rim = rimLine * RIM * (0.65 + 0.7 * n1);

    float heat = clamp(v * HEAT + rim, 0.0, 1.5);
    vec3 color = heat > 0.5 ? mix(MID, CORE, min(1.0, (heat - 0.5) * 2.0)) : mix(EDGE, MID, heat * 2.0);
    color *= tint.rgb * INTENSITY;
    alpha = clamp(max(alpha, rim * 0.8) * OPACITY, 0.0, 1.0);
    if (alpha < 0.004) discard;
    fragColor = vec4(color * alpha, alpha * OCCLUDE) * ColorModulator;
}
