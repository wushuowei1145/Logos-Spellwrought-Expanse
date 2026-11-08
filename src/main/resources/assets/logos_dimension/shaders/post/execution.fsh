#version 330

// 处决特写的全屏画面。整个画面换成平涂的两色：背景一色，目标的剪影一色；刀光、刀痕和速度线压在上面。
// 只读最终画面的颜色和半透明实体画完之后的深度，不碰光影包的缓冲。

uniform sampler2D InSampler;
uniform sampler2D DepthSampler;

layout(std140) uniform Execution {
    mat4 InvViewProj;   // 裁剪空间还原到相对镜头的坐标
    mat4 BodyInv;       // 相对镜头的坐标换到身体自己的坐标（原点在身体中心，跟着身体转）
    vec4 Box;           // xyz 身体盒子的半边长，w 地面（相对镜头的高度），地面以下的不算身体
    vec4 State;         // x 画什么，y 黑白是否对调，z 这一拍的随机种子，w 深度是否 0 到 1
    vec4 Target;        // xy 身体中心在画面上的位置，z 身体在画面上的半径（按画面高度），w 宽高比
    vec4 Look;          // x 色差，y 刀痕爆开的程度，z 是否黑底，w 重击那一刀走到第几成（0 没有）
    vec4 Finisher;      // x 重击的角度，y 弯曲，z 粗细，w 是否正在往下拍
    vec4 Extra;         // y 集中线的稀疏程度（越小越密），z 刀痕有几道，w 火花有几个
    vec4 Slash[4];      // 正在划的刀：x 角度，y 离中心多远，z 走到第几成，w 粗细（0 没有）
    vec4 Bend[4];       // x 这一刀弯多少
    vec4 Scar[24];      // 已经划过的刀痕：x 角度，y 离中心多远，z 弯曲，w 它一侧的身体沿刀错开多少
    vec4 Spark[4];      // 受击火花：xy 位置（相对身体中心，按画面高度），z 半径，w 随机种子
};

in vec2 texCoord;
out vec4 fragColor;

const float TAU = 6.28318531;
const float INK = 1.0, RED = 2.0, IMPACT = 3.0, OVERLAY = 4.0;
// 刀身的半长（按画面高度）：从画面外划进来，划到画面外去
const float LENGTH = 1.4;
// 刀走过之后留下的细线有多宽
const float LINE = 0.0014;
const vec3 PAPER = vec3(0.98), SUMI = vec3(0.03);
const vec3 CRIMSON = vec3(0.78, 0.02, 0.05), DEEP = vec3(0.33, 0.0, 0.02);

float hash(float x) {
    return fract(sin(x * 91.17 + 17.0) * 43758.5453);
}

bool isMode(float mode) {
    return abs(State.x - mode) < 0.5;
}

vec3 positionAt(vec2 uv, float depth) {
    vec4 clip = vec4(uv * 2.0 - 1.0, State.w > 0.5 ? depth : depth * 2.0 - 1.0, 1.0);
    vec4 p = InvViewProj * clip;
    return p.xyz / p.w;
}

// 这个像素是不是目标身上：深度还原出的位置落在身体的盒子里。天空、远处的地形都在盒子外面。
float bodyAt(vec2 uv) {
    float depth = texture(DepthSampler, uv).r;
    if (depth >= 1.0) return 0.0;
    vec3 p = positionAt(uv, depth);
    if (p.y < Box.w) return 0.0;
    vec3 q = abs((BodyInv * vec4(p, 1.0)).xyz) - Box.xyz;
    return max(q.x, max(q.y, q.z)) <= 0.0 ? 1.0 : 0.0;
}

