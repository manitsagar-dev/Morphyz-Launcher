package org.angelauramc.methodsInjectorAgent.renderpearl_gl;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

/**
 * Two surgical patches for MC 26.x GlBackend on Android:
 *
 * <h2>1. loadLibrary() - skip the glGetError pointer check (MC-311954 / Iris#3345)</h2>
 * <p>{@code if (lwjglGlGetError != SDL_GL_GetProcAddress(glGetError)) throw} can never
 * pass on Android: SDL resolves GL entry points via eglGetProcAddress() (vendor driver
 * pointers) while LWJGL binds the launcher's renderer library (gl4es). Only the
 * comparison is neutralized - the rest of the method MUST keep running. In particular
 * SDL_GL_LoadLibrary(LWJGL's GL path) has to be called: without it SDL's GL/EGL stack
 * is never initialized and the later SDL_CreateWindow(..., OPENGL) in GlDevice fails
 * with "Could not initialize OpenGL / GLES library".</p>
 * <p>Implementation: right before the LCMP the bytecode becomes POP2 + DUP2 so the
 * comparison always sees [lwjglPtr, lwjglPtr] and takes the original 'equal' branch.
 * Straight-line stack surgery - no branch targets, no dead code, StackMapTable stays
 * valid, COMPUTE_MAXS suffices.</p>
 *
 * <h2>2. createWindow() - force SDL_GL_CONTEXT_PROFILE_MASK to ES (EGL_BAD_ATTRIBUTE)</h2>
 * <p>GlBackend.createWindow() requests a GL 3.3 <b>CORE</b> context:
 * {@code SDL_GL_SetAttribute(20 /* PROFILE_MASK *&#47;, 1 /* CORE *&#47;)}. SDL's
 * EGL layer then sets EGL_RENDERABLE_TYPE = EGL_OPENGL_BIT and eglBindAPI(EGL_OPENGL_API)
 * (SDL_egl.c, non-ES branch of SDL_EGL_ChooseConfig). Android's system EGL (Adreno)
 * only exposes OpenGL ES - there is no EGL_OPENGL_BIT - so eglChooseConfig fails with
 * EGL_BAD_ATTRIBUTE and device creation dies at GlDevice.&lt;init&gt; line 83:
 * "Couldn't find matching EGL config".</p>
 * <p>Forcing the profile to ES (0x0004) makes SDL build an ES config instead
 * (EGL_OPENGL_ES3_BIT_KHR + eglBindAPI(EGL_OPENGL_ES_API)), which Adreno accepts, and
 * the launcher renderer (gl4es) provides the desktop GL API on top of that ES context -
 * the exact stack every Android MC launcher has always used.</p>
 * <p>Implementation: in createWindow, the sequence
 * {@code BIPUSH 20; ICONST_1; INVOKESTATIC SDL_GL_SetAttribute} is rewritten to push
 * ICONST_4 (SDL_GL_CONTEXT_PROFILE_ES) instead of ICONST_1 (SDL_GL_CONTEXT_PROFILE_CORE).
 * One-instruction hold buffer; every other instruction flushes the buffer unchanged, so
 * the four other SetAttribute calls and all other code pass through untouched.</p>
 */
public class GlBackendLoadLibraryInjector {

    private static final String TARGET_CLASS = "com/mojang/renderpearl/backend/opengl/GlBackend";
    private static final String METHOD_LOAD_LIBRARY = "loadLibrary";
    private static final String METHOD_CREATE_WINDOW = "createWindow";
    private static final String SDL_OWNER = "org/lwjgl/sdl/SDLVideo";
    private static final String SDL_GET_PROC = "SDL_GL_GetProcAddress";
    private static final String SDL_SET_ATTR = "SDL_GL_SetAttribute";
    /** SDL_GL_CONTEXT_PROFILE_MASK attribute index (SDL_GLattr). */
    private static final int GL_ATTR_PROFILE_MASK = 20;
    /** SDL_GL_CONTEXT_PROFILE_ES - see SDL_gl.h / SDL_video.h: CORE=1, COMPAT=2, ES=4. */
    private static final int GL_PROFILE_ES = 4;

