#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>

// 刀光的一层（docs/WEAPON_TRAILS.md），照 StylizedSlashesV2 刀光材质（M_ComplexSlash_01）的做法：
//   形状 = 形状遮罩^MAIN_CONTRAST × MAIN_STRENGTH，遮罩横向是年龄（头在左），纵向从刀尖外缘到柄，被扭曲噪声推开；
//   颜色 = lerp(COLOR1, COLOR2, 颜色噪声)^COLOR_CONTRAST × COLOR_STRENGTH，噪声沿刀光往尾部流；形状最浓处偏向 HOT；
//   不透明 = 形状 × 不透明噪声 × 侵蚀 × 收窄 × 边缘淡出 × 顶点 alpha（整道刀光的淡出）；
//   侵蚀 = smoothstep(e − F, e + F, 侵蚀噪声^ER_CONTRAST × ER_STRENGTH)，e 随年龄从 ER_START 起变大，尾部先被吃成一条条；
//   收窄：越老的地方从柄那一侧往外缘收得越多，离刀越远越窄（几何上已裁掉，这里给软边）。
// 颜色按 1 − exp(−c) 压回 0–1，HDR 的强度过亮时变白而不是硬切成色块。
// 预乘混合：颜色按 alpha 加上去，背景按 alpha × OCCLUDE 压暗（0 纯发光，1 照常盖上去）。

uniform sampler2D Sampler0; // 形状遮罩（夹边）
uniform sampler2D Sampler1; // 侵蚀噪声（平铺）
uniform sampler2D Sampler2; // 颜色与不透明噪声（平铺）
uniform sampler2D Sampler3; // 扭曲噪声（平铺）

// 管线只能定义整数与浮点，颜色按分量传进来
#define COLOR1 vec3(C1_R, C1_G, C1_B)
#define COLOR2 vec3(C2_R, C2_G, C2_B)
#define HOT vec3(C3_R, C3_G, C3_B)

in vec2 trailUv;
in vec4 tint;
in float arc;
in float seed;
out vec4 fragColor;

void main() {
    float s = trailUv.x;
    float t = trailUv.y;
    float time = GameTime * 1200.0;
    float sd = seed * 13.0;

    // 扭曲：取样坐标被噪声推开，边缘不再是死板的弧线
    vec2 dn = texture(Sampler3, vec2(arc * DIST_TILE_U - time * DIST_PAN + sd, t * DIST_TILE_V)).rg - 0.5;
    vec2 d = dn * DISTORT;

    // 形状
    float mask = texture(Sampler0, clamp(vec2(s, 1.0 - t) + d * vec2(1.0, 0.6), vec2(0.001), vec2(0.999))).r;
    float shape = pow(mask, MAIN_CONTRAST) * MAIN_STRENGTH;

    // 不透明噪声（往尾部流）：OM_STRENGTH 为 0 时不用
    float om = 1.0;
    if (OM_STRENGTH > 0.0) {
        float o = texture(Sampler2, vec2(arc * OM_TILE_U - time * OM_PAN + sd * 0.5, t * OM_TILE_V) + d).r;
        om = clamp(pow(o, OM_CONTRAST) * OM_STRENGTH, 0.0, 1.0);
    }

    // 侵蚀：e 随年龄变大，顶点 alpha 小时（整道刀光在淡出）一起变大
    float en = pow(texture(Sampler1, vec2(arc * ER_TILE_U - time * ER_PAN + sd * 0.7, t * ER_TILE_V) + d).r, ER_CONTRAST) * ER_STRENGTH;
    float age = clamp((s - ER_START) / max(0.001, 1.0 - ER_START), 0.0, 1.0);
    float e = clamp(EROSION * pow(age, ER_POW) + (1.0 - tint.a), 0.0, 1.0);
    e = mix(-ER_FEATHER, 1.0 + ER_FEATHER, e);
    float erode = smoothstep(e - ER_FEATHER, e + ER_FEATHER, en);

    // 收窄的软边
    float edgeIn = NARROW * pow(s, NARROW_POW);
    float narrow = smoothstep(edgeIn, edgeIn + NARROW_SOFT, t);

    // 边缘淡出：柄侧、外缘、尾端
    float fade = smoothstep(0.0, FADE_IN + 0.0001, t) * (1.0 - smoothstep(1.0 - FADE_OUT - 0.0001, 1.0, t))
            * (1.0 - smoothstep(1.0 - FADE_TAIL - 0.0001, 1.0, s));

    float alpha = clamp(shape * om * erode * narrow * fade * OPACITY, 0.0, 1.0);

    // 外缘刃线：贴着刀尖外缘的一道细亮线，刚扫过时最亮
    float rim = RIM * exp(-pow((1.0 - t) * RIM_WIDTH, 2.0)) * (1.0 - s) * (1.0 - s) * erode * narrow;

    // 颜色
    float cn = texture(Sampler2, vec2(arc * COLOR_TILE_U - time * COLOR_PAN + sd * 0.3, t * COLOR_TILE_V) + d).r;
    vec3 col = pow(max(mix(COLOR1, COLOR2, cn), vec3(0.0)), vec3(COLOR_CONTRAST)) * COLOR_STRENGTH;
    col = mix(col, HOT * COLOR_STRENGTH, clamp((shape - HOT_FROM) * HOT_GAIN, 0.0, 1.0));
    col *= mix(1.0, shape, EMISSIVE_MASK);
    col += HOT * rim * 3.0;
    col = 1.0 - exp(-col * tint.rgb);

    alpha = clamp(max(alpha, rim * 0.8), 0.0, 1.0);
    if (alpha < 0.003) discard;
    fragColor = vec4(col * alpha, alpha * OCCLUDE) * ColorModulator;
}
