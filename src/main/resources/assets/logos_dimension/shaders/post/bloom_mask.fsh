#version 330

// 把发光的东西画到空白的小图上。小图比屏幕小，用不了深度测试，所以自己和世界、手的深度比较。
// 第一人称手上物品的光在最前面（深度 0），不会被挡。

uniform sampler2D DepthSampler;
uniform sampler2D HandDepthSampler;

in vec4 vertexColor;
in vec2 screen;

out vec4 fragColor;

// 留多少格的余量：光贴在它来自的那一格上，前后差一点不能闪
const float SLACK = 0.15;

float distanceOf(float depth) {
    return 0.05 / max(1.0 - depth, 1e-6);
}

void main() {
    float depth = gl_FragCoord.z;
    if (depth > 1e-4) {
        if (texture(HandDepthSampler, screen).r < 1.0) discard;
        if (distanceOf(depth) > distanceOf(texture(DepthSampler, screen).r) + SLACK) discard;
    }
    // 取平方：加到明亮的天空上时光还留着颜色，不会一下变白
    fragColor = vec4(vertexColor.rgb * vertexColor.rgb * vertexColor.a, 1.0);
}
