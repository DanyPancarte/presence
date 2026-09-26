#version 300 es
precision highp float;

in vec3 vColor;
in vec2 vLocal;
in float vLenPx;
in float vHalfW;
in float vSoft;

out vec4 fragColor;

void main() {
    // Distance from the fragment to the segment core, normalised by the half width.
    float a = vLocal.x;
    float ax = max(max(-a, a - vLenPx), 0.0);
    float d = length(vec2(ax, vLocal.y)) / vHalfW;
    if (d >= 1.0) discard;
    // In focus: gaussian core. Out of focus: flat bokeh disc with a soft rim.
    float sharp = exp(-d * d * 5.0);
    float bokeh = smoothstep(1.0, 0.7, d) * 0.9 + 0.1 * smoothstep(1.0, 0.85, d);
    float prof = mix(sharp, bokeh * 0.55, vSoft);
    fragColor = vec4(vColor * prof, 1.0);
}
