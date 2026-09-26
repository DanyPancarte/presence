#version 300 es
// Filament segments as instanced screen-space quads.
// Geometric depth of field: each segment is widened by its circle of confusion and its energy
// is spread accordingly, which is what a real lens does to thin emissive lines.
precision highp float;

layout(location = 0) in vec2 aCorner;   // x: 0..1 along segment, y: -1..1 across
layout(location = 1) in vec4 aP0;       // xyz, seed
layout(location = 2) in vec4 aP1;       // xyz, kind
layout(location = 3) in vec4 aA;        // dist0, dist1, brightness, heat
layout(location = 4) in vec4 aB;        // width px, region, layer radius, depth

uniform mat4 uView;
uniform mat4 uProj;
uniform mat3 uModel;
uniform mat3 uCoreSpin;
uniform vec2 uViewport;
uniform float uTime;
uniform float uPxScale;       // viewport height / 2400

uniform float uFocusDist;
uniform float uCocScale;      // px of blur per unit of defocus
uniform float uMaxCoc;
uniform vec3 uCenterView;
uniform float uBodyRadius;
uniform float uBodyDensity;

// Behaviour (all smoothed CPU side)
uniform float uDilate;
uniform float uFlowSpeed;
uniform float uFlowAmt;
uniform float uTwinkle;
uniform float uSweep;
uniform float uSweepPos;
uniform vec3 uSweepAxis;
uniform float uGain;
uniform float uCoreGain;
uniform float uAlert;
uniform float uDensity;       // mood: fraction of filaments alive
uniform float uTemp;          // mood: -1 cold/pale .. +1 hot/deep
uniform float uBreath;

// Audio
uniform float uAmp;
uniform vec4 uBands;
uniform vec2 uShock;          // front radius, strength

// Modules: task zones etc.
uniform int uZoneCount;
uniform vec4 uZones[12];      // xyz dir (model space), w cos(angular radius)
uniform vec4 uZoneParams[12]; // x brightness, y blink rate, z cos(hole radius), w unused

out vec3 vColor;
out vec2 vLocal;      // along px (from start), across px
out float vLenPx;
out float vHalfW;
out float vSoft;

float hash11(float p) {
    p = fract(p * 0.1031);
    p *= p + 33.33;
    p *= p + p;
    return fract(p);
}

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

// Model-space animated position of an endpoint.
vec3 animate(vec3 p, float seed, float kind, float layer) {
    float r = length(p);
    vec3 dir = r > 1e-5 ? p / r : vec3(0.0, 1.0, 0.0);
    if (kind == 2.0) {
        // Core spins on its own tilted axis, faster near the center: a slow vortex.
        p = uCoreSpin * p;
        float swirl = 0.06 * sin(uTime * 0.23 + r * 40.0);
        float c = cos(swirl), s = sin(swirl);
        p.xz = mat2(c, -s, s, c) * p.xz;
        return p * (1.0 + 0.25 * uCoreGain * 0.2 + uBreath * 0.02);
    }
    // Slow organic drift of the whole network (simplex, time-seeded, never loops).
    float n = snoise(dir * 1.7 + vec3(0.0, uTime * 0.035, uTime * 0.021));
    float spread = 0.5 + hash11(seed * 91.7);
    float shock = uShock.y * exp(-pow((r - uShock.x) * 9.0, 2.0));
    float grow = 1.0 + uDilate * (0.35 + 0.65 * spread) * r + n * 0.012 + shock * 0.07 + uBreath * 0.006;
    return p * grow;
}

vec3 palette(float heat) {
    // Linear-space versions of #FF7A18, #FFB74D, #FFF3D6.
    vec3 deep = vec3(1.0, 0.195, 0.009);
    vec3 amber = vec3(1.0, 0.474, 0.074);
    vec3 hot = vec3(1.0, 0.896, 0.672);
    heat = clamp(heat + uTemp * 0.18, 0.0, 1.0);
    vec3 c = heat < 0.6 ? mix(deep, amber, heat / 0.6) : mix(amber, hot, (heat - 0.6) / 0.4);
    return c;
}

float bodyTransmittance(vec3 pv) {
    // Ray camera -> point through the opaque dark body (sphere at uCenterView).
    float dist = length(pv);
    vec3 d = pv / dist;
    float b = dot(d, uCenterView);
    float c = dot(uCenterView, uCenterView) - uBodyRadius * uBodyRadius;
    float disc = b * b - c;
    if (disc <= 0.0) return 1.0;
    float s = sqrt(disc);
    float t0 = b - s;
    float t1 = min(b + s, dist);
    float inside = max(t1 - max(t0, 0.0), 0.0);
    return exp(-inside * uBodyDensity);
}

