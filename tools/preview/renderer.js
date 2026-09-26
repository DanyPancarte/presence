// WebGL2 mirror of app/src/main/java/com/dany/presence/render/SphereRenderer.kt.
// Same shader files, same pass order, same uniforms — used to produce captures off-device.
'use strict';

const SHADERS = '../../app/src/main/assets/shaders/';

async function loadText(p) { const r = await fetch(p); if (!r.ok) throw new Error(p); return r.text(); }

function compile(gl, vsSrc, fsSrc) {
  const mk = (type, src) => {
    const s = gl.createShader(type);
    gl.shaderSource(s, src); gl.compileShader(s);
    if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s) + '\n' + src.split('\n').map((l, i) => (i + 1) + ': ' + l).join('\n'));
    return s;
  };
  const p = gl.createProgram();
  gl.attachShader(p, mk(gl.VERTEX_SHADER, vsSrc));
  gl.attachShader(p, mk(gl.FRAGMENT_SHADER, fsSrc));
  gl.linkProgram(p);
  if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(p));
  const u = {};
  const n = gl.getProgramParameter(p, gl.ACTIVE_UNIFORMS);
  for (let i = 0; i < n; i++) {
    const info = gl.getActiveUniform(p, i);
    const name = info.name.replace(/\[0\]$/, '');
    u[name] = gl.getUniformLocation(p, info.name);
  }
  return { p, u };
}

// ---- tiny matrix helpers (column-major) ----
function perspective(fovY, aspect, near, far) {
  const f = 1 / Math.tan(fovY / 2), nf = 1 / (near - far);
  return new Float32Array([f / aspect, 0, 0, 0, 0, f, 0, 0, 0, 0, (far + near) * nf, -1, 0, 0, 2 * far * near * nf, 0]);
}
function mat3Mul(a, b) {
  const o = new Float32Array(9);
  for (let c = 0; c < 3; c++) for (let r = 0; r < 3; r++) {
    o[c * 3 + r] = a[r] * b[c * 3] + a[3 + r] * b[c * 3 + 1] + a[6 + r] * b[c * 3 + 2];
  }
  return o;
}
function rotX(a) { const c = Math.cos(a), s = Math.sin(a); return new Float32Array([1, 0, 0, 0, c, s, 0, -s, c]); }
function rotY(a) { const c = Math.cos(a), s = Math.sin(a); return new Float32Array([c, 0, -s, 0, 1, 0, s, 0, c]); }
function rotZ(a) { const c = Math.cos(a), s = Math.sin(a); return new Float32Array([c, s, 0, -s, c, 0, 0, 0, 1]); }
function axisAngle(ax, a) {
  const [x, y, z] = ax, c = Math.cos(a), s = Math.sin(a), t = 1 - c;
  return new Float32Array([t * x * x + c, t * x * y + s * z, t * x * z - s * y, t * x * y - s * z, t * y * y + c, t * y * z + s * x, t * x * z + s * y, t * y * z - s * x, t * z * z + c]);
}

// Smooth pseudo-noise for the orientation drift (sum of incommensurate sines — mirrors Kotlin).
function drift(t, k) { return Math.sin(t * 0.071 * k + 1.3 * k) * 0.6 + Math.sin(t * 0.0313 * k + 0.7) * 0.4; }


function compileTF(gl, vsSrc, fsSrc, varyings) {
  const mk = (type, src) => {
    const s = gl.createShader(type);
    gl.shaderSource(s, src); gl.compileShader(s);
    if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s));
    return s;
  };
  const p = gl.createProgram();
  gl.attachShader(p, mk(gl.VERTEX_SHADER, vsSrc));
  gl.attachShader(p, mk(gl.FRAGMENT_SHADER, fsSrc));
  gl.transformFeedbackVaryings(p, varyings, gl.INTERLEAVED_ATTRIBS);
  gl.linkProgram(p);
  if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(p));
  const u = {};
  const n = gl.getProgramParameter(p, gl.ACTIVE_UNIFORMS);
  for (let i = 0; i < n; i++) { const info = gl.getActiveUniform(p, i); u[info.name.replace(/\[0\]$/, '')] = gl.getUniformLocation(p, info.name); }
  return { p, u };
}

