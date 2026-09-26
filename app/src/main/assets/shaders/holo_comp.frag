#version 300 es
// Composite: background haze, scene + bloom, glitch slices, chromatic aberration, scanlines,
// vignette, soft tonemap, grain.
precision mediump float;
in vec2 vUv;
uniform sampler2D uScene, uBloom;
uniform float uT, uAlert, uGlitch, uHdrScale;
uniform vec2 uRes;
out vec4 o;
float hash(vec2 p) { vec3 p3 = fract(vec3(p.xyx) * 0.1031); p3 += dot(p3, p3.yzx + 33.33); return fract((p3.x + p3.y) * p3.z); }
void main() {
    vec2 uv = vUv;
    float band = floor(uv.y * 40.0);
    if (uGlitch > 0.0 && hash(vec2(band, floor(uT * 12.0))) < uGlitch * 0.35) uv.x += (hash(vec2(band, 1.0)) - 0.5) * 0.025;
    vec2 cc = uv - 0.5;
    vec2 ca = cc * dot(cc, cc) * 0.02;
    vec3 s;
    s.r = texture(uScene, uv + ca).r;
    s.g = texture(uScene, uv).g;
    s.b = texture(uScene, uv - ca).b;
    vec3 b = texture(uBloom, uv).rgb;
    float rad = length(cc * vec2(uRes.x / uRes.y, 1.0));
    vec3 bg = vec3(0.012, 0.02, 0.03) + vec3(0.0, 0.05, 0.07) * (1.0 - rad * 0.9);
    bg = mix(bg, vec3(0.08, 0.0, 0.02), uAlert * 0.6);
    vec3 c = bg + (s + b * 0.8) * uHdrScale;
    c *= 1.0 - smoothstep(0.55, 1.1, rad) * 0.55;
    // scanlines
    c *= 1.0 - 0.08 * step(0.5, fract(uv.y * uRes.y / 3.0));
    c = c / (1.0 + c);
    c = pow(c, vec3(0.9));
    c += (hash(uv * uRes + fract(uT * 7.0) * 100.0) - 0.5) * 0.02;
    o = vec4(c, 1.0);
}
