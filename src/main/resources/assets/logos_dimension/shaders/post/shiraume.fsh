#version 330

// 白梅战的全屏调色（见 ShiraumeScreen）：
// - 色散：从画面中心往外红蓝分开，断封印、弹反、拍地时一下，几百毫秒衰减；
// - 红光：封印全断之后，封印之间里画面的高光（灯本身、被灯照亮的地方）色相转向暗红，幽蓝的狐火也变红；
//   暗处略压一点、四角略暗。光源上的红晕另由泛光加（ShiraumeHalos）；
// - 速度感：缩地、处决的冲刺、斩法的扑击，画面朝冲过来的她（焦点）径向拖影，外圈一圈细长的速度线、内圈留空，
//   四周略压暗，视线收到她身上。

uniform sampler2D InSampler;   // 最终画面的副本

layout(std140) uniform Shiraume {
    vec4 Params;   // x 色散强度 0–1，y 红光强度 0–1，z 宽高比，w 时间（秒）
    vec4 Speed;    // x 速度感强度 0–1，yz 焦点（画面坐标，左下为原点），w 未用
};

in vec2 texCoord;
out vec4 fragColor;

// 名字带前缀：GLSL 有些驱动把 noise1–4 之类当内建函数
float shiraumeHash(float n) {
    return fract(sin(n * 91.3458) * 47453.5453);
}

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
    float speed = Speed.x;
    vec2 aspect = vec2(Params.z, 1.0);
    vec2 toFocus = Speed.yz - texCoord;
    float r = length(toFocus * aspect);
    if (speed > 0.001) {
        // 径向拖影：往焦点方向取样，离焦点越远拖得越长，焦点附近（她）保持清楚
        float drag = 0.09 * speed * smoothstep(0.06, 0.6, r);
        vec3 acc = col;
        float total = 1.0;
        for (int i = 1; i <= 10; i++) {
            float k = float(i) / 10.0;
            float w = 1.0 - k * 0.6;
            acc += texture(InSampler, texCoord + toFocus * drag * k).rgb * w;
            total += w;
        }
        col = acc / total;
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
    if (speed > 0.001) {
        // 速度线：绕焦点分成 200 个扇区，每 0.05 秒随机亮一些（越快越多）；线由内往外渐粗，内圈留空
        vec2 d = -toFocus * aspect;
        float ang = atan(d.y, d.x) / 6.2831853 + 0.5;
        float sectors = 200.0;
        float cell = floor(ang * sectors);
        float frame = floor(Params.w * 20.0);
        float lit = step(1.0 - 0.38 * speed, shiraumeHash(cell * 1.731 + frame * 17.17));
        float across = abs(fract(ang * sectors) - 0.5) * 2.0;
        float inner = mix(0.46, 0.24, speed) + 0.16 * shiraumeHash(cell * 3.17 + frame * 1.3);
        float along = smoothstep(inner, inner + 0.28, r);
        float width = mix(0.18, 0.55, shiraumeHash(cell * 7.7 + frame)) * along;
        float streak = lit * (1.0 - smoothstep(width * 0.6, width, across)) * along;
        col = mix(col, vec3(1.0, 0.96, 0.97), clamp(streak * speed * 0.8, 0.0, 0.85));
        col *= 1.0 - 0.25 * speed * smoothstep(0.3, 0.95, r);
    }
    fragColor = vec4(col, 1.0);
}
