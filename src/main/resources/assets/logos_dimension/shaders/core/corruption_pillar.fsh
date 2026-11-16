#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>
// 腐败光柱：Logos 虚空光束（logos:core/void_beam）的腐败版。层次和动法照搬，颜色换成腐败的紫红；
// 原来的内芯按屏幕坐标投星空，这里改成按模型 UV 取，跟着光柱走，不贴在屏幕上。
uniform sampler2D Sampler0;
uniform sampler2D NoiseTex;
uniform sampler2D WarpTex;
uniform sampler2D StarTex;
uniform sampler2D AuraTex;
in vec4 vertexColor;
in vec2 texCoord0;
in vec3 viewPosition;
out vec4 fragColor;

vec3 sparks(vec2 uv, float seconds) {
    vec3 field = vec3(0);
    for (int i = 0; i < 4; i++) {
        float layer = float(i + 1), angle = layer * layer * 1.73;
        mat2 rotate = mat2(cos(angle), -sin(angle), sin(angle), cos(angle));
        vec2 at = rotate * uv * (.8 + layer * .35);
        at += vec2(layer * .713, -seconds * (.06 + layer * .03));
        vec3 sampleColor = texture(StarTex, at).rgb;
        float spark = smoothstep(.2, .8, max(sampleColor.r, max(sampleColor.g, sampleColor.b)));
        field += spark * mix(vec3(.85, .12, .38), vec3(1, .72, .84), layer / 4) * .7;
    }
    return field;
}

void main() {
    float seconds = GameTime * 1200;
    vec2 uv = texCoord0;
    vec3 tint = vertexColor.rgb;
    vec3 normal = normalize(cross(dFdx(viewPosition), dFdy(viewPosition)));
    float grazing = 1 - abs(dot(normal, normalize(-viewPosition)));
    float rim = pow(grazing, 5.357019);
    float warp = texture(WarpTex, uv + vec2(0, -seconds * .5)).r - .5;
    vec2 warped = uv + vec2(warp * .2, 0);
    float noise = texture(NoiseTex, uv + vec2(0, -seconds)).r;
    vec3 color;
    float alpha;
#if defined(VOID_CORE)
    vec2 along = uv * vec2(2, 6);
    vec3 field = sparks(along, seconds);
    float nebula = texture(AuraTex, along * .25 + vec2(seconds * .02, -seconds * .05)).r;
    float surfaceNoise = texture(Sampler0, uv + vec2(0, -seconds * .1)).r;
    color = vec3(.07, .005, .03) + tint * (nebula * .16 + surfaceNoise * .02) + field * 1.4;
    color += tint * rim * 1.9;
    alpha = .96;
#elif defined(VOID_PATCHES)
    vec2 moving = uv * vec2(.7, 1.4) + vec2(seconds * .24, -seconds * .75);
    float a = texture(Sampler0, moving + vec2(uv.y * 1.7, 0)).r;
    float b = texture(Sampler0, moving * vec2(1.7, .65) + vec2(.37, .61)).r;
    float mask = a * .7 + b * .3;
    if (mask < .64) discard;
    alpha = 1;
    color = mix(vec3(.2, .02, .08), vec3(.34, .05, .14), smoothstep(.64, .9, mask));
#elif defined(VOID_SOLID)
    float solid = texture(Sampler0, uv + vec2(seconds * .24, -seconds * .4)).r;
    float veins = smoothstep(.62, .9, solid);
    color = mix(tint * .65, vec3(1, .45, .68), veins * .45);
    alpha = veins * .65;
#elif defined(VOID_RIBBON)
    float a = texture(Sampler0, uv * vec2(1, 1.4) - vec2(seconds * .2, 0)).r;
    float b = texture(Sampler0, uv * vec2(1.3, 2) - vec2(seconds * .2, .07)).r;
    float edge = abs(uv.y * 2 - 1);
    float mask = smoothstep(.15, .55, edge) * (1 - smoothstep(.65, 1, edge));
    alpha = smoothstep(.12, .55, a * b) * mask * .6;
    color = tint * 1.05;
#else
#ifdef VOID_INNER_FLOW
    float speed = 1.4;
#else
    float speed = 2;
#endif
    float tex = texture(Sampler0, warped + vec2(0, -seconds * speed)).r;
    float energy = max(0, tex - noise * .2);
    alpha = smoothstep(.15, .75, energy) * .72;
    color = mix(tint, vec3(1, .42, .66), energy * .65);
#endif
    fragColor = vec4(color, alpha * vertexColor.a) * ColorModulator;
    if (fragColor.a < .005) discard;
}