    public static void premain(String args, Instrumentation inst) {
        // Never let a transformer bug abort JVM startup: an exception escaping
        // premain kills the whole game launch.
        try {
            inst.addTransformer(new ClassFileTransformer() {
                @Override
                public byte[] transform(ClassLoader loader, String name, Class<?> classBeingRedefined,
                                        ProtectionDomain protectionDomain, byte[] classfileBuffer) {
                    if (classfileBuffer == null || !TARGET_CLASS.equals(name)) {
                        return null;
                    }
                    try {
                        ClassReader cr = new ClassReader(classfileBuffer);
                        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
                        GlBackendPatch visitor = new GlBackendPatch(cw);
                        cr.accept(visitor, 0);
                        if (!visitor.methodFound) {
                            log("GlBackend loadLibrary()/createWindow() not found - MC changed, GlBackend injector inactive");
                            return null;
                        }
                        if (!visitor.loadLibraryPatched && !visitor.createWindowPatched) {
                            log("GlBackend patterns not found (glGetError LCMP / SetAttribute(20,1)) - patch not applied");
                            return null;
                        }
                        if (visitor.loadLibraryPatched) {
                            log("Neutralized GlBackend.loadLibrary() glGetError sanity check, kept SDL_GL_LoadLibrary " +
                                    "(MC-311954 / Iris#3345 workaround)");
                        } else {
                            log("GlBackend.loadLibrary() glGetError comparison not found - loadLibrary patch skipped");
                        }
                        if (visitor.createWindowPatched) {
                            log("Forced SDL_GL_CONTEXT_PROFILE_MASK to ES in GlBackend.createWindow " +
                                    "(Android EGL has no EGL_OPENGL_BIT)");
                        } else {
                            log("GlBackend.createWindow() SetAttribute(20,1) not found - ES profile patch skipped");
                        }
                        return cw.toByteArray();
                    } catch (Throwable t) {
                        log("Failed to patch GlBackend: " + t);
                        return null;
                    }
                }
            });
        } catch (Throwable t) {
            log("Could not register GlBackend transformer: " + t);
        }
    }

    private static void log(String message) {
        try { // Minecraft makes it ugly if we use println
            System.out.write(("Morphyz Launcher: " + message + "\n").getBytes());
            System.out.flush();
        } catch (Exception ignored) {}
    }

    private static class GlBackendPatch extends ClassVisitor {
        boolean methodFound = false;
        boolean loadLibraryPatched = false;
        boolean createWindowPatched = false;

        GlBackendPatch(ClassVisitor cv) {
            super(Opcodes.ASM9, cv);
        }

        @Override
        public MethodVisitor visitMethod(int access, final String name, final String desc,
                                         String signature, String[] exceptions) {
            // NOTE: named "delegate", not "mv" - an anonymous MethodVisitor
            // inherits a protected field called mv (null here), which would shadow
            // a local named mv and NPE on first use.
            MethodVisitor delegate = super.visitMethod(access, name, desc, signature, exceptions);
            if (METHOD_LOAD_LIBRARY.equals(name)) {
                methodFound = true;
                return new LoadLibraryPatch(delegate);
            }
            if (METHOD_CREATE_WINDOW.equals(name)) {
                methodFound = true;
                return new CreateWindowEsProfilePatch(delegate);
            }
            return delegate;
        }

        /** Keeps SDL_GL_LoadLibrary(); makes the glGetError comparison always equal. */
        private class LoadLibraryPatch extends MethodVisitor {
            private final MethodVisitor out;
            /** True only when the immediately preceding real instruction was
             * {@code SDLVideo.SDL_GL_GetProcAddress(...)} (labels/line numbers/frames
             * do not count as real instructions). */
            private boolean prevWasSdlGetProc = false;

            LoadLibraryPatch(MethodVisitor delegate) {
                super(Opcodes.ASM9, delegate);
                this.out = delegate;
            }

            private void realInsn() {
                prevWasSdlGetProc = false;
            }

            @Override
            public void visitInsn(int opcode) {
                if (opcode == Opcodes.LCMP && prevWasSdlGetProc) {
                    // stack: [lwjglGlGetError, sdlGlGetProcAddr]
                    //        -> [lwjglGlGetError, lwjglGlGetError]
                    out.visitInsn(Opcodes.POP2);
                    out.visitInsn(Opcodes.DUP2);
                    loadLibraryPatched = true;
                    prevWasSdlGetProc = false;
                    out.visitInsn(opcode);
                    return;
                }
                if (opcode != Opcodes.LCMP) {
                    realInsn();
                }
                out.visitInsn(opcode);
            }

            @Override
            public void visitMethodInsn(int opcode, String owner, String methodName,
                                        String descriptor, boolean isInterface) {
                prevWasSdlGetProc = opcode == Opcodes.INVOKESTATIC
                        && SDL_OWNER.equals(owner) && SDL_GET_PROC.equals(methodName);
                out.visitMethodInsn(opcode, owner, methodName, descriptor, isInterface);
            }

            @Override
            public void visitVarInsn(int opcode, int var) {
                realInsn();
                out.visitVarInsn(opcode, var);
            }

            @Override
            public void visitFieldInsn(int opcode, String owner, String fieldName, String descriptor) {
                realInsn();
                out.visitFieldInsn(opcode, owner, fieldName, descriptor);
            }

            @Override
            public void visitJumpInsn(int opcode, Label label) {
                realInsn();
                out.visitJumpInsn(opcode, label);
            }

            @Override
            public void visitLdcInsn(Object value) {
                realInsn();
                out.visitLdcInsn(value);
            }

            @Override
            public void visitIntInsn(int opcode, int operand) {
                realInsn();
                out.visitIntInsn(opcode, operand);
            }

