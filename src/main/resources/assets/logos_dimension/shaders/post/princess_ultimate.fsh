#version 330

// 精灵公主奥义 Cut 1「种子破土」的压黑（docs/ELF_PRINCESS_COMPANION.md 第 11 节，PrincessUltimateScreen）：
// 四周彻底压黑，只在新苗那一点留一圈；圈里不带颜色的地面也压暗，发光的新苗、地缝（有颜色、够亮的像素）照常亮着。
// 圈心跟着新苗在画面上的位置走（由世界坐标投过来），不是贴在屏幕上的图形；没有纹理和噪声。

uniform sampler2D InSampler;

layout(std140) uniform PrincessUltimate {
    vec4 Screen;   // 宽、高（像素），圈心 xy（画面 0–1，左下是 0）
    vec4 Spot;     // 圈的半径、边缘多软（画面高度的几成），圈外压暗几成，圈里压暗几成
    vec4 Weight;   // 整体强度（0 不压，1 全压）、发光的像素留几成、-、-
};

in vec2 texCoord;
out vec4 fragColor;

const vec3 WISH_LUMA = vec3(0.2126, 0.7152, 0.0722);

void main() {
    vec3 c = texture(InSampler, texCoord).rgb;
    vec2 d = (texCoord - Screen.zw) * vec2(Screen.x / Screen.y, 1.0);
    float spot = 1.0 - smoothstep(Spot.x, Spot.x + Spot.y, length(d));
    // 发光的新苗：有颜色（薄荷、暖金）而且够亮
    float hi = max(c.r, max(c.g, c.b)), lo = min(c.r, min(c.g, c.b));
    float lit = smoothstep(0.06, 0.2, hi - lo) * smoothstep(0.3, 0.65, dot(c, WISH_LUMA));
    float dark = mix(Spot.z, Spot.w * (1.0 - lit * Weight.y), spot);
    fragColor = vec4(c * (1.0 - dark * Weight.x), 1.0);
}
