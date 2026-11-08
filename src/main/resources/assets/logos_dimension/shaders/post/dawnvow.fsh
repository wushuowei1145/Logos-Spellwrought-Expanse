#version 330

// 曦誓的全屏后期（见 DawnvowScreen）：大型蓄力时蓄力的人身边亮、周围暗；巨型剑气到头后、光柱冲起之前的一串画面：
// 地上一团光鼓成穹顶，黑色的尖刺炸满画面而外围是亮的、亮光向内收紧，几何黑白闪（反色、白底黑星、黑底白星），
// 穹顶扑满画面，极亮的白。位置由半透明之前的深度还原，坐标相对镜头、世界坐标轴。
// 黑白闪全是几何图形，不取画面里的剪影（那是爆裂魔法的写实黑白闪）。

uniform sampler2D InSampler;          // 最终画面的副本
uniform sampler2D DepthSampler;       // 半透明之前的深度
uniform sampler2D HandDepthSampler;   // 这一帧最后的深度：只有手
uniform sampler2D NoiseSampler;       // 可平铺的柔和噪声
uniform sampler2D OpaqueSampler;      // 分层：实心的世界画完（开着光影包时：合成完、Logos 特效之前）
uniform sampler2D TransSampler;       // 分层：半透明特效画完
uniform sampler2D WeatherSampler;     // 分层：天气画完（第一人称的手之前）

layout(std140) uniform Dawnvow {
    mat4 InvViewProj;   // 裁剪空间 → 相对镜头的位置
    vec4 SpotA;         // xyz 蓄力的人（胸口，相对镜头），w 亮区半径（0 不用）
    vec4 SpotB;         // xyz 剑气头或落点（相对镜头），w 亮区半径（0 不用）
    vec4 Grade;         // x 周围变暗 0~1，y 亮区提亮，z 穹顶的金光照亮地面，w SpotB 只按水平距离算（光柱冲起后）
    vec4 Dome;          // xyz 穹顶中心（地面，相对镜头），w 水平半径
    vec4 DomeLook;      // x 竖直半径，y 芯有多白，z 光晕，w 不透明度
    vec4 Burst;         // x 黑色爆发的外缘（按画面高度），y 黑色的浓度，z 外围亮光的亮度，w 扑满画面 0~1
    vec4 Beat;          // x 这一拍（0 无，1 反色，2 白底黑星，3 黑底白星），y 星星的种子，z 白光 0~1，w 整体强度
    vec4 Screen;        // xy 穹顶在画面上的位置，z 深度是否 0 到 1，w 穹顶在不在眼前（0.3~1）
    vec4 Extra;         // x 画面宽高比，y 穹顶照亮地面的半径（涨大之前的穹顶），z 时间（秒），w 画面高度（像素）
    vec4 Layers;        // x 分层：0 没有（整幅一起调暗），1 原版三份，2 光影包合成完的那一份
};

in vec2 texCoord;
out vec4 fragColor;

const float FAR = 1e8;
const float TAU = 6.28318531;
const vec3 GOLD = vec3(1.0, 0.76, 0.29);
const vec3 PALE = vec3(1.0, 0.91, 0.65);
const vec3 HOT = vec3(1.0, 0.97, 0.89);
const vec3 AMBER = vec3(0.92, 0.4, 0.07);