            @Override
            public void visitInvokeDynamicInsn(String name, String descriptor,
                                               org.objectweb.asm.Handle bootstrapMethodHandle,
                                               Object... bootstrapMethodArguments) {
                realInsn();
                out.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
            }

            @Override
            public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
                realInsn();
                out.visitTableSwitchInsn(min, max, dflt, labels);
            }

            @Override
            public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
                realInsn();
                out.visitLookupSwitchInsn(dflt, keys, labels);
            }

            @Override
            public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
                realInsn();
                out.visitMultiANewArrayInsn(descriptor, numDimensions);
            }
        }

        /**
         * Rewrites {@code SetAttribute(20, 1)} (PROFILE_MASK = CORE) to
         * {@code SetAttribute(20, 4)} (PROFILE_MASK = ES) in createWindow().
         *
         * <p>Holds {@code BIPUSH 20} and the following {@code ICONST_1}; if the next
         * real instruction is the SDL_GL_SetAttribute invoke, emits ICONST_4 instead.
         * Any other instruction flushes the held pair unchanged.</p>
         */
        private class CreateWindowEsProfilePatch extends MethodVisitor {
            private final MethodVisitor out;
            private boolean heldAttr20 = false;
            private int heldAttr20Opcode = -1;
            private boolean heldValue1 = false;

            CreateWindowEsProfilePatch(MethodVisitor delegate) {
                super(Opcodes.ASM9, delegate);
                this.out = delegate;
            }

            private void flush() {
                if (heldAttr20) {
                    out.visitIntInsn(heldAttr20Opcode, GL_ATTR_PROFILE_MASK);
                    heldAttr20 = false;
                }
                if (heldValue1) {
                    out.visitInsn(Opcodes.ICONST_1);
                    heldValue1 = false;
                }
            }

            @Override
            public void visitIntInsn(int opcode, int operand) {
                if (!heldAttr20 && !heldValue1
                        && operand == GL_ATTR_PROFILE_MASK
                        && (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH)) {
                    heldAttr20 = true;
                    heldAttr20Opcode = opcode;
                    return;
                }
                flush();
                out.visitIntInsn(opcode, operand);
            }

            @Override
            public void visitInsn(int opcode) {
                if (opcode == Opcodes.ICONST_1 && heldAttr20 && !heldValue1) {
                    heldValue1 = true;
                    return;
                }
                flush();
                out.visitInsn(opcode);
            }

            @Override
            public void visitMethodInsn(int opcode, String owner, String methodName,
                                        String descriptor, boolean isInterface) {
                if (opcode == Opcodes.INVOKESTATIC
                        && SDL_OWNER.equals(owner) && SDL_SET_ATTR.equals(methodName)
                        && heldAttr20 && heldValue1) {
                    // Replace CORE(1) with ES(4): same single-slot push, same stack shape.
                    out.visitInsn(GL_PROFILE_ES == 4 ? Opcodes.ICONST_4 : Opcodes.ICONST_1);
                    heldAttr20 = false;
                    heldValue1 = false;
                    createWindowPatched = true;
                    out.visitMethodInsn(opcode, owner, methodName, descriptor, isInterface);
                    return;
                }
                flush();
                out.visitMethodInsn(opcode, owner, methodName, descriptor, isInterface);
            }

            // Everything else flushes any held instructions first so order is preserved.
            @Override
            public void visitLabel(Label label) {
                flush();
                out.visitLabel(label);
            }

            @Override
            public void visitLineNumber(int line, Label start) {
                flush();
                out.visitLineNumber(line, start);
            }

            @Override
            public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
                flush();
                out.visitFrame(type, numLocal, local, numStack, stack);
            }

            @Override
            public void visitVarInsn(int opcode, int var) {
                flush();
                out.visitVarInsn(opcode, var);
            }

            @Override
            public void visitFieldInsn(int opcode, String owner, String fieldName, String descriptor) {
                flush();
                out.visitFieldInsn(opcode, owner, fieldName, descriptor);
            }

            @Override
            public void visitJumpInsn(int opcode, Label label) {
                flush();
                out.visitJumpInsn(opcode, label);
            }

            @Override
            public void visitLdcInsn(Object value) {
                flush();
                out.visitLdcInsn(value);
            }

            @Override
            public void visitInvokeDynamicInsn(String name, String descriptor,
                                               org.objectweb.asm.Handle bootstrapMethodHandle,
                                               Object... bootstrapMethodArguments) {
                flush();
                out.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
            }

            @Override
            public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
                flush();
                out.visitTableSwitchInsn(min, max, dflt, labels);
            }

            @Override
            public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
                flush();
                out.visitLookupSwitchInsn(dflt, keys, labels);
            }

            @Override
            public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
                flush();
                out.visitMultiANewArrayInsn(descriptor, numDimensions);
            }

            @Override
            public void visitEnd() {
                flush();
                super.visitEnd();
            }
        }
    }
}