class Organism {
  constructor(canvas, count) {
    this.canvas = canvas;
    this.N = count || 150000;
    const gl = canvas.getContext('webgl2', { antialias: false, alpha: false, preserveDrawingBuffer: true });
    if (!gl) throw new Error('no webgl2');
    this.gl = gl;
    this.hdr = !!gl.getExtension('EXT_color_buffer_float');
    gl.getExtension('OES_texture_float_linear');
    this.simTime = 0; this.frame = 0; this.acc = 0; this.cur = 0;
  }

  async init() {
    const gl = this.gl;
    const names = ['sim.vert', 'sim.frag', 'particle.vert', 'streak.frag', 'fullscreen.vert', 'fade.frag', 'bloom_down.frag', 'bloom_up.frag', 'composite.frag'];
    const src = {};
    (await Promise.all(names.map(n => loadText(SHADERS + n)))).forEach((t, i) => src[names[i]] = t);
    this.pSim = compileTF(gl, src['sim.vert'], src['sim.frag'], ['oPos', 'oVel']);
    this.pPart = compile(gl, src['particle.vert'], src['streak.frag']);
    this.pFade = compile(gl, src['fullscreen.vert'], src['fade.frag']);
    this.pDown = compile(gl, src['fullscreen.vert'], src['bloom_down.frag']);
    this.pUp = compile(gl, src['fullscreen.vert'], src['bloom_up.frag']);
    this.pComp = compile(gl, src['fullscreen.vert'], src['composite.frag']);
    this.emptyVao = gl.createVertexArray();

    // Initial state: particles scattered in the ball with random ages (no synchronised births).
    const data = new Float32Array(this.N * 8);
    let s = 1234567;
    const rnd = () => (s = (s * 16807) % 2147483647) / 2147483647;
    for (let i = 0; i < this.N; i++) {
      let x, y, z;
      do { x = rnd() * 2 - 1; y = rnd() * 2 - 1; z = rnd() * 2 - 1; } while (x * x + y * y + z * z > 1);
      const o = i * 8;
      data[o] = x; data[o + 1] = y; data[o + 2] = z; data[o + 3] = rnd() * 12;
      data[o + 7] = rnd() * 1000;
    }
    const quad = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, quad);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([0, -1, 1, -1, 0, 1, 1, 1]), gl.STATIC_DRAW);
    this.bufs = [0, 1].map(() => { const b = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, b); gl.bufferData(gl.ARRAY_BUFFER, data, gl.DYNAMIC_COPY); return b; });
    this.simVao = this.bufs.map(b => {
      const v = gl.createVertexArray(); gl.bindVertexArray(v);
      gl.bindBuffer(gl.ARRAY_BUFFER, b);
      gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 4, gl.FLOAT, false, 32, 0);
      gl.enableVertexAttribArray(1); gl.vertexAttribPointer(1, 4, gl.FLOAT, false, 32, 16);
      return v;
    });
    this.drawVao = this.bufs.map(b => {
      const v = gl.createVertexArray(); gl.bindVertexArray(v);
      gl.bindBuffer(gl.ARRAY_BUFFER, quad);
      gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 8, 0);
      gl.bindBuffer(gl.ARRAY_BUFFER, b);
      gl.enableVertexAttribArray(1); gl.vertexAttribPointer(1, 4, gl.FLOAT, false, 32, 0); gl.vertexAttribDivisor(1, 1);
      gl.enableVertexAttribArray(2); gl.vertexAttribPointer(2, 4, gl.FLOAT, false, 32, 16); gl.vertexAttribDivisor(2, 1);
      return v;
    });
    gl.bindVertexArray(null);
    this.tf = gl.createTransformFeedback();
    this.count = this.N;
  }

  target(w, h) {
    const gl = this.gl;
    const tex = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, tex);
    if (this.hdr) gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA16F, w, h, 0, gl.RGBA, gl.HALF_FLOAT, null);
    else gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, w, h, 0, gl.RGBA, gl.UNSIGNED_BYTE, null);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    const fbo = gl.createFramebuffer();
    gl.bindFramebuffer(gl.FRAMEBUFFER, fbo);
    gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, tex, 0);
    gl.clearColor(0, 0, 0, 1); gl.clear(gl.COLOR_BUFFER_BIT);
    return { tex, fbo, w, h };
  }

  resize(w, h) {
    this.canvas.width = w; this.canvas.height = h;
    this.W = w; this.H = h;
    this.scene = this.target(w, h);
    this.mips = [];
    let mw = w >> 1, mh = h >> 1;
    for (let i = 0; i < 7 && mw > 4 && mh > 4; i++) { this.mips.push(this.target(mw, mh)); mw >>= 1; mh >>= 1; }
  }

  // One fixed simulation step.
  step(dt, p) {
    const gl = this.gl, S = this.pSim, t = this.simTime;
    const pr = p.preset;
    gl.useProgram(S.p);
    // Per-axis pull breathes on its own slow, unrelated rhythms: never the same shape twice.
    const ax = [drift(t, 0.61), drift(t + 40, 0.43), drift(t + 90, 0.77)];
    const energy = p.energy;
    gl.uniform1f(S.u.uTime, t);
    gl.uniform1f(S.u.uDt, dt);
    gl.uniform1f(S.u.uFrame, this.frame % 100000);
    gl.uniform3f(S.u.uAttract, pr.attract[0] * (1 + 0.55 * ax[0]), pr.attract[1] * (1 + 0.55 * ax[1]), pr.attract[2] * (1 + 0.55 * ax[2]));
    gl.uniform1f(S.u.uCurl, pr.curl * (0.8 + 0.4 * energy));
    gl.uniform1f(S.u.uCurlScale, pr.curlScale);
    gl.uniform1f(S.u.uCurlSpeed, 0.12 * energy);
    const a = t * 0.047, b = t * 0.031 + 1.0;
    gl.uniform3f(S.u.uSwirlAxis, Math.sin(a) * 0.45, Math.cos(b) * 0.3 + 0.85, Math.cos(a) * 0.45);
    gl.uniform1f(S.u.uSwirl, pr.swirl * (0.7 + 0.5 * energy) * (1 + 0.35 * drift(t + 13, 0.5)));
    gl.uniform1f(S.u.uShellR, 1.0 + p.dilate);
    gl.uniform1f(S.u.uLife, p.life);
    gl.uniform1f(S.u.uStreams, pr.streams);
    gl.uniform2f(S.u.uShock, (p.shock || [0, 0])[0], (p.shock || [0, 0])[1]);
    gl.uniform1f(S.u.uAmp, p.amp || 0);
    gl.uniform1f(S.u.uCoreR, 0.045);
    const src = this.cur, dst = 1 - src;
    gl.bindVertexArray(this.simVao[src]);
    gl.bindBuffer(gl.ARRAY_BUFFER, null);
    gl.bindTransformFeedback(gl.TRANSFORM_FEEDBACK, this.tf);
    gl.bindBufferBase(gl.TRANSFORM_FEEDBACK_BUFFER, 0, this.bufs[dst]);
    gl.enable(gl.RASTERIZER_DISCARD);
    gl.beginTransformFeedback(gl.POINTS);
    gl.drawArrays(gl.POINTS, 0, this.N);
    gl.endTransformFeedback();
    gl.disable(gl.RASTERIZER_DISCARD);
    gl.bindBufferBase(gl.TRANSFORM_FEEDBACK_BUFFER, 0, null);
    gl.bindTransformFeedback(gl.TRANSFORM_FEEDBACK, null);
    gl.bindVertexArray(null);
    this.cur = dst;
    this.simTime += dt;
    this.frame++;
  }

  // Advance real time dt with fixed 1/60 steps, then render.
  update(dt, p) {
    this.acc += Math.min(dt, 0.1);
    let n = 0;
    while (this.acc >= 1 / 60 && n < 3) { this.step(1 / 60, p); this.acc -= 1 / 60; n++; }
    this.draw(dt, p);
  }

  draw(dt, p) {
    const gl = this.gl, W = this.W, H = this.H, t = this.simTime;
    const hdrScale = this.hdr ? 1 : 6;
    const fovY = 30 * Math.PI / 180;
    const aspect = W / H;
    const tanX = Math.tan(fovY / 2) * aspect;
    const dist = Math.sqrt(1 + Math.pow(1 / (p.fill * tanX), 2));
    const proj = perspective(fovY, aspect, 0.1, 50);
    const par = p.parallax || [0, 0];
    const view = new Float32Array([1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, -par[0], -par[1] + p.lift, -dist, 1]);
    const model = mat3Mul(rotX(0.25 + drift(t, 0.53) * 0.1), rotY(t * 0.03));

    // ---- scene: fade previous frame (trails), then add streaks ----
    gl.bindFramebuffer(gl.FRAMEBUFFER, this.scene.fbo);
    gl.viewport(0, 0, W, H);
    gl.bindVertexArray(this.emptyVao);
    gl.enable(gl.BLEND);
    gl.blendFunc(gl.ZERO, gl.SRC_ALPHA);
    gl.useProgram(this.pFade.p);
    gl.uniform1f(this.pFade.u.uDecay, Math.pow(p.trail, Math.max(dt, 1 / 240) * 60));
    gl.drawArrays(gl.TRIANGLES, 0, 3);

    gl.blendFunc(gl.ONE, gl.ONE);
    const F = this.pPart; gl.useProgram(F.p);
    gl.uniformMatrix4fv(F.u.uView, false, view);
    gl.uniformMatrix4fv(F.u.uProj, false, proj);
    gl.uniformMatrix3fv(F.u.uModel, false, model);
    gl.uniform2f(F.u.uViewport, W, H);
    gl.uniform1f(F.u.uTime, t);
    gl.uniform1f(F.u.uPxScale, H / 2400);
    gl.uniform1f(F.u.uStreak, p.streak);
    gl.uniform1f(F.u.uFocusDist, dist - p.focus);
    gl.uniform1f(F.u.uCocScale, p.coc);
    gl.uniform1f(F.u.uMaxCoc, 14);
    gl.uniform3f(F.u.uCenterView, -par[0], -par[1] + p.lift, -dist);
    gl.uniform1f(F.u.uBodyRadius, 0.96);
    gl.uniform1f(F.u.uBodyDensity, p.bodyDensity);
    gl.uniform1f(F.u.uGain, p.gain * (1 - p.trail) * 3.3);
    gl.uniform1f(F.u.uCoreGain, p.coreGain);
    gl.uniform1f(F.u.uTwinkle, p.twinkle);
    gl.uniform1f(F.u.uAlert, p.alert);
    gl.uniform1f(F.u.uTemp, p.temp);
    gl.uniform1f(F.u.uDensity, p.density);
    gl.uniform1f(F.u.uLife, p.life);
    gl.uniform1f(F.u.uSweep, p.sweep);
    gl.uniform1f(F.u.uSweepPos, Math.sin(t * 1.7));
    gl.uniform3f(F.u.uSweepAxis, 0.3, 0.9, 0.3);
    gl.uniform4fv(F.u.uBands, p.bands || [0, 0, 0, 0]);
    gl.uniform2f(F.u.uShock, (p.shock || [0, 0])[0], (p.shock || [0, 0])[1]);
    const zones = p.zones || [];
    gl.uniform1i(F.u.uZoneCount, zones.length);
    if (zones.length) {
      gl.uniform4fv(F.u.uZones, new Float32Array(zones.flatMap(z => z.zone)));
      gl.uniform4fv(F.u.uZoneParams, new Float32Array(zones.flatMap(z => z.params)));
    }
    gl.bindVertexArray(this.drawVao[this.cur]);
    gl.drawArraysInstanced(gl.TRIANGLE_STRIP, 0, 4, this.N);
    gl.bindVertexArray(this.emptyVao);

    // ---- bloom ----
    const D = this.pDown;
    gl.disable(gl.BLEND);
    gl.useProgram(D.p);
    gl.uniform1i(D.u.uSrc, 0);
    gl.uniform1f(D.u.uThreshold, p.threshold);
    gl.uniform1f(D.u.uKnee, 0.5);
    let src = this.scene;
    gl.activeTexture(gl.TEXTURE0);
    this.mips.forEach((m, i) => {
      gl.bindFramebuffer(gl.FRAMEBUFFER, m.fbo);
      gl.viewport(0, 0, m.w, m.h);
      gl.bindTexture(gl.TEXTURE_2D, src.tex);
      gl.uniform2f(D.u.uTexel, 1 / src.w, 1 / src.h);
      gl.uniform1f(D.u.uPrefilter, i === 0 ? 1 : 0);
      gl.drawArrays(gl.TRIANGLES, 0, 3);
      src = m;
    });
    const U = this.pUp;
    gl.useProgram(U.p);
    gl.uniform1i(U.u.uSrc, 0);
    gl.enable(gl.BLEND); gl.blendFunc(gl.ONE, gl.ONE);
    for (let i = this.mips.length - 1; i > 0; i--) {
      const from = this.mips[i], to = this.mips[i - 1];
      gl.bindFramebuffer(gl.FRAMEBUFFER, to.fbo);
      gl.viewport(0, 0, to.w, to.h);
      gl.bindTexture(gl.TEXTURE_2D, from.tex);
      gl.uniform2f(U.u.uTexel, 1 / from.w, 1 / from.h);
      gl.uniform1f(U.u.uRadius, 1.0);
      gl.uniform1f(U.u.uWeight, p.bloomSpread);
      gl.drawArrays(gl.TRIANGLES, 0, 3);
    }
    gl.disable(gl.BLEND);

    // ---- composite ----
    const C = this.pComp;
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    gl.viewport(0, 0, W, H);
    gl.useProgram(C.p);
    gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, this.scene.tex);
    gl.activeTexture(gl.TEXTURE1); gl.bindTexture(gl.TEXTURE_2D, this.mips[0].tex);
    gl.uniform1i(C.u.uScene, 0);
    gl.uniform1i(C.u.uBloom, 1);
    gl.uniform2f(C.u.uResolution, W, H);
    const f = 1 / Math.tan(fovY / 2);
    const cy = (p.lift - par[1]) / dist * f;
    const cx = -par[0] / dist * f / aspect;
    const rNdc = (1 / Math.sqrt(dist * dist - 1)) * f;
    gl.uniform2f(C.u.uSphereCenter, (cx * 0.5 + 0.5) * W, (cy * 0.5 + 0.5) * H);
    gl.uniform1f(C.u.uSphereRadius, rNdc * 0.5 * H);
    gl.uniform1f(C.u.uBodyRadiusPx, rNdc * 0.5 * H * 0.96);
    gl.uniform1f(C.u.uBloomStrength, p.bloom);
    gl.uniform1f(C.u.uExposure, p.exposure);
    gl.uniform1f(C.u.uTime, t);
    gl.uniform1f(C.u.uHalo, p.halo);
    gl.uniform1f(C.u.uAlert, p.alert);
    gl.uniform1f(C.u.uHdrScale, hdrScale);
    gl.drawArrays(gl.TRIANGLES, 0, 3);
    gl.activeTexture(gl.TEXTURE0);
  }
}