float luma(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

vec3 positionAt(vec2 uv, float d) {
    float z = Screen.z > 0.5 ? d : d * 2.0 - 1.0;
    vec4 h = InvViewProj * vec4(uv * 2.0 - 1.0, z, 1.0);
    return h.xyz / h.w;
}

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

// 离亮区中心多近：1 在亮区里，0 在外面；column 时只看水平距离（光柱整根都亮）
float litBy(vec4 spot, vec3 p, float column) {
    if (spot.w <= 0.0) return 0.0;
    vec3 d = p - spot.xyz;
    if (column > 0.5) d.y = 0.0;
    return 1.0 - smoothstep(spot.w * 0.35, spot.w * 1.25, length(d));
}

// 绕一圈的噪声（首尾相接）：黑色爆发参差的尖刺、外围亮光里的放射纹
float ring(float ang, float scale, float offset) {
    vec2 q = vec2(cos(ang), sin(ang)) * scale + vec2(0.5 + offset, 0.5 - offset * 0.7);
    return texture(NoiseSampler, q).r;
}

// 绕一圈 count 道尖刺（每道的位置、宽窄、长短按种子错开）：返回这个角度上尖刺伸出多长（0~1.2），尖刺之间是 0
float spikeRing(float ang, float count, float seed, float width) {
    float k = floor(ang / TAU * count + 0.5);
    float best = 0.0;
    for (int j = -1; j <= 1; j++) {
        float kk = k + float(j);
        float idx = mod(kk, count);
        float center = (kk + (hash(vec2(idx, seed)) - 0.5) * 0.6) / count * TAU;
        float w = width * (0.6 + 0.8 * hash(vec2(idx, seed + 3.0))) / count * TAU;
        float len = 0.3 + 0.9 * hash(vec2(idx, seed + 7.0));
        best = max(best, len * pow(max(0.0, 1.0 - abs(ang - center) / w), 1.6));
    }
    return best;
}

// 某一遍画完之后画面变了多少（d = 之后 - 之前）像不像加上去的光：变亮了、而且没有哪个颜色通道变暗。
// 半透明画上去的模型（玩家皮肤就是半透明画的）是把颜色换掉，总有通道变暗，不算。
float glowOf(vec3 d) {
    float up = dot(max(d, 0.0), vec3(0.2126, 0.7152, 0.0722));
    float down = -min(min(d.r, d.g), d.b);
    return smoothstep(0.03, 0.2, up) * (1.0 - smoothstep(0.03, 0.12, down));
}

// 一道尖刺：从原点沿 u 伸出 len，根部半宽 w，越往外越细。返回覆盖 0~1
float spike(vec2 p, vec2 u, float len, float w, float px) {
    float x = dot(p, u);
    if (x < 0.0 || x > len) return 0.0;
    float y = abs(p.x * u.y - p.y * u.x);
    float hw = w * pow(1.0 - x / len, 1.3);
    return 1.0 - smoothstep(hw - px, hw + px, y);
}

// 一颗星或一个墨点（以原点为心，转 rot）：kind 小于 0.6 是四芒星（凹边的芯加四道长芒），否则是墨点（一团芯甩出几道细刺）
float shape(vec2 p, float size, float rot, float kind, float sd, float px) {
    float cs = cos(rot), sn = sin(rot);
    vec2 q = vec2(cs * p.x + sn * p.y, -sn * p.x + cs * p.y);
    float cov;
    if (kind < 0.6) {
        float s = size * 0.55;
        vec2 aq = abs(q) / s;
        float f = pow(aq.x, 0.6667) + pow(aq.y, 0.6667);
        cov = 1.0 - smoothstep(-px, px, (f - 1.0) * s * 0.4);
        for (int k = 0; k < 4; k++) {
            float a = float(k) * 1.5707963;
            float len = size * (1.5 + 1.8 * hash(vec2(sd, float(k))));
            cov = max(cov, spike(q, vec2(cos(a), sin(a)), len, size * 0.2, px));
        }
    } else {
        float core = size * (0.4 + 0.07 * sin(atan(q.y, q.x) * 5.0 + sd));
        cov = 1.0 - smoothstep(core - px, core + px, length(q));
        // 尖刺大致均匀地向四周甩出去，各自错开一点
        float count = 4.0 + floor(hash(vec2(sd, 5.0)) * 4.0);
        for (int k = 0; k < 7; k++) {
            if (float(k) >= count) break;
            float a = (float(k) + (hash(vec2(sd, float(k) + 11.0)) - 0.5) * 0.7) / count * TAU;
            float len = size * (1.1 + 2.9 * hash(vec2(float(k) + 0.5, sd + 2.0)));
            cov = max(cov, spike(q, vec2(cos(a), sin(a)), len, size * 0.24, px));
        }
    }
    return cov;
}

// 一拍里满屏散开的星：白底时是黑的、带一圈蓝边，黑底时是白的、带一圈金边。种子不同，每拍的排布都不一样
vec3 stars(vec3 c, vec2 uv, float aspect, float px, float seed, bool onWhite) {
    vec2 P = vec2(uv.x * aspect, uv.y);
    vec3 ink = onWhite ? vec3(0.0) : vec3(1.0, 0.99, 0.95);
    vec3 edge = onWhite ? vec3(0.16, 0.3, 1.0) : GOLD;
    for (int i = 0; i < 18; i++) {
        float fi = float(i);
        vec2 o = vec2(hash(vec2(seed, fi)) * aspect, hash(vec2(fi + 0.37, seed * 1.3)));
        float size = 0.018 + 0.12 * pow(hash(vec2(seed + 4.1, fi * 1.7)), 2.3);
        vec2 p = P - o;
        if (dot(p, p) > size * size * 25.0) continue;
        float kind = hash(vec2(fi * 2.9, seed + 7.7));
        float rot = hash(vec2(seed * 0.7, fi + 3.3)) * TAU;
        float sd = seed * 13.0 + fi;
        float rim = shape(p, size * 1.14, rot, kind, sd, px * 1.5);
        float cov = shape(p, size, rot, kind, sd, px);
        c = mix(c, edge, rim * 0.85);
        c = mix(c, ink, cov);
    }
    return c;
}

void main() {
    vec2 uv = texCoord;
    float aspect = Extra.x;
    float px = 1.5 / max(Extra.w, 1.0);
    float time = Extra.z;
    // 手：最后的深度里贴着镜头的那些像素。原版画手之前清了深度，最后的深度里只有手；开着光影包时最后的深度里还有世界，
    // 只认一格多以内的，不把整个世界当成手
    float handDepth = texture(HandDepthSampler, uv).r;
    bool hand = handDepth < 1.0 && length(positionAt(uv, handDepth)) < 1.5;
    float depth = texture(DepthSampler, uv).r;
    vec3 dir = normalize(positionAt(uv, 1.0));
    vec3 world = depth < 1.0 ? positionAt(uv, depth) : dir * FAR;
    float sceneDist = hand ? 0.3 : (depth < 1.0 ? length(world) : FAR);
    // 手当作就在镜头这里：蓄力的人自己第一人称时，手在亮区里
    vec3 at = hand ? vec3(0.0) : world;
    vec3 original = texture(InSampler, uv).rgb;
    vec3 c = original;

    // ---- 分层：哪些像素是之后才加上去的光（半透明特效那一遍、手那一遍里的火焰），这些地方不调暗 ----
    // 不用相减还原：画面每个通道到 1 就截断了，减出来的光会偏色；直接保留原来的颜色
    float glow = 0.0;
    if (Layers.x > 0.5 && Layers.x < 1.5) {
        vec3 O = texture(OpaqueSampler, uv).rgb, T = texture(TransSampler, uv).rgb, W = texture(WeatherSampler, uv).rgb;
        glow = glowOf(T - O);
        if (!hand) glow = max(glow, glowOf(c - W));
    } else if (Layers.x > 1.5) {
        glow = glowOf(c - texture(OpaqueSampler, uv).rgb);
    }

    // ---- 周围变暗、褪色，亮区（蓄力的人身边、剑气头、落点）提亮 ----
    float dark = Grade.x;
    if (dark > 0.0) {
        float lit = max(litBy(SpotA, at, 0.0), litBy(SpotB, at, Grade.w));
        vec3 cool = vec3(luma(c)) * vec3(0.66, 0.72, 0.95);
        vec3 far = mix(c, cool, 0.6 * dark) * (1.0 - 0.9 * dark);
        // 亮区带一点金色：是蓄力的人身上的光照亮的
        vec3 warm = mix(vec3(1.0), vec3(1.25, 1.0, 0.66), min(1.0, Grade.y * 3.0));
        vec3 near = c * (1.0 + Grade.y) * warm + GOLD * 0.14 * Grade.y;
        c = mix(far, near, lit);
        float v = length((uv - 0.5) * vec2(1.2, 1.0));
        c *= 1.0 - dark * 0.5 * smoothstep(0.35, 0.95, v) * (1.0 - lit);
    }
    c = mix(c, original, glow);

    vec3 C = Dome.xyz;
    float A = Dome.w, B = max(DomeLook.x, 0.05);

    // ---- 穹顶的金光照亮四周的地面（按涨大之前的穹顶算，涨大时不把整片地都照白） ----
    float L = max(Extra.y, 0.5);
    if (Grade.z > 0.0 && A > 0.0 && depth < 1.0 && !hand) {
        float d = length(world - C);
        c += GOLD * exp(-max(d - L * 0.8, 0.0) / (L * 1.1 + 1.5)) * Grade.z * 0.9;
    }

    // ---- 黑色爆发：黑色的尖刺从穹顶炸满画面，外围反而是亮的，亮光向内收紧 ----
    vec2 rel = (uv - Screen.xy) * vec2(aspect, 1.0);
    float r = length(rel);
    float ang = atan(rel.y, rel.x);
    if (Burst.y > 0.0) {
        // 参差的尖刺：十三道大的、二十九道小的，边上再带一点细碎的锯齿
        float spikes = max(spikeRing(ang, 13.0, 1.0, 0.9), 0.55 * spikeRing(ang, 29.0, 2.0, 0.8));
        float front = Burst.x * (0.5 + 0.9 * spikes + 0.05 * ring(ang, 3.1, 0.17));
        float black = 1.0 - smoothstep(front - px, front + px, r);
        float outside = max(r - front, 0.0);
        vec3 bright = mix(AMBER, GOLD, exp(-outside / 0.4));
        bright = mix(bright, HOT, exp(-outside / 0.035));
        float cloud = texture(NoiseSampler, uv * vec2(aspect, 1.0) * 1.2 + vec2(time * 0.05, -time * 0.12)).r;
        float streak = ring(ang, 2.2, 0.4);
        bright *= (0.72 + 0.45 * cloud) * (0.85 + 0.3 * streak) * Burst.z;
        c = mix(c, mix(bright, vec3(0.0), black), Burst.y);
    }

    // ---- 穹顶：地上一团扁扁的光，鼓成半球，最后猛地涨大扑过镜头 ----
    float domeMask = 0.0;
    vec3 domeCol = vec3(0.0);
    if (A > 0.0 && DomeLook.w > 0.0) {
        // 把竖直方向拉伸成球：椭球变成半径 A 的球来求交
        vec3 S = vec3(1.0, A / B, 1.0);
        vec3 D = dir * S, Cs = C * S;
        float qa = dot(D, D), qb = dot(D, Cs), qk = dot(Cs, Cs) - A * A;
        float disc = qb * qb - qa * qk;
        float entry = disc > 0.0 ? (qb - sqrt(disc)) / qa : FAR;
        if (qk < 0.0) {
            // 镜头已经在穹顶里：满眼的光，画面边上更白
            float edge = smoothstep(0.0, 1.0, length((uv - 0.5) * vec2(aspect, 1.0)));
            domeCol = mix(PALE, HOT, 0.5 + 0.5 * edge);
            domeMask = DomeLook.w;
        } else if (disc > 0.0) {
            float t = (qb - sqrt(disc)) / qa;
            if (t > 0.0 && t < sceneDist) {
                vec3 q = dir * t - C;
                vec3 nrm = normalize(vec3(q.x / (A * A), q.y / (B * B), q.z / (A * A)));
                float facing = clamp(dot(nrm, -dir), 0.0, 1.0);
                float rim = 1.0 - facing;
                float swirl = texture(NoiseSampler, q.xz / A * 0.35 + vec2(time * 0.11, -time * 0.07)).r;
                // 白热的芯，往边上一圈淡金，最边上是金
                vec3 col = mix(HOT, PALE, smoothstep(0.25, 0.75, rim));
                col = mix(col, GOLD, smoothstep(0.6, 1.0, rim) * (1.0 - 0.5 * DomeLook.y));
                col = mix(col, vec3(1.0), DomeLook.y * facing * facing);
                col *= 0.92 + 0.16 * swirl;
                // 扑满画面时：边缘白，往里一圈金、一圈琥珀
                float e = Burst.w;
                if (e > 0.0) {
                    vec3 band = mix(HOT, GOLD, smoothstep(0.08, 0.35, facing));
                    band = mix(band, AMBER, smoothstep(0.45, 0.9, facing));
                    col = mix(col, band, e);
                }
                domeCol = col;
                domeMask = DomeLook.w * (0.9 + 0.1 * facing);
            }
        }
        c = mix(c, domeCol, domeMask);
        // 光晕：视线离穹顶越近越亮，压在一切上面；被墙挡在后面时弱很多
        float tc = qb / qa;
        if (tc > 0.0 && qk >= 0.0) {
            float perp = sqrt(max(dot(Cs, Cs) - qb * qb / qa, 0.0));
            float glow = exp(-max(perp - A, 0.0) / (min(A, L * 1.5) * 0.7 + 0.8));
            // 被墙挡在后面、或者视线先碰到穹顶前面的地面时，光晕弱很多：地面只由上面那层金光照亮
            if (sceneDist < tc - A * 1.2 || sceneDist < entry) glow *= 0.25;
            c += GOLD * glow * DomeLook.z * 0.85 * (1.0 - domeMask);
            c += HOT * pow(glow, 3.0) * DomeLook.z * 0.5 * (1.0 - domeMask);
        }
    }

    // ---- 几何黑白闪：反色；白底上穹顶反色、散着黑星；黑底上穹顶原色、散着白星 ----
    float beat = Beat.x;
    if (beat > 0.5) {
        if (beat < 1.5) {
            c = vec3(1.0) - clamp(c, 0.0, 1.0);
        } else {
            bool onWhite = beat < 2.5;
            vec3 bg = onWhite ? vec3(1.0) : vec3(0.0);
            vec3 dome = onWhite ? vec3(1.0) - clamp(domeCol, 0.0, 1.0) : domeCol;
            c = mix(bg, dome, domeMask);
            c = stars(c, uv, aspect, px, Beat.y, onWhite);
        }
    }

    // ---- 极亮：画面整个白掉，穹顶那里透一点暖色 ----
    float w = Beat.z;
    if (w > 0.0) {
        vec3 haze = mix(HOT, vec3(1.0, 0.84, 0.6), exp(-r * r / 0.15) * 0.55);
        c = mix(c, haze, w);
    }

    fragColor = vec4(mix(original, clamp(c, 0.0, 1.0), Beat.w), 1.0);
}
