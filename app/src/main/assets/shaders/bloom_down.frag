#version 300 es
// 13-tap downsample (Jimenez, CoD:AW). First pass also applies a soft threshold
// so only the hot zones bleed.
precision highp float;
in vec2 vUv;
uniform sampler2D uSrc;
uniform vec2 uTexel;          // 1 / source size
uniform float uPrefilter;     // 1 on the first pass
uniform float uThreshold;
uniform float uKnee;
out vec4 fragColor;

vec3 s(vec2 o) { return texture(uSrc, vUv + o * uTexel).rgb; }

void main() {
    vec3 a = s(vec2(-2, 2)), b = s(vec2(0, 2)), c = s(vec2(2, 2));
    vec3 d = s(vec2(-2, 0)), e = s(vec2(0, 0)), f = s(vec2(2, 0));
    vec3 g = s(vec2(-2, -2)), h = s(vec2(0, -2)), i = s(vec2(2, -2));
    vec3 j = s(vec2(-1, 1)), k = s(vec2(1, 1)), l = s(vec2(-1, -1)), m = s(vec2(1, -1));
    vec3 col = e * 0.125 + (a + c + g + i) * 0.03125 + (b + d + f + h) * 0.0625 + (j + k + l + m) * 0.125;
    if (uPrefilter > 0.5) {
        // Soft-knee threshold on the brightest channel.
        float br = max(col.r, max(col.g, col.b));
        float rq = clamp(br - uThreshold + uKnee, 0.0, 2.0 * uKnee);
        rq = rq * rq / (4.0 * uKnee + 1e-4);
        col *= max(rq, br - uThreshold) / max(br, 1e-4);
        col = min(col, vec3(64.0));
    }
    fragColor = vec4(col, 1.0);
}
