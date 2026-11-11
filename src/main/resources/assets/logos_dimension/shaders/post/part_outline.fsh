#version 330

// 选中声部的描边：剪影外面一圈纯色的线，线外再一道 1 像素的暗边（什么底色上都看得清）。附近有看得见的剪影是实线，
// 只有被挡住的剪影是淡线；这个声部有音进来时（Style.z）亮一下。手挡在前面的地方不画。

uniform sampler2D InSampler;
uniform sampler2D HandDepthSampler;

layout(std140) uniform PartOutline {
    mat4 ViewProj;
    vec4 Color;
    vec4 Style;
};

in vec2 texCoord;

out vec4 fragColor;

void main() {
    if (texture(InSampler, texCoord).g > 0.5) discard;
    if (texture(HandDepthSampler, texCoord).r < 1.0) discard;
    vec2 px = 1.0 / vec2(textureSize(InSampler, 0));
    float width = Style.x, outer = Style.x + 1.0;
    float nearAny = 1e3, nearSeen = 1e3;
    for (int ring = 1; ring <= 6; ring++) {
        float r = min(float(ring), outer);
        float turn = (ring & 1) == 1 ? 0.0 : 0.2617994;
        for (int k = 0; k < 12; k++) {
            float a = float(k) * 0.5235988 + turn;
            vec2 m = texture(InSampler, texCoord + vec2(cos(a), sin(a)) * r * px).rg;
            if (m.g > 0.5) nearAny = min(nearAny, r);
            if (m.r > 0.5) nearSeen = min(nearSeen, r);
        }
        if (float(ring) >= outer) break;
    }
    if (nearAny > outer) discard;
    float alpha = nearSeen <= outer ? Color.a : Style.y;
    vec3 rgb = mix(Color.rgb, vec3(1.0), Style.z * 0.4);
    alpha = min(1.0, alpha * (1.0 + Style.z * 0.25));
    // 暗边
    if (nearAny > width) {
        rgb = Color.rgb * 0.18;
        alpha *= 0.6;
    }
    fragColor = vec4(rgb, alpha);
}
