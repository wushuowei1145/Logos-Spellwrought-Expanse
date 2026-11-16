#version 330

// 世界球体着色（WorldTintScreen）：按世界坐标的球给已经画好的画面染腐、净化、显露或推一道去饱和波。
// 噪声全部按世界坐标取（EyeWrapped 折返），不贴屏幕空间纹理。
uniform sampler2D InSampler;
uniform sampler2D DepthSampler;
layout(std140) uniform WorldTint {
    mat4 InverseViewProjection;
    vec4 EyeWrapped;    // 镜头的世界坐标折返到 4096 以内
    vec4 Globals;       // 游戏刻、深度区间、隐藏闪光、球数
    vec4 EndingParams;  // 结局的收缩、眩光、是否在结局
    vec4 Focus;         // 结局眩光中心（相对镜头）
    vec4 Spheres[16];   // 每个球两项：中心（相对镜头）+ 半径；模式、强度、宽度
};
in vec2 texCoord;
out vec4 fragColor;

float tintHash(vec3 p) {
    p = mod(p, 512.);
    p = fract(p * vec3(.1031, .11369, .13787));
    p += dot(p, p.yzx + 19.19);
    return fract((p.x + p.y) * p.z);
}
float tintValue(vec3 p) {
    vec3 i = floor(p), f = fract(p); f = f*f*(3.-2.*f);
    return mix(mix(mix(tintHash(i),tintHash(i+vec3(1,0,0)),f.x),mix(tintHash(i+vec3(0,1,0)),tintHash(i+vec3(1,1,0)),f.x),f.y),
               mix(mix(tintHash(i+vec3(0,0,1)),tintHash(i+vec3(1,0,1)),f.x),mix(tintHash(i+vec3(0,1,1)),tintHash(i+vec3(1,1,1)),f.x),f.y),f.z);
}
float luma(vec3 c) { return dot(c, vec3(.2126, .7152, .0722)); }
vec3 tainted(vec3 color) { return mix(color * .32, vec3(.34, .055, .075) * (.5 + luma(color)), .72); }

void main() {
    vec3 color = texture(InSampler, texCoord).rgb;
    float depth = texture(DepthSampler, texCoord).r;
    vec4 p = InverseViewProjection * vec4(texCoord * 2. - 1., Globals.y > .5 ? depth : depth * 2. - 1., 1.);
    vec3 relative = p.xyz / p.w;
    vec3 world = relative + EyeWrapped.xyz;
    float grain = tintValue(world * .125) * 6. + tintValue(world * .5) * 1.8 - 3.9;
    int count = int(Globals.w + .5);
    if (depth < .99999) {
        for (int i = 0; i < 8; i++) {
            if (i >= count) break;
            vec4 cr = Spheres[i * 2];
            vec4 info = Spheres[i * 2 + 1];
            int mode = int(info.x + .5);
            float strength = info.y, width = info.z;
            float d = length(relative - cr.xyz);
            if (mode == 0) {
                // 染腐：球内，边缘带世界噪声。
                float inside = 1. - smoothstep(-2., 2., d - cr.w + grain * .6);
                color = mix(color, tainted(color), inside * strength);
                color += vec3(.5, .06, .09) * exp(-abs(d - cr.w + grain * .6) * .5) * strength * .25;
            } else if (mode == 1) {
                // 净化：球内略提亮偏暖绿，边缘一圈淡绿光。
                float sd = d - cr.w + grain * .25;
                float inside = 1. - smoothstep(-1., 1., sd);
                color = mix(color, color * vec3(1.04, 1.1, 1.0) + vec3(.015, .035, .01), inside * strength * .6);
                color += vec3(.55, 1., .5) * exp(-abs(sd) * .9) * strength * .3;
            } else if (mode == 2) {
                // 显露：球外染腐（到 width 为止），球内保持原样；边缘一圈净化光。
                float sd = d - cr.w + grain;
                float outside = smoothstep(-2., 2., sd) * (1. - smoothstep(width - 8., width + 8., d));
                color = mix(color, tainted(color), outside * strength);
                float rim = exp(-abs(sd) * .24) * info.w;
                color += vec3(.65, 1., .55) * rim * .7;
                color += vec3(1., .78, .39) * pow(rim, 3.) * .32;
            } else if (mode == 3) {
                // 搏动：半径处一圈很淡的去饱和波。
                float band = exp(-pow((d - cr.w) / max(.5, width), 2.));
                color = mix(color, vec3(luma(color)) * .92, band * strength);
            }
        }
    }
    if (EndingParams.z > .5) {
        // 结局：一次局部收缩再展开，避免满屏黑白交替；隐藏闪光时眩光减弱（Java 里已乘）。
        vec4 c = InverseViewProjection * vec4(texCoord * 2. - 1., 1., 1.);
        vec3 ray = normalize(c.xyz / c.w);
        float focus = pow(max(0., dot(ray, normalize(Focus.xyz))), 48.);
        color = color * (1. - EndingParams.x * .16) + focus * EndingParams.y * vec3(1., .78, .56);
    }
    fragColor = vec4(color, 1.);
}
