#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// 刀光：UV0.x 是这一点的年龄（0 刚扫过，1 将散），UV0.y 是横跨刀身的位置（0 靠柄，1 刀尖外缘）；
// UV2.x 是沿刀尖走过的路程（1/64 格，循环），让噪声贴着刀光走而不是贴着屏幕；UV2.y 是这一道刀光的随机种子。
in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;

out vec2 trailUv;
out vec4 tint;
out float arc;
out float seed;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    trailUv = UV0;
    tint = Color;
    arc = float(UV2.x) / 64.0;
    seed = float(UV2.y) / 32767.0;
}
