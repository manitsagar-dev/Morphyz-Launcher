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
 * MC 26.x GlBackend.loadLibrary() sanity-checks that the address of glGetError()
 * obtained through SDL_GL_GetProcAddress matches the one LWJGL bound, and throws
 * BackendCreationException("glGetError mismatch") otherwise. MC then falls back to
 * Vulkan, which cannot initialize on devices whose system Vulkan driver is below
 * 1.2 (exit code / fatal signal 6).
 *
 * On Android the comparison can never succeed: SDL resolves GL entry points via
 * eglGetProcAddress() (vendor driver pointers) while LWJGL binds the launcher's
 * renderer library (gl4es). The check is redundant because LWJGL already loads and
 * binds every GL function the game uses - the same conclusion reached for the
 * desktop variant of this bug (Mojang MC-311954, Iris #3345).
 *
 * IMPORTANT: only the comparison is neutralized - the rest of the method MUST keep
 * running. In particular SDL_GL_LoadLibrary(LWJGL's GL path) has to be called:
 * without it SDL's GL/EGL stack is never initialized and the later
 * SDL_CreateWindow(..., OPENGL) in GlDevice fails with
 * "Could not initialize OpenGL / GLES library" (seen after an earlier
 * whole-body no-op of this method). So we surgically rewrite the bytecode of
 *
 *     if (lwjglGlGetError != sdlGlGetProcAddr) { throw ...; }
 *
 * into an always-equal comparison by replacing the right-hand operand with the
 * left-hand one right before the LCMP:
 *
 *     ... invoke SDL_GL_GetProcAddress  ->  stack: [lwjglPtr, sdlPtr]
 *     POP2                             ->  stack: [lwjglPtr]
 *     DUP2                             ->  stack: [lwjglPtr, lwjglPtr]
 *     LCMP / IFEQ (taken)              ->  always "equal", original flow continues
 *
 * Straight-line stack surgery: no branch targets, no dead code, no stack-map
 * frames change (still two longs at the LCMP), so COMPUTE_MAXS stays sufficient.
 */
public class GlBackendLoadLibraryInjector {

    private static final String TARGET_CLASS = "com/mojang/renderpearl/backend/opengl/GlBackend";
    private static final String TARGET_METHOD = "loadLibrary";
    private static final String SDL_OWNER = "org/lwjgl/sdl/SDLVideo";
    private static final String SDL_GET_PROC = "SDL_GL_GetProcAddress";

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
                        SurgicalPatch visitor = new SurgicalPatch(cw);
                        cr.accept(visitor, 0);
                        if (!visitor.methodFound) {
                            log("GlBackend.loadLibrary() not found - MC changed, GlBackend injector inactive");
                            return null;
                        }
                        if (!visitor.patched) {
                            log("GlBackend.loadLibrary() glGetError comparison not found - " +
                                    "bytecode changed, patch not applied");
                            return null;
                        }
                        log("Neutralized GlBackend.loadLibrary() glGetError sanity check, " +
                                "kept SDL_GL_LoadLibrary (MC-311954 / Iris#3345 workaround)");
                        return cw.toByteArray();
                    } catch (Throwable t) {
                        log("Failed to patch GlBackend.loadLibrary(): " + t);
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

    private static class SurgicalPatch extends ClassVisitor {
        boolean methodFound = false;
        boolean patched = false;

        SurgicalPatch(ClassVisitor cv) {
            super(Opcodes.ASM9, cv);
        }

        @Override
        public MethodVisitor visitMethod(int access, final String name, final String desc,
                                         String signature, String[] exceptions) {
            // NOTE: named "delegate", not "mv" - an anonymous MethodVisitor
            // inherits a protected field called mv (null here), which would shadow
            // a local named mv and NPE on first use.
            MethodVisitor delegate = super.visitMethod(access, name, desc, signature, exceptions);
            if (!TARGET_METHOD.equals(name)) {
                return delegate;
            }
            methodFound = true;
            return new MethodVisitor(Opcodes.ASM9, delegate) {
                /**
                 * True only when the immediately preceding real instruction was
                 * {@code SDLVideo.SDL_GL_GetProcAddress(...)} (labels, line numbers
                 * and stack-map frames do not count as real instructions).
                 */
                private boolean prevWasSdlGetProc = false;

                private void realInsn() {
                    prevWasSdlGetProc = false;
                }

                @Override
                public void visitInsn(int opcode) {
                    if (opcode == Opcodes.LCMP && prevWasSdlGetProc) {
                        // stack: [lwjglGlGetError, sdlGlGetProcAddr]
                        //        -> [lwjglGlGetError, lwjglGlGetError]
                        delegate.visitInsn(Opcodes.POP2);
                        delegate.visitInsn(Opcodes.DUP2);
                        patched = true;
                        prevWasSdlGetProc = false;
                        delegate.visitInsn(opcode);
                        return;
                    }
                    if (opcode != Opcodes.LCMP) {
                        realInsn();
                    }
                    delegate.visitInsn(opcode);
                }

                @Override
                public void visitMethodInsn(int opcode, String owner, String methodName,
                                            String descriptor, boolean isInterface) {
                    prevWasSdlGetProc = opcode == Opcodes.INVOKESTATIC
                            && SDL_OWNER.equals(owner) && SDL_GET_PROC.equals(methodName);
                    delegate.visitMethodInsn(opcode, owner, methodName, descriptor, isInterface);
                }

                @Override
                public void visitVarInsn(int opcode, int var) {
                    realInsn();
                    delegate.visitVarInsn(opcode, var);
                }

                @Override
                public void visitFieldInsn(int opcode, String owner, String fieldName, String descriptor) {
                    realInsn();
                    delegate.visitFieldInsn(opcode, owner, fieldName, descriptor);
                }

                @Override
                public void visitJumpInsn(int opcode, Label label) {
                    realInsn();
                    delegate.visitJumpInsn(opcode, label);
                }

                @Override
                public void visitLdcInsn(Object value) {
                    realInsn();
                    delegate.visitLdcInsn(value);
                }

                @Override
                public void visitIntInsn(int opcode, int operand) {
                    realInsn();
                    delegate.visitIntInsn(opcode, operand);
                }

                @Override
                public void visitInvokeDynamicInsn(String name, String descriptor,
                                                   org.objectweb.asm.Handle bootstrapMethodHandle,
                                                   Object... bootstrapMethodArguments) {
                    realInsn();
                    delegate.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
                }

                @Override
                public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
                    realInsn();
                    delegate.visitTableSwitchInsn(min, max, dflt, labels);
                }

                @Override
                public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
                    realInsn();
                    delegate.visitLookupSwitchInsn(dflt, keys, labels);
                }

                @Override
                public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
                    realInsn();
                    delegate.visitMultiANewArrayInsn(descriptor, numDimensions);
                }
            };
        }
    }
}
