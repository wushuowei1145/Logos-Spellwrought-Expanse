#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:sample_lightmap.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;
uniform sampler2D Sampler2;
out vec4 vertexColor;
out vec2 texCoord0;
out float sphericalVertexDistance;
out float cylindricalVertexDistance;

void main() {
    // 两字节相位来自整株根点；世界坐标不随镜头移动，且远坐标已在双精度下取模。
    int code = UV1.x & 65535;
    int phases = UV1.y & 65535;
    float angle = float(code & 255) * 6.28318530718 / 255.0;
    float strength = float((code >> 8) & 255) / 255.0;
    vec2 direction = vec2(cos(angle), sin(angle));
    vec2 across = vec2(-direction.y, direction.x);
    float h = clamp(Color.a, 0.0, 1.0);
    float longPhase = float(phases & 255) * 6.28318530718 / 255.0;
    float finePhase = float((phases >> 8) & 255) * 6.28318530718 / 255.0;
    // 整数周数使原版 GameTime 每日回绕时首尾相接；暂停时也不继续晃动。
    float timeAngle = GameTime * 6.28318530718;
    float wave = sin(longPhase - timeAngle * 176.0);
    float ripple = sin(finePhase - timeAngle * 533.0);
    float bend = strength * h * h * (.17 + .105 * wave + .025 * ripple);
    vec2 shift = direction * bend + across * (strength * h * h * .022 * sin(finePhase + timeAngle * 389.0));
    vec3 pos = Position + vec3(shift.x, -length(shift) * .16, shift.y);
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);
    vertexColor = vec4(Color.rgb, 1.0) * sample_lightmap(Sampler2, UV2);
    texCoord0 = UV0;
}
