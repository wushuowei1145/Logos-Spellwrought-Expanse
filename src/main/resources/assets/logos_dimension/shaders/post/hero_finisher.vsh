#version 330

out vec2 texCoord;

// 全屏三角形（draw(0, 3)），texCoord 从左下角 (0, 0) 到右上角 (1, 1)。
void main() {
    vec2 uv = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    gl_Position = vec4(uv * vec2(2, 2) + vec2(-1, -1), 0, 1);
    texCoord = uv;
}