// x 身体的覆盖（边缘和四邻平均一下，不发毛），y 身体内部的分界线（手臂压在身体前面的地方），画成细白线
vec2 body(vec2 uv, vec2 texel) {
    float c = bodyAt(uv);
    float n = bodyAt(uv + vec2(texel.x, 0.0)) + bodyAt(uv - vec2(texel.x, 0.0)) + bodyAt(uv + vec2(0.0, texel.y)) + bodyAt(uv - vec2(0.0, texel.y));
    float inner = 0.0;
    if (c > 0.5 && n > 3.5) {
        float d = texture(DepthSampler, uv).r;
        float sum = texture(DepthSampler, uv + vec2(texel.x, 0.0)).r + texture(DepthSampler, uv - vec2(texel.x, 0.0)).r
                + texture(DepthSampler, uv + vec2(0.0, texel.y)).r + texture(DepthSampler, uv - vec2(0.0, texel.y)).r;
        inner = smoothstep(0.04, 0.1, abs(sum - 4.0 * d) / max(1.0 - d, 1e-5));
    }
    return vec2(c * 0.5 + n * 0.125, inner);
}

// 集中线：从画面四周往目标收过去，不碰到身体；每拍换一次
float speed(vec2 p, float radius) {
    float r = length(p);
    float a = atan(p.y, p.x) / TAU + 0.5;
    float n = 120.0;
    float cell = floor(a * n);
    if (hash(cell * 1.31 + State.z * 7.7) < Extra.y) return 0.0;
    float start = radius * (1.7 + hash(cell * 2.17 + State.z * 3.1 + 5.0) * 2.4);
    float t = clamp((r - start) / 0.5, 0.0, 1.0);
    return step(abs(fract(a * n) - 0.5), 0.24 * t) * step(start, r);
}

// 往下拍的时候背景里竖着的速度线
float fall(vec2 uv, float aspect) {
    float x = uv.x * aspect * 60.0;
    float col = floor(x);
    if (hash(col * 1.7 + State.z * 5.3) < 0.6) return 0.0;
    float len = 0.25 + hash(col * 3.1 + State.z) * 0.5;
    float y = fract(uv.y - hash(col * 4.3 + State.z * 2.0));
    return step(abs(fract(x) - 0.5), 0.12) * step(y, len);
}

// 一刀在这一点上的覆盖：x 刀身（粗的一笔，刀头那一端最粗、刀尾拖得很长），y 刀走过的那条细线
vec2 stroke(vec2 p, float angle, float offset, float progress, float width, float bend) {
    vec2 dir = vec2(cos(angle), sin(angle));
    vec2 nrm = vec2(-dir.y, dir.x);
    float along = dot(p, dir);
    float across = dot(p, nrm) - offset - bend * along * along;
    float center = mix(-1.6, 1.37, progress);
    float u = (along - center + LENGTH) / (2.0 * LENGTH);
    float shape = u < 0.7 ? pow(max(u, 0.0) / 0.7, 1.2) : pow(max(1.0 - u, 0.0) / 0.3, 0.6);
    float w = u > 0.0 && u < 1.0 ? width * shape * (1.0 - 0.45 * clamp(progress, 0.0, 1.0)) : 0.0;
    return vec2(step(abs(across), w), step(abs(across), LINE) * step(along, center + LENGTH));
}

// 刀痕到这一点的垂直距离（带正负：正的一侧是被错开的那块）
float scarAcross(vec2 p, vec4 s) {
    vec2 dir = vec2(cos(s.x), sin(s.x));
    float along = dot(p, dir);
    return dot(p, vec2(-dir.y, dir.x)) - s.y - s.z * along * along;
}

float scar(vec2 p, vec4 s, float width) {
    return step(abs(scarAcross(p, s)), width);
}

// 身体被斩开：每道刀痕正的一侧沿刀的方向错开一点，取剪影时从错开前的位置取
vec2 sliced(vec2 uv, vec2 p) {
    vec2 shift = vec2(0.0);
    for (int i = 0; i < 24; i++) {
        if (float(i) >= Extra.z) break;
        if (Scar[i].w != 0.0 && scarAcross(p, Scar[i]) > 0.0) shift += vec2(cos(Scar[i].x), sin(Scar[i].x)) * Scar[i].w;
    }
    return uv - shift / vec2(Target.w, 1.0);
}

