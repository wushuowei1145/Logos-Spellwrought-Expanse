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
    vec4 Extra;                       // x 门数
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

vec3 applyStyle(int style, float level, vec3 c, vec2 uv, vec2 size, bool hand) {
    if (style == 0 || level <= 0.0) return c;
    if (style == 1) return monochrome(c, uv, level);
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
            fragColor = vec4(own ? scene : applyStyle(int(DoorCenter[i].w + 0.5), PREVIEW, scene, uv, size, hand), 1.0);
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
