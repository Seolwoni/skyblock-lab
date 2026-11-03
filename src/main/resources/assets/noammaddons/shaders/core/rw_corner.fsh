#version 330

// Riftwalk: anti-aliased rounded corners.
// cornerPos is 0 at the circle's centre and 1 on its edge, so the edge is smoothed over one screen pixel
// whatever the radius or GUI scale is.
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

in vec2 cornerPos;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    float d = length(cornerPos);
    float w = max(fwidth(d), 0.0001);
    float a = 1.0 - smoothstep(1.0 - w, 1.0 + w * 0.5, d);
    vec4 color = vertexColor * ColorModulator;
    color.a *= a;
    if (color.a <= 0.002) {
        discard;
    }
    fragColor = color;
}
