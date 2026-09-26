#version 300 es
// 9-tap tent upsample, additively blended onto the next larger mip.
precision highp float;
in vec2 vUv;
uniform sampler2D uSrc;
uniform vec2 uTexel;
uniform float uRadius;
uniform float uWeight;
out vec4 fragColor;
vec3 s(vec2 o) { return texture(uSrc, vUv + o * uTexel * uRadius).rgb; }
void main() {
    vec3 c = s(vec2(0)) * 4.0
        + (s(vec2(-1, 0)) + s(vec2(1, 0)) + s(vec2(0, -1)) + s(vec2(0, 1))) * 2.0
        + s(vec2(-1, -1)) + s(vec2(1, -1)) + s(vec2(-1, 1)) + s(vec2(1, 1));
    fragColor = vec4(c / 16.0 * uWeight, 1.0);
}
