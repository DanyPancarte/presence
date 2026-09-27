#version 300 es
// The disc: six rings of ticks, a core, and eight ribbons that flow through it. One point each.
precision highp float;
layout(location = 0) in vec4 aA;   // angle, radius (core: disc factor 0..1), y0, seed
layout(location = 1) in vec4 aB;   // ring 0..5, 6 = core, 9 = ribbon; u; kind (1 = tick mark); ribbon index
uniform mat4 uVP;
uniform float uT, uAmp, uPitch, uHeat, uShock, uListen, uPx;
uniform float uSpin;      // ring rotation phase, integrates (1 + listen * 0.8): listening spins the disc faster with no jump
uniform float uFlow;      // ribbon flow phase, integrates (1 + amp * 2)
uniform float uBands[8];
uniform vec3 uCam;
uniform vec4 uSeg[8];     // tasks on ring 2: start angle, length, progress, 97 flag
uniform int uSegN;
uniform float uMed;       // ring 1: 1 = today's med taken (closed), 0 = open with a pulsing ember gap
uniform float uMargin;    // budget margin 0..1: radius of the core
uniform float uVoice;     // the voice is playing (already pulsing on the CPU)
uniform float uEvents;    // 0..1: the week's events (ring 4 brightness)
uniform vec4 uTouch;      // x, z on the disc plane, strength 0..1, unused
out vec3 vC;
out float vA;
#include "cine_noise.glsl"
mat3 rotX(float a) { float c = cos(a), s = sin(a); return mat3(1, 0, 0, 0, c, s, 0, -s, c); }
mat3 rotZ(float a) { float c = cos(a), s = sin(a); return mat3(c, s, 0, -s, c, 0, 0, 0, 1); }
const float TAU = 6.2831853;
const float PI = 3.14159265;
void main() {
  float ring = aB.x;
  float seed = aA.w;
  float kind = aB.z;
  vec3 p;
  vec3 col = vec3(0.95, 0.72, 0.38);
  float I = 1.0;
  if (ring < 8.5) {
    int ri = int(ring);
    float band = uBands[ri];
    // the core is a disc whose radius is the budget margin
    float base = ri == 6 ? aA.y * (0.05 + 0.22 * uMargin) : aA.y;
    float ang = aA.x + uSpin * (0.02 + 0.012 * ring);
    float rad = base * (1.0 + band * 0.10 + uShock * 0.05 * sin(aA.y * 30.0 - uT * 12.0)) * (1.0 + uVoice * 0.025);
    float y = aA.z + 0.02 * snoise(vec3(cos(ang) * 2.0, sin(ang) * 2.0, uT * 0.3 + ring)) + band * 0.06 * sin(ang * 6.0 + uT * 4.0);
    p = vec3(cos(ang) * rad, y, sin(ang) * rad);
    // the disc tilts with the pitch of the voice, and wobbles slowly
    p = rotX(0.12 * sin(uT * 0.21)) * rotZ(uPitch * 0.35 + 0.08 * sin(uT * 0.17)) * p;
    if (ri == 1) {
      // meds: a gap at 90 degrees that closes when the med is taken; its lips burn ember while open
      float open = 1.0 - uMed;
      float rel = abs(mod(aA.x - 1.5707963 + PI, TAU) - PI);
      float hw = 0.45 * open;
      float on = min(open * 4.0, 1.0);
      float gap = (1.0 - smoothstep(hw - 0.03, hw + 0.03, rel)) * on;
      float edge = exp(-pow((rel - hw) * 14.0, 2.0)) * on * (0.55 + 0.45 * sin(uT * 3.0));
      I *= 1.0 - gap * 0.92;
      col = mix(col, vec3(1.0, 0.42, 0.14), edge);
      I *= 1.0 + edge * 2.0;
    }
    if (ri == 2) {
      // task segments: brighter arcs up to their progress; a 97 % burns ember and has a hole
      float a = mod(aA.x, TAU);
      float inSeg = 0.0, hot = 0.0, hole = 0.0;
      for (int k = 0; k < 8; k++) {
        if (k >= uSegN) break;
        vec4 s = uSeg[k];
        float rel = mod(a - s.x + TAU, TAU);
        if (rel < s.y) {
          if (rel < s.y * s.z) inSeg = 1.0;
          if (s.w > 0.5) { hot = 1.0; if (rel > s.y * s.z - 0.05 && rel < s.y * s.z + 0.03) hole = 1.0; }
        }
      }
      I *= 0.35 + 0.9 * inSeg;
      col = mix(col, vec3(1.0, 0.42, 0.14), hot);
      I *= 1.0 - hole * 0.95;
      I *= 1.0 + hot * 0.6;
    }
    if (ri == 4) I *= 0.3 + 0.7 * uEvents;
    if (ri == 6) {
      I *= 0.35 + 1.2 * uVoice;
      col = mix(col, vec3(1.0, 0.95, 0.85), uVoice * 0.5);
    }
    if (kind > 0.5) { I *= 1.8; col = mix(col, vec3(1.0, 1.0, 0.92), 0.5); }   // tick marks
    I *= 0.55 + 0.45 * sin(uT * (1.0 + fract(seed * 7.0) * 4.0) + seed * 50.0);
    I *= 1.0 + band * 1.6;
  } else {
    // ribbons: spiralling streams that flow, breathing with the low band
    float k = aB.w;
    float u = fract(aB.y + uFlow * (0.03 + 0.02 * fract(k * 0.37)));
    float ang = u * TAU * 1.6 + k * 1.1 + uT * 0.05;
    float rad = 0.25 + u * 1.15;
    float y = -0.1 + 0.35 * u + 0.10 * sin(u * 14.0 + uT * 1.3 + k) + uBands[0] * 0.25 * sin(u * 20.0 - uT * 6.0);
    p = vec3(cos(ang) * rad, y, sin(ang) * rad);
    p.xz += 0.03 * vec2(snoise(vec3(p.xz * 3.0, uT * 0.4 + k)), snoise(vec3(p.zx * 3.0, -uT * 0.4 + k)));
    col = mix(vec3(0.95, 0.75, 0.42), vec3(0.45, 0.85, 0.80), fract(k * 0.618) * 0.6);
    I = (0.25 + 0.75 * pow(sin(u * PI), 2.0)) * (0.5 + 0.5 * sin(uT * 3.0 + seed * 40.0)) * (0.7 + uAmp);
  }
  // the finger pushes the points away and lights them
  vec2 dxz = p.xz - uTouch.xy;
  float dd = length(dxz);
  float push = uTouch.z * exp(-dd * dd * 8.0);
  p.xz += dxz / max(dd, 1e-3) * push * 0.10;
  p.y += push * 0.04;
  I *= 1.0 + push * 1.5;

  float d = distance(p, uCam);
  gl_Position = uVP * vec4(p, 1.0);
  float size = (1.4 + 1.4 * fract(seed * 3.3)) * uPx / max(d * 0.5, 0.3);
  gl_PointSize = min(size * (1.0 + uHeat * 0.5), 7.0 * uPx);
  float fog = exp(-d * 0.20);
  vC = col;
  vA = I * fog * 1.25;
}