// Organism presets (swipe) and default VEILLE look. Keep in sync with Organism.kt.
const PRESETS = {
  vortex:     { label: 'A · Vortex',     attract: [0.42, 0.30, 0.38], curl: 0.55, curlScale: 1.6, swirl: 0.55, streams: 0.35 },
  essaim:     { label: 'B · Essaim',     attract: [0.30, 0.30, 0.30], curl: 1.25, curlScale: 2.2, swirl: 0.12, streams: 0.15 },
  tentacules: { label: 'C · Tentacules', attract: [0.55, 0.16, 0.34], curl: 0.8,  curlScale: 1.3, swirl: 0.3,  streams: 0.8 },
};
const DEFAULTS = {
  fill: 0.86, lift: 0.0, focus: 0.75, coc: 6.0, bodyDensity: 1.3,
  dilate: 0.0, energy: 1.0, twinkle: 1.0, sweep: 0.0,
  gain: 0.4, coreGain: 1.0, alert: 0.0, density: 1.0, temp: 0.0,
  threshold: 0.9, bloom: 0.9, bloomSpread: 0.85, exposure: 1.0, halo: 1.0,
  trail: 0.9, streak: 0.05, life: 9.0,
};

window.Organism = Organism;
window.PRESETS = PRESETS;
window.DEFAULTS = DEFAULTS;
