#version 330

// 选中声部的乐器模型（见 PartOutline）：位置相对镜头、世界坐标轴，按世界渲染同一个投影投到屏幕上，
// 和留下来的深度比较时才对得上。

layout(std140) uniform PartOutline {
    mat4 ViewProj;
    vec4 Color;
    vec4 Style;
};

in vec3 Position;
in vec2 UV0;

out vec2 texCoord0;

void main() {
    gl_Position = ViewProj * vec4(Position, 1.0);
    texCoord0 = UV0;
}
