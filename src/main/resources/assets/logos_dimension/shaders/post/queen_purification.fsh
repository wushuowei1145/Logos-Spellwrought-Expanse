#version 330

uniform sampler2D InSampler;
uniform sampler2D DepthSampler;
layout(std140) uniform Purification {
    mat4 InverseViewProjection;
    vec4 CenterRadius; // 镜头相对球心和净化半径
    vec4 Timing;       // 游戏刻、深度区间、隐藏闪光、最终半径
    vec4 EyeWrapped;   // 世界坐标折返，噪声随世界移动
};
in vec2 texCoord;
out vec4 fragColor;

float fieldHash(vec3 p) {
    p = mod(p, 512.);
    p = fract(p * vec3(.1031, .11369, .13787));
    p += dot(p, p.yzx + 19.19);
    return fract((p.x + p.y) * p.z);
}
float fieldValue(vec3 p) {
    vec3 i = floor(p), f = fract(p); f = f*f*(3.-2.*f);
    return mix(mix(mix(fieldHash(i),fieldHash(i+vec3(1,0,0)),f.x),mix(fieldHash(i+vec3(0,1,0)),fieldHash(i+vec3(1,1,0)),f.x),f.y),
               mix(mix(fieldHash(i+vec3(0,0,1)),fieldHash(i+vec3(1,0,1)),f.x),mix(fieldHash(i+vec3(0,1,1)),fieldHash(i+vec3(1,1,1)),f.x),f.y),f.z);
}
void main() {
    vec3 color = texture(InSampler, texCoord).rgb;
    float depth = texture(DepthSampler, texCoord).r;
    float age = Timing.x;
    vec4 p = InverseViewProjection * vec4(texCoord*2.-1., Timing.y > .5 ? depth : depth*2.-1., 1.);
    vec3 relative = p.xyz / p.w;
    vec3 toCenter = relative - CenterRadius.xyz;
    float distanceToCenter = length(toCenter);
    vec3 world = relative + EyeWrapped.xyz;
    float noise = fieldValue(world*.125) * 6. + fieldValue(world*.5) * 1.8 - 3.9;
    float signedDistance = distanceToCenter - CenterRadius.w + noise;
    float endFade = 1. - smoothstep(270., 310., age);
    if (depth < .99999 && age >= 80.) {
        // 服务端已还原的材质在波外暂染腐败色，不需要第二台相机或重画世界。
        float outside = smoothstep(-2., 2., signedDistance) * (1.-smoothstep(Timing.w-8.,Timing.w+8.,distanceToCenter));
        float lum = dot(color, vec3(.2126,.7152,.0722));
        vec3 tainted = mix(color*.32, vec3(.34,.055,.075)*(.5+lum), .72);
        color = mix(color, tainted, outside * endFade);
        float rim = exp(-abs(signedDistance)*.24) * smoothstep(98.,110.,age) * endFade;
        color += vec3(.65,1.,.55) * rim * .7;
        color += vec3(1.,.78,.39) * pow(rim,3.) * .32;
    }
    // 一次局部收缩再展开，避免满屏黑白交替；关闭闪烁时继续保留温和净化。
    float collapse = exp(-pow((age-72.)/12.,2.));
    float glare = exp(-pow((age-84.)/7.,2.)) * (Timing.z > .5 ? .12 : .55);
    vec4 c = InverseViewProjection * vec4(texCoord*2.-1., Timing.y > .5 ? 1. : 1.,1.);
    vec3 ray = normalize(c.xyz/c.w);
    float focus = pow(max(0.,dot(ray,normalize(CenterRadius.xyz))),48.);
    color = color * (1.-collapse*.16) + focus * glare * vec3(1.,.78,.56);
    fragColor = vec4(color,1.);
}
