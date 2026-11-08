#version 330

// 爆裂魔法的全屏后期（见 ExplosionScreen）：吟唱时的变暗，爆炸时的红热、白热、转蓝、十字闪，
// 白热的球体和被推开的空气。位置都由半透明之前的深度还原，坐标相对镜头、世界坐标轴。

uniform sampler2D InSampler;          // 最终画面的副本
uniform sampler2D DepthSampler;       // 半透明之前的深度
uniform sampler2D HandDepthSampler;   // 这一帧最后的深度：只有手
uniform sampler2D NoiseSampler;       // 可平铺的柔和噪声

layout(std140) uniform Explosion {
    mat4 InvViewProj;   // 裁剪空间 → 相对镜头的位置
    vec4 Center;        // xyz 爆点（相对镜头），w 爆炸半径
    vec4 Grade;         // x 变暗，y 红热，z 白热，w 转蓝
    vec4 Burst;         // x 十字闪，y 球体半径，z 球体不透明度，w 时间（秒）
    vec4 Shock;         // x 落地冲击的球壳半径，y 强度，z 气浪的球壳半径，w 强度（没有爆炸时 xy 是刚蓄满的人推出去的空气）
    vec4 Screen;        // xy 爆点在画面上的位置，z 深度是否 0 到 1，w 爆点在不在眼前（0.35~1）
    vec4 Extra;         // x 画面宽高比，y 自己蓄满后画面四周往上流的蓝光
};

in vec2 texCoord;
out vec4 fragColor;

const float FAR = 1e8;

float luma(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

vec3 positionAt(vec2 uv, float d) {
    float z = Screen.z > 0.5 ? d : d * 2.0 - 1.0;
    vec4 h = InvViewProj * vec4(uv * 2.0 - 1.0, z, 1.0);
    return h.xyz / h.w;
}

// 黑体的颜色：0 暗红，0.3 红，0.55 橙，0.8 黄，1 白
vec3 blackbody(float t) {
    t = clamp(t, 0.0, 1.0);
    vec3 c = mix(vec3(0.25, 0.0, 0.0), vec3(1.0, 0.12, 0.02), smoothstep(0.0, 0.3, t));
    c = mix(c, vec3(1.0, 0.5, 0.1), smoothstep(0.3, 0.55, t));
    c = mix(c, vec3(1.0, 0.86, 0.45), smoothstep(0.55, 0.8, t));
    return mix(c, vec3(1.0, 1.0, 0.95), smoothstep(0.8, 1.0, t));
}

// ---- 三维值噪声 ----

float hash3(vec3 p) {
    p = fract(p * 0.3183099 + vec3(0.71, 0.113, 0.419));
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}

float valueNoise3(vec3 p) {
    vec3 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(hash3(i), hash3(i + vec3(1, 0, 0)), f.x), mix(hash3(i + vec3(0, 1, 0)), hash3(i + vec3(1, 1, 0)), f.x), f.y),
               mix(mix(hash3(i + vec3(0, 0, 1)), hash3(i + vec3(1, 0, 1)), f.x), mix(hash3(i + vec3(0, 1, 1)), hash3(i + vec3(1, 1, 1)), f.x), f.y), f.z);
}

float fbm(vec3 p) {
    float s = 0.0, a = 0.5;
    for (int i = 0; i < 4; i++) {
        s += a * valueNoise3(p);
        p = p * 2.03 + vec3(1.7, 9.2, 3.1);
        a *= 0.5;
    }
    return s;
}

// 视线（单位向量 dir）和以 c 为心、半径 r 的球：返回 (进入点, 离开点)，打不到时 x > y
vec2 sphereHit(vec3 dir, vec3 c, float r) {
    float b = dot(dir, c);
    float k = dot(c, c) - r * r;
    float disc = b * b - k;
    if (disc < 0.0) return vec2(1.0, -1.0);
    float s = sqrt(disc);
    return vec2(b - s, b + s);
}

// 看不见的球壳：视线擦过壳的地方是一圈。返回 (这一圈的强度, 视线最贴近球心的距离)
float shell(vec3 dir, float sceneDist, float radius, float width) {
    if (radius <= 0.0) return 0.0;
    vec3 c = Center.xyz;
    float b = dot(dir, c);
    float perp = sqrt(max(dot(c, c) - b * b, 0.0));
    // 镜头在壳里时看到的是壳的内壁：用离开点
    float ring = exp(-pow((perp - radius) / width, 2.0));
    float along = b > 0.0 ? b : 0.0;
    // 被挡住的部分不画：最贴近的那一点在场景后面
    if (along > sceneDist + radius * 0.1) ring = 0.0;
    return ring;
}

