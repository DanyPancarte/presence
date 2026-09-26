#version 300 es
// Renders each particle as a short velocity streak (instanced quad) with geometric depth of field.
// Filaments emerge from the streaks + the trail feedback in the scene buffer.
precision highp float;

layout(location = 0) in vec2 aCorner;   // x: 0..1 along, y: -1..1 across
layout(location = 1) in vec4 aPos;      // xyz, age
layout(location = 2) in vec4 aVel;      // xyz, seed

uniform mat4 uView;
uniform mat4 uProj;
uniform mat3 uModel;
uniform vec2 uViewport;
uniform float uTime;
uniform float uPxScale;
uniform float uStreak;        // seconds of motion drawn per streak

uniform float uFocusDist;
uniform float uCocScale;
uniform float uMaxCoc;
uniform vec3 uCenterView;
uniform float uBodyRadius;
uniform float uBodyDensity;

uniform float uGain;
uniform float uCoreGain;
uniform float uTwinkle;
uniform float uAlert;
uniform float uTemp;
uniform float uDensity;
uniform float uLife;
uniform float uSweep;
uniform float uSweepPos;
uniform vec3 uSweepAxis;
uniform vec4 uBands;
uniform vec2 uShock;

uniform int uZoneCount;
uniform vec4 uZones[12];
uniform vec4 uZoneParams[12];

out vec3 vColor;
out vec2 vLocal;
out float vLenPx;
out float vHalfW;
out float vSoft;

float hash(float n) { return fract(sin(n) * 43758.5453123); }

vec3 palette(float heat) {
    vec3 deep = vec3(1.0, 0.195, 0.009);
    vec3 amber = vec3(1.0, 0.474, 0.074);
    vec3 hot = vec3(1.0, 0.896, 0.672);
    heat = clamp(heat + uTemp * 0.18, 0.0, 1.0);
    return heat < 0.6 ? mix(deep, amber, heat / 0.6) : mix(amber, hot, (heat - 0.6) / 0.4);
}

float bodyTransmittance(vec3 pv) {
    float dist = length(pv);
    vec3 d = pv / dist;
    float b = dot(d, uCenterView);
    float c = dot(uCenterView, uCenterView) - uBodyRadius * uBodyRadius;
    float disc = b * b - c;
    if (disc <= 0.0) return 1.0;
    float s = sqrt(disc);
    float t0 = b - s;
    float t1 = min(b + s, dist);
    return exp(-max(t1 - max(t0, 0.0), 0.0) * uBodyDensity);
}

void main() {
    vec3 pos = aPos.xyz;
    float age = aPos.w;
    vec3 vel = aVel.xyz;
    float seed = aVel.w;
    float speed = length(vel);

    vec3 p1 = uModel * pos;
    vec3 p0 = uModel * (pos - vel * uStreak);
    vec4 v0 = uView * vec4(p0, 1.0);
    vec4 v1 = uView * vec4(p1, 1.0);
    vec4 c0 = uProj * v0;
    vec4 c1 = uProj * v1;
    vec2 s0 = (c0.xy / c0.w * 0.5 + 0.5) * uViewport;
    vec2 s1 = (c1.xy / c1.w * 0.5 + 0.5) * uViewport;

    float depth = -v1.z;
    float coc = min(abs(depth - uFocusDist) * uCocScale * uPxScale, uMaxCoc * uPxScale);
    float w = max(1.1 * uPxScale * 2.0, 0.8);
    float halfW = 0.5 * w + coc;

    vec2 seg = s1 - s0;
    float len = length(seg);
    vec2 dir = len > 1e-3 ? seg / len : vec2(1.0, 0.0);
    vec2 nrm = vec2(-dir.y, dir.x);
    float along = aCorner.x * (len + 2.0 * halfW) - halfW;
    vec2 sp = s0 + dir * along + nrm * aCorner.y * halfW;
    vec4 clip = mix(c0, c1, aCorner.x);
    gl_Position = vec4((sp / uViewport * 2.0 - 1.0) * clip.w, clip.z, clip.w);

    // ---- intensity -------------------------------------------------------------------------
    float r = length(pos);
    vec3 dm = pos / max(r, 1e-4);
    float core = exp(-r * 7.0);
    float I = 0.16 + 0.9 * exp(-r * 3.0) + core * 1.4 * uCoreGain;
    I *= 0.55 + 0.9 * smoothstep(0.02, 0.6, speed);
    // Birth fade-in and death fade-out: nothing pops.
    I *= smoothstep(0.0, 0.6, age) * (1.0 - smoothstep(uLife * 1.2, uLife * 1.45, age));
    // Independent scintillation.
    float rate = mix(0.6, 5.0, hash(seed * 13.1));
    I *= mix(1.0, 0.55 + 0.45 * sin(uTime * rate + seed * 61.0), uTwinkle * 0.7);
    // Thinking sweep.
    I *= 1.0 + uSweep * 2.5 * exp(-pow((dot(dm, uSweepAxis) - uSweepPos) * 9.0, 2.0));
    // Voice bands light different latitudes; shock front flares.
    float lat = dm.y * 0.5 + 0.5;
    vec4 bandMask = vec4(core * 3.0, 1.0 - abs(lat - 0.5) * 2.0, smoothstep(0.55, 1.0, lat), smoothstep(0.45, 0.0, lat));
    I *= 1.0 + dot(uBands, bandMask) * 1.5;
    I *= 1.0 + uShock.y * 4.0 * exp(-pow((r - uShock.x) * 7.0, 2.0));
    // Mood density: fewer living particles.
    I *= 1.0 - smoothstep(uDensity * 1.08 - 0.08, uDensity * 1.08, hash(seed * 7.13));
    // Module zones.
    for (int i = 0; i < 12; i++) {
        if (i >= uZoneCount) break;
        float d = dot(dm, uZones[i].xyz);
        if (d > uZones[i].w && r > 0.3) {
            vec4 zp = uZoneParams[i];
            float blink = zp.y > 0.0 ? 0.55 + 0.45 * sin(uTime * zp.y + float(i) * 1.7) : 1.0;
            float hole = 1.0 - smoothstep(zp.z - 0.0015, zp.z + 0.0015, d);
            I *= zp.x * blink * hole;
        }
    }
    // Depth of field energy spread + dark body.
    I *= pow((0.5 * w) / halfW, 0.85);
    I *= bodyTransmittance(v1.xyz);
    // Longer streaks spread the same light over more pixels.
    I *= 1.0 / (1.0 + len * 0.04);
    I *= 1.0 - smoothstep(1.0, 1.12, r);
    I *= uGain;

    float heat = clamp(core * 1.6 + speed * 0.35 + 0.15 * hash(seed * 3.3), 0.0, 1.0);
    vec3 col = palette(heat);
    col = mix(col, vec3(1.0, 0.09, 0.02), uAlert * 0.75);
    I *= 1.0 + uAlert * (0.5 + 0.5 * sin(uTime * 7.0 - r * 9.0)) * 2.5;

    vColor = col * I;
    vLocal = vec2(along, aCorner.y * halfW);
    vLenPx = len;
    vHalfW = halfW;
    vSoft = clamp(coc / (halfW + 1e-3), 0.0, 1.0);
}
