#version 330

// 模糊链往下走一级（双重滤波：小图、少量采样，结果又宽又软）。

uniform sampler2D InSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec2 d = 1.0 / vec2(textureSize(InSampler, 0));
    vec3 c = texture(InSampler, texCoord).rgb * 4.0;
    c += texture(InSampler, texCoord + vec2(-d.x, -d.y)).rgb;
    c += texture(InSampler, texCoord + vec2(d.x, -d.y)).rgb;
    c += texture(InSampler, texCoord + vec2(-d.x, d.y)).rgb;
    c += texture(InSampler, texCoord + vec2(d.x, d.y)).rgb;
    fragColor = vec4(c / 8.0, 1.0);
}
