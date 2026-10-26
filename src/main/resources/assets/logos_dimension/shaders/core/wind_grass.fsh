#version 330

// 风里的草尖片元阶段：cutout（alpha < 0.5 丢弃），颜色乘顶点色。
// 不采样光照贴图——光照已经在顶点色里烘好（每片草按位置取一次亮度，CPU 侧的代价很小）。
in vec4 vertexColor;
in vec2 texCoord0;
in float vHeight;
in float vWind;

uniform sampler2D Sampler0;

out vec4 fragColor;

void main() {
    vec4 tex = texture(Sampler0, texCoord0);
    if (tex.a < 0.5) discard;
    // 风格化草色：根部压暗、叶尖提亮；被风压弯时叶尖更亮（掠射光的感觉）。
    vec3 root = vec3(0.42, 0.50, 0.30);
    vec3 tip = vec3(1.06, 1.14, 0.82);
    vec3 grade = mix(root, tip, clamp(vHeight, 0.0, 1.0));
    grade *= 0.92 + 0.20 * clamp(vWind, 0.0, 1.2);
    fragColor = vec4(tex.rgb * grade * vertexColor.rgb, 1.0);
}
