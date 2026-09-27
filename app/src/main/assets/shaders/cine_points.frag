#version 300 es
// A soft round sprite, additive.
precision mediump float;
in vec3 vC;
in float vA;
uniform float uGain;   // 1 on HDR targets, < 1 on RGBA8 (the composite multiplies back)
out vec4 o;
void main() {
  vec2 d = gl_PointCoord - 0.5;
  float r = dot(d, d) * 4.0;
  if (r > 1.0) discard;
  o = vec4(vC * exp(-r * 2.5) * vA * uGain, 1.0);
}
