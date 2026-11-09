#version 330

// 白梅战的全屏调色（见 ShiraumeScreen）：
// - 色散：从画面中心往外红蓝分开，断封印、弹反、拍地时一下，几百毫秒衰减；
// - 红光：封印全断之后，封印之间里画面的高光（灯本身、被灯照亮的地方）色相转向暗红，幽蓝的狐火也变红；
//   暗处略压一点、四角略暗。光源上的红晕另由泛光加（ShiraumeHalos）。

uniform sampler2D InSampler;   // 最终画面的副本

layout(std140) uniform Shiraume {
    vec4 Params;   // x 色散强度 0–1，y 红光强度 0–1，z 宽高比，w 时间（秒）
};

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec2 c = texCoord - 0.5;
    float split = Params.x;
    vec3 col;
    if (split > 0.001) {
        // 越往外分得越开；中心几乎不动
        vec2 dir = c * (0.006 + 0.03 * dot(c, c)) * split * 2.0;
        col.r = texture(InSampler, texCoord + dir).r;
        col.g = texture(InSampler, texCoord + dir * 0.15).g;
        col.b = texture(InSampler, texCoord - dir).b;
    } else {
        col = texture(InSampler, texCoord).rgb;
    }
    float mood = Params.y;
    if (mood > 0.001) {
        float lum = dot(col, vec3(0.2126, 0.7152, 0.0722));
        float peak = max(max(col.r, col.g), col.b);
        // 高光：带颜色的光（狐火的青、灯笼的暖黄）往红偏得多，暗一些也算；白的东西（她的毛、纸）只微微泛红，灰的石头不动
        float low = min(min(col.r, col.g), col.b);
        float sat = peak > 0.001 ? (peak - low) / peak : 0.0;
        float hi = max(smoothstep(0.30, 0.82, peak) * mix(0.4, 1.0, sat), sat * smoothstep(0.10, 0.34, peak));
        vec3 red = vec3(1.0, 0.16, 0.14) * (lum * 1.25 + peak * 0.35 + 0.03);
        col = mix(col, red, mood * hi * 0.78);
        // 暗处略压、偏一点紫红
        col = mix(col, col * vec3(0.9, 0.82, 0.86), mood * 0.4 * (1.0 - hi));
        float corner = length(c * vec2(Params.z, 1.0)) / length(vec2(Params.z, 1.0) * 0.5);
        col *= 1.0 - 0.22 * mood * smoothstep(0.45, 1.0, corner);
    }
    fragColor = vec4(col, 1.0);
}
