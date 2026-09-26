#version 300 es
// Multiplies the previous frame by uDecay (via blending) so particles leave luminous trails.
precision mediump float;
uniform float uDecay;
out vec4 fragColor;
void main() { fragColor = vec4(0.0, 0.0, 0.0, uDecay); }
