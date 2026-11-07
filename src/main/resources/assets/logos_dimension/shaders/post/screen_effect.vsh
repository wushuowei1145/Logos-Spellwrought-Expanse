#version 330

uniform sampler2D InSampler;

out vec2 texCoord;
flat out float SceneLum;

// 整幅画面的平均明度。放在顶点着色器里每帧只算三次，黑白闪的明暗分界按它来定，
// 夜晚和山洞才不会变成一片全黑，开光影时也不用猜光影包把画面调成了多亮。
const int GRID = 8;

void main() {
    vec2 uv = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    gl_Position = vec4(uv * vec2(2, 2) + vec2(-1, -1), 0, 1);
    texCoord = uv;

    float sum = 0.0;
    for (int y = 0; y < GRID; y++) {
        for (int x = 0; x < GRID; x++) {
            vec3 c = textureLod(InSampler, (vec2(x, y) + 0.5) / float(GRID), 0.0).rgb;
            sum += dot(c, vec3(0.2126, 0.7152, 0.0722));
        }
    }
    SceneLum = sum / float(GRID * GRID);
}
