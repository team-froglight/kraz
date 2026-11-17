#version 150

uniform sampler2D Sampler0;
uniform vec2 InSize;

in vec2 texCoord;

out vec4 fragColor;

const float REDUCE_MIN = 1.0 / 128.0;
const float REDUCE_MUL = 1.0 / 8.0;
const float SPAN_MAX = 8.0;

// The image is premultiplied and may be transparent, so alpha takes part in edge detection:
// otherwise dark geometry against an empty background would have no detectable silhouette.
float luma(vec4 c) {
    return dot(c.rgb, vec3(0.299, 0.587, 0.114)) + c.a * 0.25;
}

void main() {
    vec2 px = 1.0 / InSize;
    float lumaNW = luma(texture(Sampler0, texCoord + vec2(-1.0, -1.0) * px));
    float lumaNE = luma(texture(Sampler0, texCoord + vec2(1.0, -1.0) * px));
    float lumaSW = luma(texture(Sampler0, texCoord + vec2(-1.0, 1.0) * px));
    float lumaSE = luma(texture(Sampler0, texCoord + vec2(1.0, 1.0) * px));
    float lumaM = luma(texture(Sampler0, texCoord));

    float lumaMin = min(lumaM, min(min(lumaNW, lumaNE), min(lumaSW, lumaSE)));
    float lumaMax = max(lumaM, max(max(lumaNW, lumaNE), max(lumaSW, lumaSE)));

    vec2 dir = vec2(-((lumaNW + lumaNE) - (lumaSW + lumaSE)), (lumaNW + lumaSW) - (lumaNE + lumaSE));
    float dirReduce = max((lumaNW + lumaNE + lumaSW + lumaSE) * (0.25 * REDUCE_MUL), REDUCE_MIN);
    float rcpDirMin = 1.0 / (min(abs(dir.x), abs(dir.y)) + dirReduce);
    dir = clamp(dir * rcpDirMin, vec2(-SPAN_MAX), vec2(SPAN_MAX)) * px;

    vec4 narrow = 0.5 * (texture(Sampler0, texCoord + dir * (1.0 / 3.0 - 0.5))
                       + texture(Sampler0, texCoord + dir * (2.0 / 3.0 - 0.5)));
    vec4 wide = narrow * 0.5 + 0.25 * (texture(Sampler0, texCoord + dir * -0.5)
                                     + texture(Sampler0, texCoord + dir * 0.5));
    float lumaWide = luma(wide);
    fragColor = (lumaWide < lumaMin || lumaWide > lumaMax) ? narrow : wide;
}