// 受击火花：刀划过身体的那一点炸开一圈长短不一的尖刺
float spark(vec2 p, vec4 s) {
    vec2 d = p - s.xy;
    float r = length(d);
    if (r > s.z * 1.5) return 0.0;
    float a = atan(d.y, d.x) / TAU * 11.0 + hash(s.w * 3.7);
    float len = s.z * (0.5 + hash(floor(a) + s.w * 13.0));
    float tip = 1.0 - abs(fract(a) - 0.5) * 2.0;
    return step(r, max(len * pow(tip, 2.4), s.z * 0.22));
}

// 刀光、刀痕、火花：压在什么颜色上就是它的反色
float cuts(vec2 p) {
    float cover = 0.0;
    for (int i = 0; i < 4; i++) {
        if (Slash[i].w <= 0.0) continue;
        vec2 s = stroke(p, Slash[i].x, Slash[i].y, Slash[i].z, Slash[i].w, Bend[i].x);
        cover = max(cover, max(s.x, s.y));
    }
    for (int i = 0; i < 24; i++) {
        if (float(i) >= Extra.z) break;
        cover = max(cover, scar(p, Scar[i], LINE));
    }
    for (int i = 0; i < 4; i++) {
        if (float(i) >= Extra.w) break;
        cover = max(cover, spark(p, Spark[i]));
    }
    if (Look.w > 0.0) {
        vec2 s = stroke(p, Finisher.x, 0.0, Look.w, Finisher.z, Finisher.y);
        cover = max(cover, max(s.x, s.y));
    }
    return cover;
}

vec3 inkFrame(vec2 uv, vec2 texel, vec2 p) {
    vec2 b = body(sliced(uv, p), texel);
    float ink = mix(b.x, 0.0, b.y);
    ink = max(ink, speed(p, Target.z) * (1.0 - b.x));
    float cover = cuts(p);
    ink = mix(ink, 1.0 - ink, cover);
    if (Look.z > 0.5) ink = 1.0 - ink;
    if (State.y > 0.5) ink = 1.0 - ink;
    return mix(PAPER, SUMI, ink);
}

// 重击：背景变红，身体是黑的；之前所有的刀痕同时爆开成白线再收掉，重击那一刀是白的
vec3 redFrame(vec2 uv, vec2 texel, vec2 p) {
    vec2 b = body(sliced(uv, p), texel);
    vec3 col = mix(CRIMSON, SUMI, b.x);
    float lines = Finisher.w > 0.5 ? fall(uv, Target.w) : speed(p, Target.z);
    col = mix(col, DEEP, lines * (1.0 - b.x));
    float cover = 0.0;
    if (Look.y > 0.0) {
        float width = LINE + Look.y * 0.0045;
        for (int i = 0; i < 24; i++) {
            if (float(i) >= Extra.z) break;
            cover = max(cover, scar(p, Scar[i], width));
        }
    }
    if (Look.w > 0.0) cover = max(cover, stroke(p, Finisher.x, 0.0, Look.w, Finisher.z, Finisher.y).x);
    return mix(col, PAPER, cover);
}

// 落地那一拍：全白，身体黑，集中线收得更近
vec3 impactFrame(vec2 uv, vec2 texel, vec2 p) {
    vec2 b = body(uv, texel);
    float ink = max(b.x, speed(p, Target.z * 0.6) * (1.0 - b.x));
    return mix(PAPER, SUMI, ink);
}

vec3 frame(vec2 uv, vec2 texel) {
    vec2 p = (uv - Target.xy) * vec2(Target.w, 1.0);
    if (isMode(INK)) return inkFrame(uv, texel, p);
    if (isMode(RED)) return redFrame(uv, texel, p);
    if (isMode(IMPACT)) return impactFrame(uv, texel, p);
    // 抛起和刚落地：原画上压一层白色集中线
    vec3 c = texture(InSampler, uv).rgb;
    return mix(c, PAPER, speed(p, Target.z) * (1.0 - bodyAt(uv)) * 0.7);
}

void main() {
    vec2 texel = 1.0 / vec2(textureSize(DepthSampler, 0));
    vec3 col = frame(texCoord, texel);
    if (Look.x > 0.0) {
        col.r = frame(texCoord + vec2(Look.x, 0.0), texel).r;
        col.b = frame(texCoord - vec2(Look.x, 0.0), texel).b;
    }
    fragColor = vec4(col, 1.0);
}
