#version 330

// 风里的草尖：顶点阶段给草叶加一个随阵风摆动的位移。
// 世界坐标通过 UV2（这里是整数的世界 x/z）传进来，所以波浪能"扫过"整片草原，
// 而不是跟着镜头走；时间来自 GameTime，CPU 每帧只提交几何，不做顶点运算。
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:globals.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;

out vec4 vertexColor;
out vec2 texCoord0;

void main() {
    vec2 world = vec2(UV2);          // 世界 xz（整数格）
    float t = GameTime;

    // 阵风：两层正弦叠出"时大时小"，与 WindField 的节奏接近（约 150s 慢层 + 11s 快层）。
    float gust = 0.55 + 0.45 * sin(t * 0.021) + 0.18 * sin(t * 0.573 + 2.1);
    gust = clamp(gust, 0.05, 1.2);

    // 草浪：沿风向的相位差让波峰从一侧扫到另一侧。
    float phase = dot(world, vec2(0.62, 0.78)) - t * 2.35;
    float wave = sin(phase);

    // Color.a 存的是"离地高度比例"，越靠叶尖摆得越多。
    float h = clamp(Color.a, 0.0, 1.0);
    float sway = h * h * (gust * 0.42 + 0.24 * wave * gust);

    vec3 pos = Position;
    pos.x += sway * 0.78;
    pos.z += sway * 0.62;
    pos.y -= abs(sway) * 0.16;       // 摆出去的时候压低一点，像被风压住

    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
    vertexColor = Color;
    texCoord0 = UV0;
}
