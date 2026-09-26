#version 300 es
precision mediump float;
in vec3 vCol;
in float vA;
out vec4 o;
void main() {
    vec2 d = gl_PointCoord - 0.5;
    float r = dot(d, d) * 4.0;
    if (r > 1.0) discard;
    o = vec4(vCol * exp(-r * 3.0) * vA * 0.22, 1.0);
}
