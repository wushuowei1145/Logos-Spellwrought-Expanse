#version 330

// 超电磁炮发射时的调色（见 RailgunScreen）：压暗、偏冷、四角更暗；射线在画面上经过的走廊保持原样，
// 并被射线的暖光照亮一点。

uniform sampler2D InSampler;   // 最终画面的副本

layout(std140) uniform Railgun {
    vec4 Segment;   // 射线在画面上的两端，和 texCoord 同一套坐标
    vec4 Grade;     // x 变暗，y、z 两端走廊的半宽（以画面高度为 1，0 表示射线不在眼前），w 宽高比
    vec4 Warm;      // rgb 暖光颜色，a 强度
};

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec3 c = texture(InSampler, texCoord).rgb;
    float dim = Grade.x;

    // 离射线（画面上的那一段）多远，以走廊半宽为 1
    float lit = 0.0, glow = 0.0;
    if (Grade.y + Grade.z > 0.0) {
        vec2 p = vec2(texCoord.x * Grade.w, texCoord.y);
        vec2 a = vec2(Segment.x * Grade.w, Segment.y), b = vec2(Segment.z * Grade.w, Segment.w);
        vec2 ab = b - a;
        float h = clamp(dot(p - a, ab) / max(dot(ab, ab), 1e-8), 0.0, 1.0);
        float d = length(p - a - ab * h) / max(mix(Grade.y, Grade.z, h), 1e-5);
        lit = exp(-d * d * 0.35);
        glow = exp(-d * d * 0.08);
    }

    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    vec3 cold = mix(c, vec3(l) * vec3(0.62, 0.74, 1.1), 0.55) * 0.4;
    // 四角更暗：0 是画面中心，1 是角
    float corner = length(texCoord - 0.5) * 1.414;
    cold *= 1.0 - 0.55 * smoothstep(0.45, 1.0, corner);

    vec3 o = mix(c, cold, dim * (1.0 - lit));
    o += Warm.rgb * Warm.a * dim * glow * (0.25 + l);
    fragColor = vec4(o, 1.0);
}
