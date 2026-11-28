#version 330

// 玩家最后一击「星穹誓约 · 羁绊焚天炮」自己的全屏后期（HeroFinisherScreen，docs/ULTIMATES.md 第六节）：
// 开演压黑 → Cut 1 全黑里一朵暖金白芯的火苗和几点浮起的火星 → Cut 2 曝光慢慢抬起来，带一点暮色的深蓝。
// 火苗钉在掌心投到画面上的那一点，跟着它走；不往画面上写字。

uniform sampler2D InSampler;

layout(std140) uniform HeroFinisher {
    vec4 Screen;    // 宽、高（像素），第几刻，种子
    vec4 Expose;    // 曝光（0 全黑、1 原样）、暮色几成、火苗多亮、火苗多大（占画面高度）
    vec4 Flame;     // 火苗在画面上的位置 xy（0–1，左下是 0）
    vec4 FlameColor;
};

in vec2 texCoord;
out vec4 fragColor;

float heroHash(float n) {
    return fract(sin(n * 91.345 + Screen.w * 0.017) * 43758.5453);
}

// 一朵火苗：极小的白芯、暖金的光晕、横竖两道长芒加两道短的斜芒，微微跳动
vec3 heroFlame(vec2 uv) {
    float aspect = Screen.x / Screen.y;
    float size = Expose.w;
    vec2 d = (uv - Flame.xy) * vec2(aspect, 1.0) / size;
    float r = length(d);
    float flicker = 0.9 + 0.1 * sin(Screen.z * 1.7) * sin(Screen.z * 0.63 + 1.3);
    float core = exp(-r * r * 160.0);
    float halo = exp(-r * 5.5) * 0.55 + exp(-r * 1.6) * 0.12;
    float rays = exp(-abs(d.y) * 90.0) * exp(-abs(d.x) * 2.2) + exp(-abs(d.x) * 90.0) * exp(-abs(d.y) * 3.0);
    vec2 e = vec2(d.x + d.y, d.x - d.y) * 0.7071;
    rays += 0.35 * (exp(-abs(e.y) * 120.0) * exp(-abs(e.x) * 6.0) + exp(-abs(e.x) * 120.0) * exp(-abs(e.y) * 6.0));
    // 火苗本身往上拉长一点
    vec2 f = d * vec2(1.0, 0.55) - vec2(0.0, 0.03);
    float tongue = exp(-dot(f, f) * 70.0);
    vec3 gold = FlameColor.rgb;
    vec3 c = vec3(1.0) * core * 1.4 + mix(gold, vec3(1.0), 0.6) * tongue * 0.9 + gold * (halo + rays * 0.8);
    // 几点火星从火苗旁慢慢浮起，越高越淡
    for (int i = 0; i < 7; i++) {
        float fi = float(i);
        float rise = fract(Screen.z * (0.018 + 0.006 * heroHash(fi)) + heroHash(fi + 3.1));
        vec2 at = vec2((heroHash(fi + 7.7) - 0.5) * 0.9 + sin(Screen.z * 0.05 + fi) * 0.08, 0.15 + rise * 2.2);
        float s = exp(-dot(d - at, d - at) * 900.0) * (1.0 - rise) * smoothstep(0.0, 0.1, rise);
        c += mix(gold, vec3(1.0), 0.4) * s * 1.2;
    }
    return c * flicker;
}

void main() {
    vec3 c = texture(InSampler, texCoord).rgb;
    // 暮色：火光照亮的是深蓝的天
    vec3 dusk = c * vec3(0.78, 0.86, 1.12);
    c = mix(c, dusk, Expose.y);
    c *= Expose.x;
    if (Expose.z > 0.001) c += heroFlame(texCoord) * Expose.z;
    fragColor = vec4(c, 1.0);
}
