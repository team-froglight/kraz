#version 150

in vec3 Position;
in vec2 UV0;

out vec2 texCoord;

void main() {
    // Post passes draw a quad that is already in clip space; no matrices involved.
    gl_Position = vec4(Position.xy, 0.0, 1.0);
    texCoord = UV0;
}
