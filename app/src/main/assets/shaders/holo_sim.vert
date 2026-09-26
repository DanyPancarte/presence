#version 300 es
// Hologram particles chase a target formation per state (transform feedback).
// Formations: 0 FIELD (veille), 1 SPECTRUM (écoute), 2 STREAM (réflexion), 3 WAVE (réponse), 4 CORE (alerte).
// The box is the screen: x ∈ [-aspect, aspect], y ∈ [-1, 1], z ∈ [-1, 1].
precision highp float;
layout(location = 0) in vec4 aPos;   // xyz, glow
layout(location = 1) in vec4 aVel;   // xyz, seed
out vec4 oPos;
out vec4 oVel;
uniform float uT, uDt, uAspect, uBlend, uEnergy, uAmp, uGlitch, uJitter;
uniform int uA, uB;
uniform float uBands[24];
uniform vec2 uTouch;

float hash(float n) { return fract(sin(n) * 43758.5453123); }
vec3 hash3(float n) { return vec3(hash(n), hash(n + 17.13), hash(n + 41.71)); }
vec3 mod289(vec3 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
vec4 mod289(vec4 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
vec4 permute(vec4 x) { return mod289(((x * 34.0) + 1.0) * x); }
vec4 taylorInvSqrt(vec4 r) { return 1.79284291400159 - 0.85373472095314 * r; }

float snoise(vec3 v) {
    const vec2 C = vec2(1.0 / 6.0, 1.0 / 3.0);
    const vec4 D = vec4(0.0, 0.5, 1.0, 2.0);
    vec3 i = floor(v + dot(v, C.yyy));
    vec3 x0 = v - i + dot(i, C.xxx);
    vec3 g = step(x0.yzx, x0.xyz);
    vec3 l = 1.0 - g;
    vec3 i1 = min(g.xyz, l.zxy);
    vec3 i2 = max(g.xyz, l.zxy);
    vec3 x1 = x0 - i1 + C.xxx;
    vec3 x2 = x0 - i2 + C.yyy;
    vec3 x3 = x0 - D.yyy;
    i = mod289(i);
    vec4 p = permute(permute(permute(
        i.z + vec4(0.0, i1.z, i2.z, 1.0)) + i.y + vec4(0.0, i1.y, i2.y, 1.0)) + i.x + vec4(0.0, i1.x, i2.x, 1.0));
    float n_ = 0.142857142857;
    vec3 ns = n_ * D.wyz - D.xzx;
    vec4 j = p - 49.0 * floor(p * ns.z * ns.z);
    vec4 x_ = floor(j * ns.z);
    vec4 y_ = floor(j - 7.0 * x_);
    vec4 x = x_ * ns.x + ns.yyyy;
    vec4 y = y_ * ns.x + ns.yyyy;
    vec4 h = 1.0 - abs(x) - abs(y);
    vec4 b0 = vec4(x.xy, y.xy);
    vec4 b1 = vec4(x.zw, y.zw);
    vec4 s0 = floor(b0) * 2.0 + 1.0;
    vec4 s1 = floor(b1) * 2.0 + 1.0;
    vec4 sh = -step(h, vec4(0.0));
    vec4 a0 = b0.xzyw + s0.xzyw * sh.xxyy;
    vec4 a1 = b1.xzyw + s1.xzyw * sh.zzww;
    vec3 p0 = vec3(a0.xy, h.x);
    vec3 p1 = vec3(a0.zw, h.y);
    vec3 p2 = vec3(a1.xy, h.z);
    vec3 p3 = vec3(a1.zw, h.w);
    vec4 norm = taylorInvSqrt(vec4(dot(p0, p0), dot(p1, p1), dot(p2, p2), dot(p3, p3)));
    p0 *= norm.x; p1 *= norm.y; p2 *= norm.z; p3 *= norm.w;
    vec4 m = max(0.6 - vec4(dot(x0, x0), dot(x1, x1), dot(x2, x2), dot(x3, x3)), 0.0);
    m = m * m;
    return 42.0 * dot(m * m, vec4(dot(p0, x0), dot(p1, x1), dot(p2, x2), dot(p3, x3)));
}

// Torus knot (2,3): the holographic core object.
vec3 knot(float u, float r2) {
    float p = 2.0, q = 3.0;
    float R = 0.22 + 0.11 * cos(q * u);
    return vec3(R * cos(p * u), R * sin(p * u), 0.11 * sin(q * u)) * (1.0 + r2 * 0.35);
}
mat3 rotY(float a) { float c = cos(a), s = sin(a); return mat3(c, 0, -s, 0, 1, 0, s, 0, c); }
mat3 rotX(float a) { float c = cos(a), s = sin(a); return mat3(1, 0, 0, 0, c, s, 0, -s, c); }

vec3 shape(int k, float i, vec3 h, float t) {
    float A = uAspect;
    if (k == 0) { // FIELD: dust filling the box + core knot (30%)
        if (h.z < 0.3) {
            vec3 c = knot(h.x * 6.2831853 + t * 0.15, h.y);
            return rotX(0.5 + 0.2 * sin(t * 0.13)) * rotY(t * 0.35) * c;
        }
        float y = fract(h.y + t * 0.012 * (0.5 + h.z));
        if (h.z > 0.85) return vec3((h.x * 2.0 - 1.0) * A * 1.2, y * 2.4 - 1.2, -1.0);
        return vec3((h.x * 2.0 - 1.0) * A, y * 2.0 - 1.0, (h.z * 2.0 - 1.0) * 0.9);
    }
    if (k == 1) { // SPECTRUM: 24 bars in perspective rows, like a radio EQ
        float bar = floor(h.x * 24.0);
        float lvl = uBands[int(bar)];
        float x = (bar + 0.5) / 24.0 * 2.0 - 1.0;
        float row = floor(h.z * 3.0);
        float yy = -0.55 + h.y * (0.08 + lvl * 0.9);
        return vec3(x * A * 0.92 + (fract(h.x * 24.0) - 0.5) * 0.055 * A, yy, -0.2 + row * 0.35 + (h.z * 3.0 - row) * 0.1);
    }
    if (k == 2) { // STREAM: horizontal data lines, fast, layered in depth
        float lane = floor(h.y * 40.0);
        float sp = 0.6 + hash(lane * 3.7) * 1.4;
        float x = fract(h.x + t * sp * 0.35 * uEnergy) * 2.0 - 1.0;
        float y = (lane + 0.5) / 40.0 * 2.0 - 1.0 + 0.03 * sin(t * 2.0 + lane);
        return vec3(x * A * 1.05, y * 0.95, (h.z * 2.0 - 1.0) * 0.9);
    }
    if (k == 3) { // WAVE: rippling sheet in the lower third + knot above
        if (h.z < 0.25) { vec3 c = knot(h.x * 6.2831853 + t * 0.1, h.y); return rotX(0.6) * rotY(t * 0.25) * c + vec3(0.0, 0.25, 0.0); }
        float x = h.x * 2.0 - 1.0, z = h.y * 2.0 - 1.0;
        float w = sin(x * 7.0 - t * 2.2) * 0.4 + sin(z * 5.0 + t * 1.7) * 0.3 + snoise(vec3(x * 2.0, z * 2.0, t * 0.4)) * 0.5;
        return vec3(x * A, -0.55 + w * 0.09 * (0.4 + uAmp * 1.6), z * 0.9);
    }
    // CORE (alerte): the knot blown up + shell
    vec3 c = knot(h.x * 6.2831853 + t * 0.6, h.y) * 2.2;
    vec3 sh = normalize(hash3(i * 0.77) * 2.0 - 1.0) * 0.75;
    return rotY(t * 1.2) * (h.z < 0.6 ? c : sh);
}

void main() {
    vec3 p = aPos.xyz;
    float glow = aPos.w;
    vec3 v = aVel.xyz;
    float seed = aVel.w;
    vec3 h = hash3(seed);
    vec3 target = mix(shape(uA, seed, h, uT), shape(uB, seed, h, uT), uBlend);
    // Organic life: noise drift + touch repulsion + glitch snaps.
    vec3 n = vec3(snoise(p * 1.6 + vec3(0.0, uT * 0.25, 0.0)), snoise(p * 1.6 + vec3(7.1, 0.0, uT * 0.2)), snoise(p * 1.6 + vec3(0.0, 3.3, -uT * 0.22)));
    target += n * (0.035 + 0.06 * uAmp) * (1.0 + uJitter * 4.0);
    vec2 d = p.xy - uTouch;
    target.xy += normalize(d + 1e-4) * 0.35 * exp(-dot(d, d) * 10.0);
    if (uGlitch > 0.0 && hash(seed + floor(uT * 30.0)) < uGlitch) target.x += (hash(seed * 3.1 + uT) - 0.5) * 0.5;
    float k = 6.0 + 10.0 * uEnergy;
    v = mix(v, (target - p) * k, 1.0 - exp(-uDt * 8.0));
    p += v * uDt;
    glow = mix(glow, length(v), 0.2);
    oPos = vec4(p, glow);
    oVel = vec4(v, seed);
}
