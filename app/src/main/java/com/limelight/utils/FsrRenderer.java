package com.limelight.utils;

import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.view.Surface;

import com.limelight.LimeLog;
import com.limelight.preferences.PreferenceConfiguration;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Experimental AMD FidelityFX Super Resolution 1.0 (EASU upscale + RCAS sharpen) video path.
 *
 * Decoder -> SurfaceTexture (OES) -> copy to RGBA texture at stream size
 *         -> EASU to view size -> RCAS to the window.
 *
 * Shaders are a GLSL ES 3.00 port of ffx_fsr1.h:
 * Copyright (c) 2021 Advanced Micro Devices, Inc. All rights reserved. (MIT License)
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software
 * and associated documentation files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions: The above copyright notice
 * and this permission notice shall be included in all copies or substantial portions of the
 * Software. THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND.
 */
public class FsrRenderer implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {
    // Sharpness in stops (0 = maximum sharpening); AMD's default is 0.2
    private static final float RCAS_SHARPNESS_STOPS = 0.2f;

    public static volatile boolean isActive = false;
    // Time from the decoder releasing a frame to the upscaled frame being submitted to the GPU
    public static volatile float drawDelayMs = 0.0f;

    private static final float[] QUAD = {-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f};
    private static final float[] QUAD_TEX = {0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f};

    private static final String VERTEX_SHADER =
            "#version 300 es\n" +
            "in vec4 aPos;\n" +
            "in vec4 aTex;\n" +
            "uniform mat4 uSt;\n" +
            "out vec2 vTex;\n" +
            "void main() {\n" +
            "  gl_Position = aPos;\n" +
            "  vTex = (uSt * aTex).xy;\n" +
            "}\n";

    private static final String COPY_SHADER =
            "#version 300 es\n" +
            "#extension GL_OES_EGL_image_external_essl3 : require\n" +
            "precision mediump float;\n" +
            "uniform samplerExternalOES uTex;\n" +
            "in vec2 vTex;\n" +
            "out vec4 oColor;\n" +
            "void main() {\n" +
            "  oColor = vec4(texture(uTex, vTex).rgb, 1.0);\n" +
            "}\n";

