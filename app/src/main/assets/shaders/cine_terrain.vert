#version 300 es
// Topographic terrain under the disc. Height is computed here; the contours come from the fragment shader.
precision highp float;
layout(location = 0) in vec2 aXZ;
uniform mat4 uVP;
uniform float uT, uAmp, uShock, uLow;
uniform vec4 uTouch;   // x, z on the disc plane, strength 0..1, unused
out float vH;
out vec3 vW;
#include "cine_noise.glsl"
// slow noise + voice bumps + ripples from the centre + the finger's dent
float terrain(vec2 xz, float t, float amp, float shock, float low) {
  float r = length(xz);
  float h = fbm(vec3(xz * 0.9, t * 0.06)) * 0.16;
  h += (amp * 0.22 + 0.05 * (0.5 + 0.5 * sin(t * 0.6))) * exp(-r * r * 1.4) * (0.6 + 0.4 * sin(t * 9.0 * amp + r * 8.0));
  h += low * 0.10 * sin(r * 6.0 - t * 1.5) * exp(-r * 0.9);
  h += shock * 0.08 * sin(r * 18.0 - t * 14.0) * exp(-r * 1.2);
  float td = distance(xz, uTouch.xy);
  h += uTouch.z * (-0.12 * exp(-td * td * 10.0) + 0.03 * sin(td * 22.0 - t * 9.0) * exp(-td * td * 3.0));
  return h;
}
void main() {
  float h = terrain(aXZ, uT, uAmp, uShock, uLow);
  vec3 w = vec3(aXZ.x, h - 0.42, aXZ.y);
  vW = w;
  vH = h;
  gl_Position = uVP * vec4(w, 1.0);
}
