#version 330

// 后期里画的特效几何（见 PostVfx）：位置相对镜头、世界坐标轴，按世界渲染同一个投影投到屏幕上，
// 和留下来的深度比较时才对得上。

layout(std140) uniform PostVfx {
    mat4 ViewProj;
    vec4 ScreenSize;
};

in vec3 Position;
in vec2 UV0;
in vec4 Color;

out vec2 texCoord0;
out vec4 vertexColor;

void main() {
    gl_Position = ViewProj * vec4(Position, 1.0);
    texCoord0 = UV0;
    vertexColor = Color;
}