void main() {
    vec2 uv = texCoord;
    float aspect = Extra.x;
    bool hand = texture(HandDepthSampler, uv).r < 1.0;
    float depth = texture(DepthSampler, uv).r;
    vec3 farPoint = positionAt(uv, 1.0);
    vec3 dir = normalize(farPoint);
    vec3 world = depth < 1.0 ? positionAt(uv, depth) : dir * FAR;
    float sceneDist = hand ? 0.3 : (depth < 1.0 ? length(world) : FAR);
    vec2 toCenter = (uv - Screen.xy) * vec2(aspect, 1.0);
    float r = length(toCenter);
    vec2 outward = r > 1e-4 ? (uv - Screen.xy) / max(length(uv - Screen.xy), 1e-4) : vec2(0.0);

    // ---- 空气被推开：擦过球壳的地方把背后的画面往外折射 ----
    float airRing = hand ? 0.0 : shell(dir, sceneDist, Shock.x, max(2.0, Shock.x * 0.06)) * Shock.y;
    float waveRing = hand ? 0.0 : shell(dir, sceneDist, Shock.z, max(3.0, Shock.z * 0.08)) * Shock.w;
    vec2 offset = outward * (airRing * 0.018 + waveRing * 0.024);
    // 火柱落地后爆点附近的热浪
    if (Grade.y > 0.0 && !hand) {
        vec2 n = texture(NoiseSampler, uv * 3.0 + vec2(0.0, Burst.w * 0.35)).rg - 0.5;
        offset += n * 0.006 * Grade.y * exp(-r * r / 0.08) * Screen.w;
    }
    vec3 c = texture(InSampler, uv + offset).rgb;

    // ---- 吟唱：周围变暗、偏冷，边角更暗 ----
    float dim = Grade.x;
    if (dim > 0.0) {
        float l = luma(c);
        c = mix(c, vec3(l) * vec3(0.55, 0.72, 1.2), dim * 0.55);
        c *= 1.0 - dim * 0.7;
        float v = length((texCoord - 0.5) * vec2(1.2, 1.0));
        c *= 1.0 - dim * 0.6 * smoothstep(0.3, 0.9, v);
    }

    // ---- 自己蓄满后（第一人称）：魔力从身上涌出，画面四周一圈火苗似的蓝光往上流 ----
    float aura = Extra.y;
    if (aura > 0.0) {
        float v = length((texCoord - 0.5) * vec2(1.25, 1.0));
        float edge = smoothstep(0.32, 0.8, v);
        float flow = texture(NoiseSampler, vec2(texCoord.x * 2.6, texCoord.y * 1.1 - Burst.w * 0.9)).r;
        float flow2 = texture(NoiseSampler, vec2(texCoord.x * 5.1 + 0.37, texCoord.y * 2.3 - Burst.w * 1.7)).r;
        float flames = smoothstep(0.42, 0.9, flow * 0.65 + flow2 * 0.45);
        float pulse = 0.8 + 0.2 * sin(Burst.w * 5.0);
        c += vec3(0.1, 0.36, 1.0) * aura * edge * (0.25 + 1.1 * flames) * pulse * 0.6;
    }

    // ---- 红热到白热：按到爆点的真实距离 ----
    float heat = Grade.y, white = Grade.z;
    if (heat > 0.0) {
        float reach = Center.w * (0.55 + 0.9 * white);
        float near = depth < 1.0 && !hand ? 1.0 - smoothstep(reach * 0.12, reach, length(world - Center.xyz)) : 0.0;
        float h = heat * near;
        vec3 hot = blackbody(0.3 + 0.45 * h + 0.35 * white);
        float l = luma(c);
        c *= 1.0 - 0.72 * heat * (1.0 - near);
        c = mix(c, hot * (0.55 + 0.9 * l), h * 0.85);
        c += hot * h * 0.3;
        // 天空和远处：爆点方向一团光
        float glow = exp(-r * r / (0.015 + 0.09 * white)) * (0.5 + 1.6 * white);
        c += blackbody(0.45 + 0.55 * white) * heat * glow * Screen.w;
        c = mix(c, vec3(1.0, 0.97, 0.9), clamp(white * heat * exp(-r * r / (0.008 + 0.1 * white)) * Screen.w * 1.3, 0.0, 1.0));
    }

    // ---- 白热的球体（参考 Unreal 的爆炸）：边缘一圈白热，往里越来越透，透出壳里翻滚的橙红火云和正中一颗极亮的白球
    //      （从哪个方向看都在中间）；壳上一层层不规则的波纹从顶上往下流到地面 ----
    float R = Burst.y, alpha = Burst.z;
    if (R > 0.0 && alpha > 0.0) {
        vec3 C = Center.xyz;
        float time = Burst.w;
        if (dot(C, C) < R * R) {
            // 镜头已经在球里
            c = mix(c, vec3(1.0, 0.96, 0.88), 0.9 * alpha);
        } else {
            vec2 t = sphereHit(dir, C, R);
            if (t.x <= t.y && t.x > 0.0 && t.x < sceneDist) {
                vec3 p = dir * t.x;
                vec3 n = (p - C) / R;
                float facing = clamp(dot(n, -dir), 0.0, 1.0);
                float rim = 1.0 - facing;
                // 噪声贴着球面走：球长大时花纹跟着撑开，再慢慢翻滚
                float turb = fbm(n * 3.2 + vec3(0.0, -time * 0.5, time * 0.2));
                float fine = fbm(n * 9.0 + vec3(time * 0.8, 0.0, 0.0));

                // 壳里的白球：球心在地面上，露出来的是半个球，白球放在这半个球看上去的正中（离地三成二半径）
                vec3 Cc = C + vec3(0.0, R * 0.32, 0.0);
                float b = dot(dir, Cc);
                float perp = sqrt(max(dot(Cc, Cc) - b * b, 0.0));
                vec3 q = (dir * b - Cc) / R;
                // 火云：取视线上最贴近白球的那一点，橙红到金
                float cloud = fbm(q * 2.4 + vec3(0.0, -time * 0.3, time * 0.12));
                vec3 inside = mix(vec3(0.88, 0.24, 0.05), vec3(1.0, 0.62, 0.22), smoothstep(0.3, 0.72, cloud));
                inside *= 0.75 + 0.5 * cloud;
                // 白球边缘带一点横向的毛刺；被地面挡住的部分不画。外面一圈很亮的日冕，再外面一层淡金的光晕
                float rc = R * 0.26 * (1.0 + 0.08 * (valueNoise3(vec3(q.y * 26.0, time * 2.5, 3.0)) - 0.5));
                float coreFront = b - sqrt(max(rc * rc - perp * perp, 0.0));
                float core = smoothstep(rc + R * 0.015, rc - R * 0.005, perp) * step(coreFront, sceneDist);
                float beyond = max(perp - rc, 0.0);
                inside = mix(inside, vec3(1.0, 0.82, 0.5), exp(-beyond / (R * 0.3)) * 0.35);
                inside = mix(inside, vec3(1.0, 0.97, 0.9), exp(-beyond / (R * 0.06)));
                inside = mix(inside, vec3(1.0), core);

                // 壳：边缘白热，中间橙金、半透明
                vec3 skin = mix(vec3(1.0, 0.68, 0.32), vec3(1.0, 0.98, 0.92), smoothstep(0.35, 0.95, rim));
                skin = mix(skin, vec3(1.0, 0.46, 0.12), smoothstep(0.42, 0.2, turb) * 0.4);
                skin *= 1.0 + 0.45 * (turb - 0.5) + 0.25 * (fine - 0.5);
                float skinA = mix(0.3, 1.0, pow(rim, 1.8)) * (0.7 + 0.6 * turb);
                vec3 col = mix(inside, skin, clamp(skinA, 0.0, 1.0));

                // 一层层不规则的波纹：锯齿形的亮纹带着一层光，前面（还没流到的下方）压着一道暗影，像一圈浪；断断续续，
                // 从顶上（theta 0）一圈圈往下流到地面（theta 1.57）
                float theta = acos(clamp(n.y, -1.0, 1.0));
                vec3 horiz = vec3(n.x, n.z, 0.0);
                float width = max(0.03, 0.004 * t.x / R);
                float ripple = 0.0, shadow = 0.0;
                for (int i = 0; i < 3; i++) {
                    float fi = float(i);
                    float pos = fract(time * 0.28 + fi / 3.0) * 1.9 - 0.15;
                    float jag = (valueNoise3(horiz * 3.0 + vec3(0.0, 0.0, fi * 7.0 + time * 0.6)) - 0.5) * 0.24
                              + (valueNoise3(horiz * 16.0 + vec3(0.0, 0.0, fi * 3.0 + time * 1.4)) - 0.5) * 0.1
                              + (valueNoise3(horiz * 45.0 + vec3(0.0, 0.0, fi * 5.0 + time * 3.0)) - 0.5) * 0.045;
                    float ds = theta + jag - pos;
                    float d = abs(ds);
                    float line = exp(-d / width) + 0.35 * exp(-d / (width * 3.5));
                    float broken = smoothstep(0.2, 0.48, valueNoise3(horiz * 5.0 + vec3(0.0, 0.0, fi * 11.0 + time * 0.4)));
                    float k = broken * smoothstep(-0.15, 0.1, pos) * (1.0 - smoothstep(1.35, 1.7, pos));
                    ripple += line * k;
                    shadow += smoothstep(width * 0.8, width * 2.0, ds) * (1.0 - smoothstep(width * 2.0, width * 6.0, ds)) * k;
                }
                col *= 1.0 - 0.32 * clamp(shadow, 0.0, 1.0);
                col = mix(col, vec3(1.0, 0.99, 0.94), clamp(ripple, 0.0, 1.0) * (0.75 + 0.25 * facing));

                // 散去时按噪声一块块消失，边缘烧成橙色；白球最后才散
                float erode = 1.0 - alpha;
                float keep = turb * 0.75 + fine * 0.25 + facing * 0.25;
                float a = smoothstep(erode * 1.25 - 0.05, erode * 1.25 + 0.12, keep);
                col = mix(vec3(1.0, 0.45, 0.12), col, smoothstep(erode * 1.25, erode * 1.25 + 0.3, keep));
                float coreLeft = core * smoothstep(0.0, 0.45, alpha);
                col = mix(col, vec3(1.0), coreLeft * (1.0 - a));
                a = max(a, coreLeft);
                // 边缘稍微透一点，像一层发光的气
                a *= mix(1.0, 0.85, pow(rim, 3.0));
                c = mix(c, col, clamp(a, 0.0, 1.0));
            }
            // 和地面相交的地方一圈更亮
            if (depth < 1.0 && !hand) {
                float gap = abs(length(world - C) - R);
                c += vec3(1.0, 0.62, 0.25) * exp(-gap / (1.5 + R * 0.03)) * alpha * 0.9;
            }
        }
    }

    // 球壳上淡淡的一层白雾
    c += vec3(1.0, 0.96, 0.9) * (airRing * 0.12 + waveRing * 0.1);

    // ---- 转蓝：整个画面变成深蓝到青白的两色，中心留一团白 ----
    float blue = Grade.w;
    if (blue > 0.0) {
        // 前一刻还是白热，整个画面都很亮：分界压得很高，大部分落进深蓝，只有最亮的地方是青白
        float l = luma(c);
        vec3 tone = mix(vec3(0.0, 0.025, 0.13), vec3(0.08, 0.32, 0.85), smoothstep(0.25, 0.8, l));
        tone = mix(tone, vec3(0.6, 0.88, 1.0), smoothstep(0.8, 0.99, l));
        tone = mix(tone, vec3(0.95, 0.99, 1.0), exp(-r * r / 0.012) * Screen.w);
        c = mix(c, tone, blue);
    }

    // ---- 十字闪：蓝色画面中心的一颗白星：不大的白芯，上下左右四道又细又长的光芒（横的最长，贯穿画面），斜向四道短芒 ----
    float flare = Burst.x * Screen.w;
    if (flare > 0.0) {
        vec2 d = abs(toCenter);
        float rr = dot(toCenter, toCenter);
        float core = exp(-rr / (0.0015 + 0.03 * flare * flare));
        float halo = exp(-sqrt(rr) / (0.05 + 0.12 * flare)) * 0.55;
        float h = exp(-d.y / (0.002 + 0.006 * flare)) * exp(-d.x / (0.3 + 1.4 * flare));
        float v = exp(-d.x / (0.002 + 0.006 * flare)) * exp(-d.y / (0.15 + 0.6 * flare));
        // 芒外面一层柔光，细线在蓝底上才有分量
        float hGlow = exp(-d.y / (0.015 + 0.03 * flare)) * exp(-d.x / (0.15 + 0.7 * flare)) * 0.45;
        float vGlow = exp(-d.x / (0.015 + 0.03 * flare)) * exp(-d.y / (0.08 + 0.3 * flare)) * 0.45;
        float diag = exp(-abs(d.x - d.y) / (0.002 + 0.004 * flare)) * exp(-(d.x + d.y) / (0.06 + 0.2 * flare)) * 0.5;
        c = mix(c, vec3(0.97, 0.99, 1.0), clamp(flare * (core * 1.5 + halo + h + v + hGlow + vGlow + diag), 0.0, 1.0));
        c += vec3(0.5, 0.7, 1.0) * flare * 0.08;
    }

    fragColor = vec4(c, 1.0);
}
