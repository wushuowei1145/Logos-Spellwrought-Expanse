#version 330

// 模糊链往上走一级，加到上一级；定义了 COMPOSITE 时是最后一步，加到屏幕上。

uniform sampler2D InSampler;

#ifdef COMPOSITE
layout(std140) uniform Bloom {
    float Strength;
};
#endif

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec2 d = 1.0 / vec2(textureSize(InSampler, 0));
    vec3 c = texture(InSampler, texCoord + vec2(-d.x, 0.0)).rgb;
    c += texture(InSampler, texCoord + vec2(d.x, 0.0)).rgb;
    c += texture(InSampler, texCoord + vec2(0.0, -d.y)).rgb;
    c += texture(InSampler, texCoord + vec2(0.0, d.y)).rgb;
    c += texture(InSampler, texCoord + vec2(-d.x, -d.y) * 0.5).rgb * 2.0;
    c += texture(InSampler, texCoord + vec2(d.x, -d.y) * 0.5).rgb * 2.0;
    c += texture(InSampler, texCoord + vec2(-d.x, d.y) * 0.5).rgb * 2.0;
    c += texture(InSampler, texCoord + vec2(d.x, d.y) * 0.5).rgb * 2.0;
    c /= 12.0;
#ifdef COMPOSITE
    // 小图只有八位，又宽又淡的光会出现色阶：加一点噪声盖住
    float noise = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    c = max(c * Strength + (noise - 0.5) / 255.0 * step(1.0 / 255.0, c.r + c.g + c.b), vec3(0.0));
#endif
    fragColor = vec4(c, 1.0);
}
