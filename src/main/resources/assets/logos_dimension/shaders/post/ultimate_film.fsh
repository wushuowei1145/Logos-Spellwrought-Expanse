#version 330

// 奥义过场盖在画面上的那一层（docs/ULTIMATES.md「过场」，UltimateFilmRenderer）：动态模糊 → 调色（压暗、去饱和、染色、暗角）→ 速度线 → 擦镜。
// 速度线和擦镜是画在屏幕上的 2D 图形：漫画的速度线本来就贴着画面，擦镜十几刻就扫过去，不会出现「世界在转、纹理不动」的晕。
// 不写字（拟声字也不写）。

uniform sampler2D InSampler;

layout(std140) uniform UltimateFilm {
    vec4 Screen;      // 宽、高（像素），第几刻，种子
    vec4 Grade;       // 压暗、去饱和、染色几成、暗角
    vec4 Tint;        // 染成什么颜色
    vec4 Blur;        // 强度、方式（0 没有，1 沿方向，2 从焦点往外）、方向（弧度，0 向右、正的往下）、-
    vec4 Lines;       // 强度、方式（0 没有，1 平行，2 往外喷，3 往里吸）、方向（弧度）、-
    vec4 LineColor;   // 线的颜色
    vec4 Focus;       // 径向模糊的焦点 xy，速度线的焦点 zw（画面 0–1，左下是 0）
    vec4 Wipe;        // 进度（小于 0 没有），什么东西（UltimateFilm.Wipe 的序号）
    vec4 WipeColor;
    vec4 WipeColor2;
};

in vec2 texCoord;
out vec4 fragColor;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
const float TAU = 6.28318530718;

float filmHash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21) + Screen.w * 0.0137);
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float filmHash1(float n) {
    return fract(sin(n * 127.1 + Screen.w * 0.013) * 43758.5453);
}

mat2 filmRot(float a) {
    float c = cos(a), s = sin(a);
    return mat2(c, -s, s, c);
}

// ---------------------------------------------------------------- 动态模糊

vec3 filmBlur(vec2 uv) {
    vec3 c = texture(InSampler, uv).rgb;
    float s = Blur.x;
    if (s <= 0.001 || Blur.y < 0.5) return c;
    // 每个像素错开一点起点，免得采样数少时出一道道的台阶
    float jitter = filmHash(uv * Screen.xy) / 6.0;
    bool directional = Blur.y < 1.5;
    vec2 span = directional ? vec2(cos(Blur.z), -sin(Blur.z)) * s * 0.035 : (uv - Focus.xy) * s * 0.14;
    vec3 acc = c;
    float total = 1.0;
    for (int i = 1; i <= 6; i++) {
        float k = float(i) / 6.0 - jitter;
        float w = 1.0 - k * 0.5;
        if (directional) {
            acc += (texture(InSampler, uv + span * k).rgb + texture(InSampler, uv - span * k).rgb) * w;
            total += 2.0 * w;
        } else {
            acc += texture(InSampler, uv - span * k).rgb * w;
            total += w;
        }
    }
    return acc / total;
}

// ---------------------------------------------------------------- 调色

vec3 filmGrade(vec3 c, vec2 uv) {
    float l = dot(c, LUMA);
    c = mix(c, vec3(l), Grade.y);
    // 染色：按亮度上色（暗处还是暗的），留一点原来的颜色
    c = mix(c, l * Tint.rgb * 1.35 + c * 0.15, Grade.z);
    vec2 d = (uv - 0.5) * vec2(Screen.x / Screen.y, 1.0);
    c *= 1.0 - Grade.w * smoothstep(0.35, 1.05, length(d));
    return c * (1.0 - Grade.x);
}

// ---------------------------------------------------------------- 速度线

