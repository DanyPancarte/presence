#version 300 es
precision mediump float;
in vec2 vUv;
uniform sampler2D uSrc;
uniform vec2 uDir;
out vec4 o;
void main() {
    float w[5] = float[](0.227, 0.194, 0.121, 0.054, 0.016);
    vec3 c = texture(uSrc, vUv).rgb * w[0];
    for (int i = 1; i < 5; i++) {
        c += texture(uSrc, vUv + uDir * float(i)).rgb * w[i];
        c += texture(uSrc, vUv - uDir * float(i)).rgb * w[i];
    }
    o = vec4(c, 1.0);
}
