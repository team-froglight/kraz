#version 150

uniform sampler2D Sampler0;
uniform float Strength;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord);
    vec2 fromCenter = texCoord - 0.5;
    float falloff = smoothstep(0.25, 0.75, length(fromCenter));
    // Colour is premultiplied: darken rgb only so transparent areas stay transparent.
    fragColor = vec4(color.rgb * (1.0 - Strength * falloff), color.a);
}