float filmLines(vec2 uv) {
    if (Lines.x <= 0.001 || Lines.y < 0.5) return 0.0;
    float aspect = Screen.x / Screen.y;
    vec2 p = (uv - Focus.zw) * vec2(aspect, 1.0);
    float t = Screen.z;
    // 中间留一块不盖住主体
    float clear = smoothstep(0.2, 0.48, length(p));
    if (Lines.y < 1.5) {
        // 平行的一排：沿方向拉长的细条，一段段往前流
        vec2 dir = vec2(cos(Lines.z), -sin(Lines.z));
        vec2 nrm = vec2(-dir.y, dir.x);
        float across = dot(p, nrm) * 55.0;
        float id = floor(across);
        float r = filmHash1(id);
        if (r > 0.5) return 0.0;
        float along = dot(p, dir) * (2.0 + r * 3.0) - t * (0.6 + r * 0.6) + r * 10.0;
        float seg = fract(along);
        float len = 0.3 + 0.45 * filmHash1(id + 7.0);
        float body = smoothstep(0.0, 0.05, seg) * (1.0 - smoothstep(len - 0.1, len, seg));
        float width = 0.12 + 0.3 * filmHash1(id + 3.0);
        float thin = 1.0 - smoothstep(width * 0.5, width * 0.5 + 0.1, abs(fract(across) - 0.5));
        return body * thin * clear * Lines.x;
    }
    // 从焦点往外喷、往里吸的尖刺：越往里越细，每十分之一秒换一批（漫画的抖动感）
    float rad = length(p);
    float a = atan(p.y, p.x) / TAU * 96.0;
    float frame = floor(t * 0.5);
    float id = floor(a);
    float r = filmHash1(id + frame * 13.0);
    if (r > 0.55) return 0.0;
    float inner = 0.22 + 0.28 * filmHash1(id + 5.0 + frame * 7.0);
    float width = 0.42 * smoothstep(inner, 1.3, rad);
    float thin = 1.0 - smoothstep(width * 0.5, width * 0.5 + 0.06, abs(fract(a) - 0.5));
    float inward = Lines.y < 2.5 ? 1.0 : -1.0;
    float flow = fract(rad * (1.4 + r) - inward * t * (0.3 + r * 0.3) + r * 5.0);
    float pulse = smoothstep(0.0, 0.15, flow) * (1.0 - smoothstep(0.55, 0.95, flow));
    return thin * smoothstep(inner, inner + 0.12, rad) * mix(1.0, pulse, 0.5) * Lines.x;
}

// ---------------------------------------------------------------- 擦镜

// 一片东西盖住多少（q 是这一片自己的坐标，大约 -1..1）
float filmShape(int kind, vec2 q) {
    if (kind == 0) {
        // 花瓣：椭圆，尖上一个小缺口
        float d = length(q / vec2(0.62, 0.36)) - 1.0;
        d = max(d, 0.35 - length(q - vec2(0.68, 0.0)) * 2.4);
        return 1.0 - smoothstep(-0.08, 0.08, d);
    }
    if (kind == 1) {
        // 叶子：两段圆弧夹出来的尖叶，中间一道叶脉
        float d = max(length(q - vec2(0.0, -0.55)), length(q - vec2(0.0, 0.55))) - 0.95;
        float leaf = 1.0 - smoothstep(-0.05, 0.05, d * 1.5);
        return leaf * (1.0 - 0.35 * (1.0 - smoothstep(0.0, 0.05, abs(q.y))) * step(abs(q.x), 0.7));
    }
    if (kind == 2) {
        // 纸片（符纸、形代）：竖着的长方形
        vec2 d = abs(q) - vec2(0.38, 0.62);
        return 1.0 - smoothstep(-0.04, 0.04, max(d.x, d.y));
    }
    if (kind == 3) {
        // 碎晶：歪的菱形
        float d = abs(q.x + q.y * 0.25) / 0.45 + abs(q.y) / 0.8 - 1.0;
        return 1.0 - smoothstep(-0.06, 0.06, d);
    }
    if (kind == 4) {
        // 烟：软的一团
        return 1.0 - smoothstep(0.25, 0.95, length(q * vec2(0.8, 1.0)));
    }
    if (kind == 5) {
        // 火星：一个亮点拖一道尾巴
        float d = length(q / vec2(0.85, 0.09));
        return 1.0 - smoothstep(0.6, 1.0, d);
    }
    // 星芒：四角星
    vec2 a = abs(q);
    float d = pow(a.x, 0.55) + pow(a.y, 0.55) - 0.9;
    return 1.0 - smoothstep(-0.06, 0.06, d);
}

