#version 330

// 乐器的剪影：g 是全部，r 是没被挡住的部分。透明的像素不算剪影。
// 和半透明之前的深度比较（玻璃、水挡不住），留一点余量：乐器自己的面和深度里的是同一个面。

uniform sampler2D Sampler0;
uniform sampler2D DepthSampler;

in vec2 texCoord0;

out vec4 fragColor;

const float SLACK = 0.12;

float distanceOf(float depth) {
    return 0.05 / max(1.0 - depth, 1e-6);
}

void main() {
    if (texture(Sampler0, texCoord0).a < 0.1) discard;
    vec2 screen = gl_FragCoord.xy / vec2(textureSize(DepthSampler, 0));
    float scene = texture(DepthSampler, screen).r;
    float seen = distanceOf(gl_FragCoord.z) <= distanceOf(scene) + SLACK ? 1.0 : 0.0;
    fragColor = vec4(seen, 1.0, 0.0, 1.0);
}
