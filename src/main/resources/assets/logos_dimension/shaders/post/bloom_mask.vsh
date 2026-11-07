#version 330

// 发光的几何，已经在 CPU 上投好了（见 GlowBloom）。

in vec3 Position;
in vec4 Color;

out vec4 vertexColor;
out vec2 screen;

void main() {
    gl_Position = vec4(Position, 1.0);
    vertexColor = Color;
    screen = Position.xy * 0.5 + 0.5;
}
