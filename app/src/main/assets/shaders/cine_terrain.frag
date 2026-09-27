#version 300 es
// Contour lines from the height derivative; gold to ember with height and voice; radial fade and fog.
precision highp float;
in float vH;
in vec3 vW;
uniform float uT, uAmp, uHeat;
uniform float uGain;   // 1 on HDR targets, < 1 on RGBA8 (the composite multiplies back)
uniform vec3 uCam;
out vec4 o;
void main() {
  float lv = vH * 26.0;
  float f = abs(fract(lv + 0.5) - 0.5) / max(fwidth(lv), 1e-4);
  float line = 1.0 - clamp(f - 0.25, 0.0, 1.0);
  float major = 1.0 - clamp(abs(fract(lv / 4.0 + 0.5) - 0.5) / max(fwidth(lv / 4.0), 1e-4) - 0.35, 0.0, 1.0);
  float r = length(vW.xz);
  float fade = exp(-r * r * 0.55);
  float d = distance(vW, uCam);
  float fog = exp(-d * 0.22);
  vec3 gold = vec3(1.0, 0.68, 0.30), ember = vec3(1.0, 0.36, 0.10);
  vec3 col = mix(gold, ember, clamp(vH * 3.0 + uAmp * 0.6, 0.0, 1.0));
  float I = (line * 0.6 + major * 1.0) * fade * fog * (0.55 + 0.7 * uAmp + 0.3 * uHeat);
  I += 0.012 * fade * fog;   // surface veil
  o = vec4(col * I * uGain, 1.0);
}