    private static final String EASU_SHADER =
            "#version 300 es\n" +
            "precision highp float;\n" +
            "precision highp int;\n" +
            "uniform sampler2D uTex;\n" +
            "uniform vec2 uScale;\n" +      // input size / output size
            "uniform vec2 uOffset;\n" +     // 0.5 * uScale - 0.5
            "uniform ivec2 uInputSize;\n" +
            "out vec4 oColor;\n" +
            "vec3 fetch(ivec2 p) {\n" +
            "  return texelFetch(uTex, clamp(p, ivec2(0), uInputSize - 1), 0).rgb;\n" +
            "}\n" +
            "float luma(vec3 c) { return c.b * 0.5 + (c.r * 0.5 + c.g); }\n" +
            "void easuSet(inout vec2 dir, inout float len, float w,\n" +
            "             float lA, float lB, float lC, float lD, float lE) {\n" +
            "  float lenX = max(abs(lD - lC), abs(lC - lB));\n" +
            "  lenX = 1.0 / max(lenX, 1.0 / 65536.0);\n" +
            "  float dirX = lD - lB;\n" +
            "  dir.x += dirX * w;\n" +
            "  lenX = clamp(abs(dirX) * lenX, 0.0, 1.0);\n" +
            "  len += lenX * lenX * w;\n" +
            "  float lenY = max(abs(lE - lC), abs(lC - lA));\n" +
            "  lenY = 1.0 / max(lenY, 1.0 / 65536.0);\n" +
            "  float dirY = lE - lA;\n" +
            "  dir.y += dirY * w;\n" +
            "  lenY = clamp(abs(dirY) * lenY, 0.0, 1.0);\n" +
            "  len += lenY * lenY * w;\n" +
            "}\n" +
            "void easuTap(inout vec3 aC, inout float aW, vec2 off, vec2 dir, vec2 len,\n" +
            "             float lob, float clp, vec3 c) {\n" +
            "  vec2 v = vec2(off.x * dir.x + off.y * dir.y, off.x * (-dir.y) + off.y * dir.x) * len;\n" +
            "  float d2 = min(v.x * v.x + v.y * v.y, clp);\n" +
            "  float wB = (2.0 / 5.0) * d2 - 1.0;\n" +
            "  float wA = lob * d2 - 1.0;\n" +
            "  wB *= wB;\n" +
            "  wA *= wA;\n" +
            "  wB = (25.0 / 16.0) * wB - (25.0 / 16.0 - 1.0);\n" +
            "  float w = wB * wA;\n" +
            "  aC += c * w;\n" +
            "  aW += w;\n" +
            "}\n" +
            "void main() {\n" +
            "  vec2 pp = floor(gl_FragCoord.xy) * uScale + uOffset;\n" +
            "  vec2 fp = floor(pp);\n" +
            "  pp -= fp;\n" +
            "  ivec2 f0 = ivec2(fp);\n" +
            //    b c
            //  e f g h
            //  i j k l
            //    n o
            "  vec3 b = fetch(f0 + ivec2(0, -1)); vec3 c = fetch(f0 + ivec2(1, -1));\n" +
            "  vec3 e = fetch(f0 + ivec2(-1, 0)); vec3 f = fetch(f0);\n" +
            "  vec3 g = fetch(f0 + ivec2(1, 0));  vec3 h = fetch(f0 + ivec2(2, 0));\n" +
            "  vec3 i = fetch(f0 + ivec2(-1, 1)); vec3 j = fetch(f0 + ivec2(0, 1));\n" +
            "  vec3 k = fetch(f0 + ivec2(1, 1));  vec3 l = fetch(f0 + ivec2(2, 1));\n" +
            "  vec3 n = fetch(f0 + ivec2(0, 2));  vec3 o = fetch(f0 + ivec2(1, 2));\n" +
            "  float bL = luma(b), cL = luma(c), eL = luma(e), fL = luma(f), gL = luma(g), hL = luma(h);\n" +
            "  float iL = luma(i), jL = luma(j), kL = luma(k), lL = luma(l), nL = luma(n), oL = luma(o);\n" +
            "  vec2 dir = vec2(0.0);\n" +
            "  float len = 0.0;\n" +
            "  easuSet(dir, len, (1.0 - pp.x) * (1.0 - pp.y), bL, eL, fL, gL, jL);\n" +
            "  easuSet(dir, len, pp.x * (1.0 - pp.y), cL, fL, gL, hL, kL);\n" +
            "  easuSet(dir, len, (1.0 - pp.x) * pp.y, fL, iL, jL, kL, nL);\n" +
            "  easuSet(dir, len, pp.x * pp.y, gL, jL, kL, lL, oL);\n" +
            "  float dirR = dir.x * dir.x + dir.y * dir.y;\n" +
            "  bool zro = dirR < (1.0 / 32768.0);\n" +
            "  dirR = zro ? 1.0 : inversesqrt(dirR);\n" +
            "  dir.x = zro ? 1.0 : dir.x;\n" +
            "  dir *= dirR;\n" +
            "  len = len * 0.5;\n" +
            "  len *= len;\n" +
            "  float stretch = (dir.x * dir.x + dir.y * dir.y) / max(abs(dir.x), abs(dir.y));\n" +
            "  vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 - 0.5 * len);\n" +
            "  float lob = 0.5 + ((1.0 / 4.0 - 0.04) - 0.5) * len;\n" +
            "  float clp = 1.0 / lob;\n" +
            "  vec3 min4 = min(min(f, g), min(j, k));\n" +
            "  vec3 max4 = max(max(f, g), max(j, k));\n" +
            "  vec3 aC = vec3(0.0);\n" +
            "  float aW = 0.0;\n" +
            "  easuTap(aC, aW, vec2( 0.0, -1.0) - pp, dir, len2, lob, clp, b);\n" +
            "  easuTap(aC, aW, vec2( 1.0, -1.0) - pp, dir, len2, lob, clp, c);\n" +
            "  easuTap(aC, aW, vec2(-1.0,  1.0) - pp, dir, len2, lob, clp, i);\n" +
            "  easuTap(aC, aW, vec2( 0.0,  1.0) - pp, dir, len2, lob, clp, j);\n" +
            "  easuTap(aC, aW, vec2( 0.0,  0.0) - pp, dir, len2, lob, clp, f);\n" +
            "  easuTap(aC, aW, vec2(-1.0,  0.0) - pp, dir, len2, lob, clp, e);\n" +
            "  easuTap(aC, aW, vec2( 1.0,  1.0) - pp, dir, len2, lob, clp, k);\n" +
            "  easuTap(aC, aW, vec2( 2.0,  1.0) - pp, dir, len2, lob, clp, l);\n" +
            "  easuTap(aC, aW, vec2( 2.0,  0.0) - pp, dir, len2, lob, clp, h);\n" +
            "  easuTap(aC, aW, vec2( 1.0,  0.0) - pp, dir, len2, lob, clp, g);\n" +
            "  easuTap(aC, aW, vec2( 1.0,  2.0) - pp, dir, len2, lob, clp, o);\n" +
            "  easuTap(aC, aW, vec2( 0.0,  2.0) - pp, dir, len2, lob, clp, n);\n" +
            "  oColor = vec4(min(max4, max(min4, aC / aW)), 1.0);\n" +
            "}\n";