void main() {
    float seed = aP0.w;
    float kind = aP1.w;
    float layer = aB.z;

    // The core keeps its own camera-facing tilt (uCoreSpin) so the spiral always reads.
    mat3 M = kind == 2.0 ? mat3(1.0) : uModel;
    vec3 m0 = M * animate(aP0.xyz, seed, kind, layer);
    vec3 m1 = M * animate(aP1.xyz, seed, kind, layer);
    vec4 v0 = uView * vec4(m0, 1.0);
    vec4 v1 = uView * vec4(m1, 1.0);
    vec4 c0 = uProj * v0;
    vec4 c1 = uProj * v1;
    vec2 s0 = (c0.xy / c0.w * 0.5 + 0.5) * uViewport;
    vec2 s1 = (c1.xy / c1.w * 0.5 + 0.5) * uViewport;

    vec3 vmid = mix(v0.xyz, v1.xyz, aCorner.x);
    float depth = -vmid.z;
    float cocK = kind == 2.0 ? 0.3 : 1.0;
    float coc = min(abs(depth - uFocusDist) * uCocScale * cocK * uPxScale, uMaxCoc * uPxScale);
    float w = max(aB.x * uPxScale, 0.75);
    float halfW = 0.5 * w + coc;

    vec2 seg = s1 - s0;
    float len = length(seg);
    vec2 dir = len > 1e-3 ? seg / len : vec2(1.0, 0.0);
    vec2 nrm = vec2(-dir.y, dir.x);
    float along = aCorner.x * (len + 2.0 * halfW) - halfW;
    vec2 sp = s0 + dir * along + nrm * aCorner.y * halfW;

    vec4 clip = mix(c0, c1, aCorner.x);
    gl_Position = vec4((sp / uViewport * 2.0 - 1.0) * clip.w, clip.z, clip.w);

    // ---- intensity ----------------------------------------------------------------------
    vec3 pm = mix(aP0.xyz, aP1.xyz, aCorner.x);
    float r = length(pm);
    vec3 dirm = r > 1e-5 ? pm / r : vec3(0.0, 1.0, 0.0);
    float I = aA.z;

    // Independent scintillation: two incommensurate rates per filament + rare dropouts.
    float rate = mix(0.35, 3.2, hash11(seed * 13.1));
    float ph = seed * 61.0;
    float fl = 0.62 + 0.25 * sin(uTime * rate + ph) + 0.13 * sin(uTime * rate * 2.71 + ph * 1.7);
    float drop = smoothstep(-0.97, -0.88, sin(uTime * rate * 0.113 + ph * 3.3));
    I *= mix(1.0, fl * mix(0.25, 1.0, drop), uTwinkle);

    // Internal currents: pulses travelling along the network paths.
    float dist = mix(aA.x, aA.y, aCorner.x);
    float pulse = pow(0.5 + 0.5 * sin(dist * 38.0 - uTime * uFlowSpeed + seed * 2.0), 18.0);
    float flowMask = smoothstep(0.1, 0.7, snoise(dirm * 1.3 + vec3(uTime * 0.05)) * 0.5 + 0.5);
    I *= 1.0 + uFlowAmt * pulse * (0.6 + 2.4 * flowMask);

    // Slow luminance waves sweeping the sphere (data weather).
    float wave = snoise(dirm * 2.2 + vec3(uTime * 0.07, -uTime * 0.05, uTime * 0.04));
    I *= 0.72 + 0.5 * smoothstep(-0.4, 0.8, wave);

    // Thinking sweep.
    float sw = exp(-pow((dot(dirm, uSweepAxis) - uSweepPos) * 10.0, 2.0));
    I *= 1.0 + uSweep * sw * 3.0;

    // Audio: frequency bands light different latitudes; transients ride the shock front.
    float lat = dirm.y * 0.5 + 0.5;
    vec4 bandMask = vec4(
        (kind == 1.0 || kind == 4.0) ? 1.0 : 0.25,
        1.0 - abs(lat - 0.5) * 2.0,
        smoothstep(0.55, 1.0, lat),
        smoothstep(0.45, 0.0, lat));
    I *= 1.0 + dot(uBands, bandMask) * 1.6 + uAmp * 0.8;
    I *= 1.0 + uShock.y * 4.0 * exp(-pow((r - uShock.x) * 7.0, 2.0));

    // Mood density: filaments die off in a stable order.
    float alive = 1.0 - smoothstep(uDensity * 1.08 - 0.08, uDensity * 1.08, fract(seed * 7.13 + aB.y * 0.37));
    I *= mix(0.03, 1.0, alive);

    // Module zones (tasks / 97%).
    if (kind != 2.0) {
        for (int i = 0; i < 12; i++) {
            if (i >= uZoneCount) break;
            float d = dot(dirm, uZones[i].xyz);
            if (d > uZones[i].w) {
                vec4 zp = uZoneParams[i];
                float edge = smoothstep(uZones[i].w, uZones[i].w + 0.01, d);
                float blink = zp.y > 0.0 ? 0.55 + 0.45 * sin(uTime * zp.y + float(i) * 1.7) : 1.0;
                float hole = 1.0 - smoothstep(zp.z - 0.0015, zp.z + 0.0015, d);
                I *= mix(1.0, zp.x * blink * hole, edge);
            }
        }
    }

    float heat = aA.w;
    if (kind == 2.0) {
        I *= uCoreGain;
    }

    // Depth of field energy conservation (area of the blurred footprint).
    float focusW = 0.5 * w;
    float energy = focusW / halfW;
    I *= pow(energy, 0.85);

    I *= bodyTransmittance(vmid);

    // Night-side limb: grazing filaments on the shells fade instead of piling up into a ring.
    if (kind != 2.0 && kind != 4.0) {
        vec3 nv = M * dirm;
        float facing = dot(nv, normalize(-vmid));
        I *= mix(0.015, 1.0, smoothstep(-0.05, 0.55, facing));
    }
    I *= uGain;

    vec3 col = palette(heat);
    // Alert: red-orange violent pulse from the core outward.
    float alertWave = 0.5 + 0.5 * sin(uTime * 7.0 - r * 9.0);
    col = mix(col, vec3(1.0, 0.09, 0.02), uAlert * 0.75);
    I *= 1.0 + uAlert * alertWave * 2.5;

    vColor = col * I;
    vLocal = vec2(along, aCorner.y * halfW);
    vLenPx = len;
    vHalfW = halfW;
    vSoft = clamp(coc / (halfW + 1e-3), 0.0, 1.0);
}
