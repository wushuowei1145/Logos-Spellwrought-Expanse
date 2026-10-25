#version 330

// 风里的草尖片元阶段：cutout（alpha < 0.5 丢弃），颜色乘顶点色。
// 不采样光照贴图——光照已经在顶点色里烘好（每片草按位置取一次亮度，CPU 侧的代价很小）。
in vec4 vertexColor;
in vec2 texCoord0;

uniform sampler2D Sampler0;

out vec4 fragColor;

void main() {
    vec4 tex = texture(Sampler0, texCoord0);
    if (tex.a < 0.5) discard;
    fragColor = vec4(tex.rgb * vertexColor.rgb, 1.0);
}