    private static final String RCAS_SHADER =
            "#version 300 es\n" +
            "precision highp float;\n" +
            "precision highp int;\n" +
            "uniform sampler2D uTex;\n" +
            "uniform float uSharpness;\n" + // exp2(-stops)
            "uniform ivec2 uSize;\n" +
            "out vec4 oColor;\n" +
            "vec3 fetch(ivec2 p) {\n" +
            "  return texelFetch(uTex, clamp(p, ivec2(0), uSize - 1), 0).rgb;\n" +
            "}\n" +
            "void main() {\n" +
            "  ivec2 sp = ivec2(gl_FragCoord.xy);\n" +
            //    b
            //  d e f
            //    h
            "  vec3 b = fetch(sp + ivec2(0, -1));\n" +
            "  vec3 d = fetch(sp + ivec2(-1, 0));\n" +
            "  vec3 e = fetch(sp);\n" +
            "  vec3 f = fetch(sp + ivec2(1, 0));\n" +
            "  vec3 h = fetch(sp + ivec2(0, 1));\n" +
            "  vec3 mn4 = min(min(b, d), min(f, h));\n" +
            "  vec3 mx4 = max(max(b, d), max(f, h));\n" +
            "  const float limit = 0.25 - (1.0 / 16.0);\n" +
            "  vec3 hitMin = mn4 / max(4.0 * mx4, vec3(1.0 / 65536.0));\n" +
            "  vec3 hitMax = (1.0 - mx4) / min(4.0 * mn4 - 4.0, vec3(-1.0 / 65536.0));\n" +
            "  vec3 lobeRGB = max(-hitMin, hitMax);\n" +
            "  float lobe = max(-limit, min(max(lobeRGB.r, max(lobeRGB.g, lobeRGB.b)), 0.0)) * uSharpness;\n" +
            "  float rcpL = 1.0 / (4.0 * lobe + 1.0);\n" +
            "  oColor = vec4(((b + d + f + h) * lobe + e) * rcpL, 1.0);\n" +
            "}\n";

    private final GLSurfaceView glSurfaceView;
    private final Stereo3DRenderer.OnSurfaceReadyListener listener;
    private final PreferenceConfiguration prefConfig;