// 一大片东西从左往右扫过：返回颜色和盖住多少。带子正中扫到画面中间时整个画面都盖满，镜头在这时回到玩家视角
vec4 filmWipe(vec2 uv) {
    float prog = Wipe.x;
    if (prog < 0.0) return vec4(0.0);
    int kind = int(Wipe.y + 0.5);
    float aspect = Screen.x / Screen.y;
    vec2 p = vec2(uv.x * aspect, uv.y);
    float center = mix(-0.95, 1.95, prog) * aspect;
    float x = p.x - center - (uv.y - 0.5) * 0.45;
    float near = 1.0 - smoothstep(0.42 * aspect, 0.85 * aspect, abs(x));
    float core = 1.0 - smoothstep(0.38 * aspect, 0.58 * aspect, abs(x));
    // 带子中间一层底：两个颜色之间慢慢变，烟更不透
    float n = filmHash(floor(p * 9.0)) * 0.5 + filmHash(floor(p * 23.0)) * 0.5;
    vec3 curtain = mix(WipeColor.rgb, WipeColor2.rgb, clamp(n * 0.8 + uv.y * 0.3, 0.0, 1.0));
    float curtainA = core * (kind == 4 ? 0.96 : 0.88);
    // 一片片的东西：三层，近的大、走得快
    vec3 piece = vec3(0.0);
    float pieceA = 0.0;
    for (int layer = 0; layer < 3; layer++) {
        float scale = 3.2 + float(layer) * 2.4;
        vec2 q = p * scale + vec2(-center * scale * (0.25 + 0.15 * float(layer)), float(layer) * 7.3);
        vec2 cell = floor(q);
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                vec2 c = cell + vec2(i, j);
                float r = filmHash(c + float(layer) * 31.7);
                if (r > mix(0.12, 0.92, near)) continue;
                vec2 pos = c + 0.5 + (vec2(filmHash(c + 3.1), filmHash(c + 9.7)) - 0.5) * 0.7;
                float size = 0.45 + 0.4 * filmHash(c + 5.3);
                float spin = filmHash(c + 2.2) * TAU + Screen.z * (filmHash(c + 4.4) - 0.5) * 0.6;
                vec2 local = filmRot(spin) * (q - pos) / size;
                float cover = filmShape(kind, local);
                if (cover <= pieceA) continue;
                float shade = 0.75 + 0.35 * (1.0 - length(local) * 0.6);
                vec3 tone = mix(WipeColor.rgb, WipeColor2.rgb, filmHash(c + 8.8));
                // 火星、星芒是发光的：芯更亮
                if (kind >= 5) tone = mix(tone, vec3(1.0), 0.45 * (1.0 - length(local)));
                piece = tone * shade;
                pieceA = cover;
            }
        }
    }
    float a = max(curtainA, pieceA);
    vec3 col = mix(curtain, piece, pieceA);
    return vec4(col, a);
}

void main() {
    vec2 uv = texCoord;
    vec3 c = filmBlur(uv);
    c = filmGrade(c, uv);
    c = mix(c, LineColor.rgb, clamp(filmLines(uv), 0.0, 1.0));
    vec4 w = filmWipe(uv);
    c = mix(c, w.rgb, clamp(w.a, 0.0, 1.0));
    fragColor = vec4(c, 1.0);
}
