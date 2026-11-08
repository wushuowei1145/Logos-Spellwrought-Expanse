#version 330

// 后期里画的特效：贴图乘顶点颜色。方块的遮挡由深度测试处理；手挡在前面的不画，
// 除非这一批是画在手前面的（FRONT：第一人称杖头上的光）。

uniform sampler2D Sampler0;
uniform sampler2D HandDepthSampler;

layout(std140) uniform PostVfx {
    mat4 ViewProj;
    vec4 ScreenSize;
};

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor;
    if (color.a < 0.002) discard;
#ifndef FRONT
    if (texture(HandDepthSampler, gl_FragCoord.xy / ScreenSize.xy).r < 1.0) discard;
#endif
    fragColor = color;
}
