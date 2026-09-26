#version 300 es
// Organism simulation, one particle per vertex, updated with transform feedback.
// Particles are born on the shell (partly at wandering "source" spots), pulled toward the core
// with an anisotropic, time-varying force, stirred by divergence-free noise and a precessing
// vortex. When they reach the core they are absorbed and reborn: a constant, never-repeating
// inflow — organised chaos.
precision highp float;

layout(location = 0) in vec4 aPos;   // xyz, age
layout(location = 1) in vec4 aVel;   // xyz, seed

out vec4 oPos;
out vec4 oVel;

uniform float uTime;
uniform float uDt;
uniform float uFrame;
uniform vec3 uAttract;      // per-axis pull toward the core (xyz factors)
uniform float uCurl;        // turbulence strength
uniform float uCurlScale;
uniform float uCurlSpeed;
uniform vec3 uSwirlAxis;
uniform float uSwirl;       // vortex strength
uniform float uShellR;
uniform float uLife;
uniform float uStreams;     // share of births at the wandering sources
uniform vec2 uShock;        // front radius, strength
uniform float uAmp;         // voice amplitude: the organism inhales / expands
uniform float uCoreR;       // absorption radius

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

float hash(float n) { return fract(sin(n) * 43758.5453123); }
vec3 hash3(float n) { return vec3(hash(n), hash(n + 17.13), hash(n + 41.71)); }

// Divergence-free field: cross product of two noise gradients (Bridson-style).
vec3 grad(vec3 p) {
    const float e = 0.07;
    return vec3(
        snoise(p + vec3(e, 0, 0)) - snoise(p - vec3(e, 0, 0)),
        snoise(p + vec3(0, e, 0)) - snoise(p - vec3(0, e, 0)),
        snoise(p + vec3(0, 0, e)) - snoise(p - vec3(0, 0, e))) / (2.0 * e);
}

vec3 flow(vec3 p, float t) {
    vec3 q = p * uCurlScale;
    vec3 g1 = grad(q + vec3(0.0, t * uCurlSpeed, 0.0));
    vec3 g2 = grad(q * 0.7 + vec3(19.1, -t * uCurlSpeed * 0.8, 7.7));
    return cross(g1, g2) * 0.18;
}

// Wandering birth spots: directions drifting on slow, incommensurate orbits.
vec3 source(float k, float t) {
    float a = t * (0.041 + 0.013 * k) + k * 2.399;
    float b = t * (0.029 + 0.007 * k) + k * 1.618;
    return normalize(vec3(cos(a) * cos(b * 1.3), sin(b) * 0.9 + 0.2 * sin(a * 0.7), sin(a) * cos(b)));
}

void main() {
    vec3 p = aPos.xyz;
    float age = aPos.w;
    vec3 v = aVel.xyz;
    float seed = aVel.w;
    float t = uTime;
    float r = length(p);

    float life = uLife * (0.55 + 0.9 * hash(seed * 91.0));
    bool reborn = age > life || r < uCoreR * (0.5 + hash(seed + uFrame * 0.37)) || r > uShellR * 1.6;

    if (reborn) {
        vec3 h = hash3(seed * 13.7 + uFrame * 0.618 + t);
        vec3 dir = normalize(vec3(
            sqrt(-2.0 * log(max(h.x, 1e-4))) * cos(6.2831 * h.y),
            sqrt(-2.0 * log(max(h.y, 1e-4))) * sin(6.2831 * h.z),
            sqrt(-2.0 * log(max(h.z, 1e-4))) * cos(6.2831 * h.x)));
        if (hash(seed * 3.1 + uFrame * 0.11) < uStreams) {
            float k = floor(hash(seed * 7.7 + uFrame * 0.23) * 6.0);
            dir = normalize(source(k, t) + dir * 0.16);
        }
        float rr = uShellR * (0.9 + 0.12 * hash(seed + t));
        oPos = vec4(dir * rr, 0.0);
        oVel = vec4(0.0, 0.0, 0.0, seed);
        return;
    }

    vec3 dir = p / max(r, 1e-4);

    // Anisotropic pull to the core, stronger in the middle of the volume.
    vec3 pull = -uAttract * p * (0.6 + 0.8 * smoothstep(1.1, 0.2, r));
    // Voice makes the organism breathe out.
    pull *= 1.0 - clamp(uAmp, 0.0, 1.0) * 0.8;

    // Vortex around a precessing axis, faster near the core.
    vec3 swirl = cross(uSwirlAxis, p) * uSwirl / (r + 0.18);

    // Turbulence, fading near the core so the centre stays a dense knot.
    vec3 turb = flow(p, t) * uCurl * (0.35 + 0.65 * smoothstep(0.05, 0.5, r));

    // Soft shell: never leave the sphere.
    vec3 shell = -dir * (max(r - uShellR * 0.94, 0.0) * 10.0 + max(r - uShellR, 0.0) * 30.0);

    // Shock wave: radial kick at the travelling front.
    vec3 shock = dir * uShock.y * exp(-pow((r - uShock.x) * 8.0, 2.0)) * 2.5;

    vec3 target = pull + swirl + turb + shell + shock;
    float inertia = 1.0 - exp(-uDt * 4.0);
    v = mix(v, target, inertia);
    p += v * uDt;

    oPos = vec4(p, age + uDt);
    oVel = vec4(v, seed);
}
