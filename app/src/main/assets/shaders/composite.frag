#version 300 es
// Final grade: background atmosphere, dark body, bloom, chromatic aberration, ACES, vignette, grain.
precision highp float;
in vec2 vUv;
uniform sampler2D uScene;
uniform sampler2D uBloom;
uniform vec2 uResolution;      // output px
uniform vec2 uSphereCenter;    // px
uniform float uSphereRadius;   // px (shell)
uniform float uBodyRadiusPx;   // px (dark body)
uniform float uBloomStrength;
uniform float uExposure;
uniform float uTime;
uniform float uHalo;
uniform float uAlert;
uniform float uHdrScale;       // 1 when rendering to float targets, >1 on RGBA8 fallback
out vec4 fragColor;

float hash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

vec3 aces(vec3 x) {
    // Stephen Hill's fitted ACES (RRT + ODT), keeps highlights from clipping flat.
    const mat3 inM = mat3(0.59719, 0.07600, 0.02840, 0.35458, 0.90834, 0.13383, 0.04823, 0.01566, 0.83777);
    const mat3 outM = mat3(1.60475, -0.10208, -0.00327, -0.53108, 1.10813, -0.07276, -0.07367, -0.00605, 1.07602);
    x = inM * x;
    vec3 a = x * (x + 0.0245786) - 0.000090537;
    vec3 b = x * (0.983729 * x + 0.4329510) + 0.238081;
    return clamp(outM * (a / b), 0.0, 1.0);
}

vec3 toSrgb(vec3 c) {
    return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c));
}

void main() {
    vec2 px = vUv * uResolution;
    vec2 fromC = (px - uSphereCenter);
    float r = length(fromC);

    // --- background: muted violet-blue haze top right + diffuse amber halo ------------------
    vec3 bg = vec3(0.0);
    vec2 tr = (vUv - vec2(1.05, 1.02)) * vec2(uResolution.x / uResolution.y, 1.0);
    bg += vec3(0.05, 0.04, 0.16) * 0.12 * exp(-dot(tr, tr) * 4.0);
    bg += vec3(0.02, 0.018, 0.06) * 0.06 * exp(-dot(tr, tr) * 1.2);
    float rn = r / uSphereRadius;
    vec3 amber = vec3(1.0, 0.33, 0.04);
    bg += amber * uHalo * (0.07 * exp(-max(rn - 0.97, 0.0) * 6.5) + 0.012 * exp(-rn * 1.4));
    bg += vec3(1.0, 0.06, 0.02) * uAlert * 0.18 * exp(-max(rn - 0.97, 0.0) * 6.0);

    // --- the night-side body hides the halo: visible dark rim -------------------------------
    float body = 1.0 - smoothstep(uBodyRadiusPx - 1.5, uBodyRadiusPx + 1.5, r);
    float limb = pow(clamp(r / uBodyRadiusPx, 0.0, 1.0), 6.0);
    vec3 bodyCol = amber * 0.004 * (1.0 - limb);
    bg = mix(bg, bodyCol, body);

    // --- scene + bloom with a hint of lateral chromatic aberration at the edges -------------
    vec2 cc = vUv - 0.5;
    vec2 ca = cc * dot(cc, cc) * 0.006;
    vec3 scene;
    scene.r = texture(uScene, vUv + ca).r;
    scene.g = texture(uScene, vUv).g;
    scene.b = texture(uScene, vUv - ca).b;
    vec3 bloom;
    bloom.r = texture(uBloom, vUv + ca * 2.0).r;
    bloom.g = texture(uBloom, vUv).g;
    bloom.b = texture(uBloom, vUv - ca * 2.0).b;

    vec3 hdr = bg + (scene + bloom * uBloomStrength) * uHdrScale;
    hdr *= uExposure;

    // Vignette (applied before tonemapping so it rolls off naturally).
    float v = smoothstep(1.25, 0.25, length(cc * vec2(uResolution.x / uResolution.y, 1.0) * 1.35));
    hdr *= mix(0.55, 1.0, v);

    vec3 ldr = aces(hdr);
    vec3 outc = toSrgb(ldr);

    // Film grain, stronger in the mids, plus dithering against banding.
    float g = hash(px + fract(uTime * 13.37) * 1000.0) - 0.5;
    float l = dot(outc, vec3(0.299, 0.587, 0.114));
    outc += g * (0.028 * (1.0 - abs(l - 0.45) * 1.4) + 1.0 / 255.0);
    fragColor = vec4(outc, 1.0);
}
