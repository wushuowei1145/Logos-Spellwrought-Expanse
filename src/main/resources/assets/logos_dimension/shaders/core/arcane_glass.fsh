#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>

uniform sampler2D Sampler0;
in vec2 texCoord0;
in vec4 vertexColor;
in vec3 viewPosition;
in vec3 viewNormal;
out vec4 fragColor;

void main() {
    vec2 p = texCoord0 * 2.0 - 1.0;
    float r = length(p);
    float time = GameTime * 1200.0;
    vec3 n = normalize(viewNormal);
    vec3 eye = normalize(-viewPosition);
    float fresnel = pow(1.0 - abs(dot(n, eye)), 3.0);
    vec3 light = normalize(vec3(0.4 + sin(time * 0.18) * 0.25, -0.65, 0.5));
    float specular = pow(max(0.0, abs(dot(reflect(-light, n), eye))), 38.0);
    // A wide glint and a narrow white reflection travel together across the glass.
    vec2 axis = vec2(cos(time * 0.13), sin(time * 0.13));
    float band = dot(p, axis) - sin(time * 0.19) * 0.72;
    float broad = exp(-band * band * 45.0);
    float glintWidth = max(0.025, fwidth(band) * 1.25);
    float sharp = exp(-band * band / (glintWidth * glintWidth));
    float edge = min(abs(r-1.0), min(abs(r-0.745), min(abs(r-0.733), min(abs(r-0.316),abs(r-0.324)))));
    float edgeAA = max(fwidth(r), 0.001);
    float bevel = 1.0 - smoothstep(max(0.0,0.008-edgeAA), 0.008+edgeAA, edge);
    float angular = atan(p.y,p.x);
    vec3 prism = 0.5 + 0.5*cos(angular*2.0 + time*0.2 + vec3(0.0,2.1,4.2));
    vec3 color = vertexColor.rgb * 0.70 + vec3(0.16,0.23,0.27);
    color += broad*vec3(0.12,0.18,0.21) + sharp*0.52 + specular*0.28;
    color += bevel*(vec3(0.18,0.24,0.27) + prism*0.14);
    float alpha = (0.07 + fresnel*0.17 + broad*0.10 + sharp*0.25 + bevel*0.34 + specular*0.13) * vertexColor.a;
    fragColor = vec4(color, min(alpha,0.70)) * ColorModulator * texture(Sampler0,vec2(0.5));
}
