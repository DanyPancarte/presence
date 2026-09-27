#version 300 es
// Composite: tilt-shift depth of field, bloom, light leaks, listening glow, alert tint, vignette, tonemap, grain.
precision highp float;
in vec2 v;
uniform sampler2D uS, uB, uD;   // sharp scene, bloom (quarter res), out-of-focus copy (half res)
uniform float uGrain, uAmp, uAlert, uListen, uFocus, uHdr;   // uGrain: fract(t * 9) * 100, computed in double on the CPU
uniform vec2 uRes;
out vec4 o;
float hash(vec2 p) { vec3 p3 = fract(vec3(p.xyx) * 0.1031); p3 += dot(p3, p3.yzx + 33.33); return fract((p3.x + p3.y) * p3.z); }
void main() {
  vec2 cc = v - 0.5;
  float r = length(cc * vec2(uRes.x / uRes.y, 1.0));
  vec2 ca = cc * r * 0.010;   // a touch of chromatic aberration
  vec3 s;
  s.r = texture(uS, v + ca).r; s.g = texture(uS, v).g; s.b = texture(uS, v - ca).b;
  vec3 b = texture(uB, v).rgb;
  vec3 dfo = texture(uD, v).rgb;
  // tilt-shift: sharp band around the focus row, soft above and below
  float coc = smoothstep(0.04, 0.34, abs(v.y - uFocus));
  vec3 scene = mix(s, dfo, coc) * uHdr;
  vec3 bg = vec3(0.016, 0.022, 0.040);
  bg += vec3(0.16, 0.09, 0.03) * exp(-length((v - vec2(0.85, 1.05)) * vec2(1.0, 1.4)) * 2.2) * (0.7 + 0.5 * uAmp);   // warm leak, top right
  bg += vec3(0.02, 0.05, 0.10) * exp(-length(v - vec2(0.1, 0.0)) * 2.0);                                             // cool leak, bottom left
  bg = mix(bg, vec3(0.12, 0.02, 0.0), uAlert * 0.7);
  bg += vec3(0.0, 0.06, 0.06) * uListen * exp(-r * 2.0);
  vec3 c = bg + scene + b * uHdr * 1.1;
  c *= 1.0 - smoothstep(0.4, 1.0, r) * 0.65;
  c = c / (1.0 + c * 0.55);
  c = pow(c, vec3(0.9));
  c += (hash(v * uRes + uGrain) - 0.5) * 0.04;
  o = vec4(c, 1.0);
}