    private final FloatBuffer quadBuffer;
    private final FloatBuffer texBuffer;
    private final float[] stMatrix = new float[16];

    private SurfaceTexture surfaceTexture;
    private Surface surface;

    private int oesTexture;
    private int copyProgram, easuProgram, rcasProgram;
    private int inputTexture, inputFbo, inputWidth, inputHeight;
    private int easuTexture, easuFbo, outputWidth, outputHeight;

    private volatile boolean frameAvailable;

    public FsrRenderer(GLSurfaceView view, Stereo3DRenderer.OnSurfaceReadyListener listener,
                       PreferenceConfiguration prefConfig) {
        this.glSurfaceView = view;
        this.listener = listener;
        this.prefConfig = prefConfig;

        quadBuffer = ByteBuffer.allocateDirect(QUAD.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quadBuffer.put(QUAD).position(0);
        texBuffer = ByteBuffer.allocateDirect(QUAD_TEX.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        texBuffer.put(QUAD_TEX).position(0);
    }

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        frameAvailable = true;
        glSurfaceView.requestRender();
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        oesTexture = tex[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        copyProgram = createProgram(VERTEX_SHADER, COPY_SHADER);
        easuProgram = createProgram(VERTEX_SHADER, EASU_SHADER);
        rcasProgram = createProgram(VERTEX_SHADER, RCAS_SHADER);

        surfaceTexture = new SurfaceTexture(oesTexture);
        surfaceTexture.setDefaultBufferSize(prefConfig.width, prefConfig.height);
        surfaceTexture.setOnFrameAvailableListener(this);
        surface = new Surface(surfaceTexture);

        isActive = true;
        LimeLog.info("FSR renderer created, stream " + prefConfig.width + "x" + prefConfig.height);

        if (listener != null) {
            listener.onStereo3DSurfaceReady(surface);
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        outputWidth = width;
        outputHeight = height;
        if (easuTexture != 0) {
            GLES20.glDeleteTextures(1, new int[]{easuTexture}, 0);
            GLES20.glDeleteFramebuffers(1, new int[]{easuFbo}, 0);
        }
        int[] res = createTargetTexture(width, height);
        easuTexture = res[0];
        easuFbo = res[1];
        LimeLog.info("FSR output " + width + "x" + height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        if (!frameAvailable || surfaceTexture == null || copyProgram == 0 || easuProgram == 0 || rcasProgram == 0) {
            return;
        }
        frameAvailable = false;

        surfaceTexture.updateTexImage();
        surfaceTexture.getTransformMatrix(stMatrix);

        // (Re)allocate the input target if the stream resolution changed
        if (inputWidth != prefConfig.width || inputHeight != prefConfig.height || inputTexture == 0) {
            if (inputTexture != 0) {
                GLES20.glDeleteTextures(1, new int[]{inputTexture}, 0);
                GLES20.glDeleteFramebuffers(1, new int[]{inputFbo}, 0);
            }
            inputWidth = prefConfig.width;
            inputHeight = prefConfig.height;
            int[] res = createTargetTexture(inputWidth, inputHeight);
            inputTexture = res[0];
            inputFbo = res[1];
        }

        // Pass 1: external OES frame -> RGBA texture at stream resolution
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, inputFbo);
        GLES20.glViewport(0, 0, inputWidth, inputHeight);
        GLES20.glUseProgram(copyProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(copyProgram, "uTex"), 0);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(copyProgram, "uSt"), 1, false, stMatrix, 0);
        drawQuad(copyProgram);

        // Pass 2: EASU upscale -> view resolution
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, easuFbo);
        GLES20.glViewport(0, 0, outputWidth, outputHeight);
        GLES20.glUseProgram(easuProgram);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexture);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(easuProgram, "uTex"), 0);
        float scaleX = (float) inputWidth / outputWidth;
        float scaleY = (float) inputHeight / outputHeight;
        GLES20.glUniform2f(GLES20.glGetUniformLocation(easuProgram, "uScale"), scaleX, scaleY);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(easuProgram, "uOffset"), 0.5f * scaleX - 0.5f, 0.5f * scaleY - 0.5f);
        GLES20.glUniform2i(GLES20.glGetUniformLocation(easuProgram, "uInputSize"), inputWidth, inputHeight);
        drawQuad(easuProgram);

        // Pass 3: RCAS sharpen -> window
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, outputWidth, outputHeight);
        GLES20.glUseProgram(rcasProgram);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, easuTexture);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(rcasProgram, "uTex"), 0);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(rcasProgram, "uSharpness"), (float) Math.pow(2.0, -RCAS_SHARPNESS_STOPS));
        GLES20.glUniform2i(GLES20.glGetUniformLocation(rcasProgram, "uSize"), outputWidth, outputHeight);
        drawQuad(rcasProgram);

        // The decoder releases frames with a System.nanoTime() timestamp in low latency pacing modes
        long ageNs = System.nanoTime() - surfaceTexture.getTimestamp();
        if (ageNs > 0 && ageNs < 1_000_000_000L) {
            drawDelayMs = drawDelayMs * 0.9f + (ageNs / 1_000_000f) * 0.1f;
        }
    }

    private void drawQuad(int program) {
        int pos = GLES20.glGetAttribLocation(program, "aPos");
        int tex = GLES20.glGetAttribLocation(program, "aTex");
        if (program != copyProgram) {
            // Identity transform for passes that use gl_FragCoord
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uSt"), 1, false, IDENTITY, 0);
        }
        GLES20.glEnableVertexAttribArray(pos);
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, quadBuffer);
        if (tex >= 0) {
            GLES20.glEnableVertexAttribArray(tex);
            GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 0, texBuffer);
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(pos);
        if (tex >= 0) {
            GLES20.glDisableVertexAttribArray(tex);
        }
    }

    private static final float[] IDENTITY = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};

    public void onSurfaceDestroyed() {
        isActive = false;
        drawDelayMs = 0.0f;
        if (surface != null) {
            surface.release();
            surface = null;
        }
        if (surfaceTexture != null) {
            surfaceTexture.release();
            surfaceTexture = null;
        }
        final int[] textures = {oesTexture, inputTexture, easuTexture};
        final int[] fbos = {inputFbo, easuFbo};
        final int[] programs = {copyProgram, easuProgram, rcasProgram};
        oesTexture = inputTexture = easuTexture = inputFbo = easuFbo = 0;
        copyProgram = easuProgram = rcasProgram = 0;
        glSurfaceView.queueEvent(() -> {
            GLES20.glDeleteTextures(textures.length, textures, 0);
            GLES20.glDeleteFramebuffers(fbos.length, fbos, 0);
            for (int p : programs) {
                GLES20.glDeleteProgram(p);
            }
        });
    }

    // Returns {texture, framebuffer}
    private static int[] createTargetTexture(int width, int height) {
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        int[] fbo = new int[1];
        GLES20.glGenFramebuffers(1, fbo, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0]);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex[0], 0);
        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            LimeLog.severe("FSR framebuffer incomplete: " + status);
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        return new int[]{tex[0], fbo[0]};
    }

    private static int loadShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            LimeLog.severe("FSR shader compile failed: " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    private static int createProgram(String vertex, String fragment) {
        int vs = loadShader(GLES20.GL_VERTEX_SHADER, vertex);
        int fs = loadShader(GLES20.GL_FRAGMENT_SHADER, fragment);
        if (vs == 0 || fs == 0) {
            return 0;
        }
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vs);
        GLES20.glAttachShader(program, fs);
        GLES20.glLinkProgram(program);
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        int[] linked = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
        if (linked[0] != GLES20.GL_TRUE) {
            LimeLog.severe("FSR program link failed: " + GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteProgram(program);
            return 0;
        }
        return program;
    }
}
