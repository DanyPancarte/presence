#version 300 es
precision highp float;
layout(location = 0) in vec4 aPos;
layout(location = 1) in vec4 aVel;
uniform mat4 uProj;
uniform float uPx, uT, uAlert, uAmp;
uniform vec2 uParallax;
uniform vec3 uAccent;   // yellow (or amber) accent particles
out vec3 vCol;
out float vA;
float hash(float n) { return fract(sin(n) * 43758.5453123); }
void main() {
    vec3 p = aPos.xyz;
    vec3 pv = vec3(p.x + p.z * uParallax.x * 0.6, p.y + p.z * uParallax.y * 0.6, p.z - 3.2);
    gl_Position = uProj * vec4(pv, 1.0);
    float depth = clamp((p.z + 1.0) * 0.5, 0.0, 1.0);
    float seed = aVel.w;
    float flick = 0.7 + 0.3 * sin(uT * (3.0 + hash(seed) * 9.0) + seed * 7.0);
    float size = (1.0 + 1.2 * depth + hash(seed * 1.3) * 0.9) * uPx;
    size *= 1.0 + aPos.w * 0.8 + uAmp * 0.6;
    gl_PointSize = size;
    vec3 cyan = vec3(0.0, 0.94, 1.0), red = vec3(1.0, 0.0, 0.24);
    vec3 c = mix(cyan, uAccent, step(0.86, hash(seed * 5.1)));
    c = mix(c, red, uAlert);
    vCol = c;
    vA = (0.25 + 0.75 * depth) * flick * (0.6 + 0.4 * aPos.w);
}
