#version 300 es
// One triangle that covers the screen; v is the 0..1 texture coordinate.
precision highp float;
out vec2 v;
void main() {
  vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
  v = p;
  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
