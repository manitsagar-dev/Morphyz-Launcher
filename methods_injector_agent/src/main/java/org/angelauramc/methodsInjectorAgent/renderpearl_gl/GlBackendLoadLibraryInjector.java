package org.angelauramc.methodsInjectorAgent.renderpearl_gl;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

/**
 * MC 26.x GlBackend.loadLibrary() sanity-checks that the address of glGetError()
 * obtained through SDL_GL_GetProcAddress matches the one LWJGL bound, and throws
 * BackendCreationException("glGetError mismatch") otherwise. MC then falls back to
 * Vulkan, which aborts on devices whose system Vulkan driver is below 1.2
 * (exit code / fatal signal 6).
 *
 * On Android the comparison can never succeed: SDL resolves GL entry points via
 * eglGetProcAddress() (vendor driver pointers) while LWJGL binds the launcher's
 * renderer library (gl4es/MobileGlues/...). The check is redundant because LWJGL
 * already loads and binds every GL function the game uses - the same conclusion
 * reached for the desktop variant of this bug (Mojang MC-311954, Iris #3345),
 * where patching loadLibrary() to do nothing made the game run normally.
 *
 * This injector replaces the whole body of GlBackend.loadLibrary() with an
 * immediate default return, so neither the SDL_GL_LoadLibrary() call nor the
 * pointer comparison can throw anymore.
 */
public class GlBackendLoadLibraryInjector {

    private static final String TARGET_CLASS = "com/mojang/renderpearl/backend/opengl/GlBackend";
    private static final String TARGET_METHOD = "loadLibrary";

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
                        NeutralizeLoadLibrary visitor = new NeutralizeLoadLibrary(cw);
                        cr.accept(visitor, 0);
                        if (!visitor.patched) {
                            log("GlBackend.loadLibrary() not found - MC changed, GlBackend injector inactive");
                            return null;
                        }
                        log("Neutralized GlBackend.loadLibrary() glGetError sanity check " +
                                "(MC-311954 / Iris#3345 workaround)");
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

    private static class NeutralizeLoadLibrary extends ClassVisitor {
        boolean patched = false;

        NeutralizeLoadLibrary(ClassVisitor cv) {
            super(Opcodes.ASM9, cv);
        }

        @Override
        public MethodVisitor visitMethod(int access, final String name, final String desc,
                                         String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
            if (!TARGET_METHOD.equals(name)) {
                return mv;
            }
            patched = true;
            // Discard the original method body entirely (a MethodVisitor with a null
            // delegate swallows every visit) and emit a default return as soon as the
            // reader enters the method. Straight-line code needs no stack map frames.
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitCode() {
                    mv.visitCode();
                    char returnType = desc.charAt(desc.indexOf(')') + 1);
                    switch (returnType) {
                        case 'V':
                            mv.visitInsn(Opcodes.RETURN);
                            break;
                        case 'J':
                            mv.visitInsn(Opcodes.LCONST_0);
                            mv.visitInsn(Opcodes.LRETURN);
                            break;
                        case 'D':
                            mv.visitInsn(Opcodes.DCONST_0);
                            mv.visitInsn(Opcodes.DRETURN);
                            break;
                        case 'F':
                            mv.visitInsn(Opcodes.FCONST_0);
                            mv.visitInsn(Opcodes.FRETURN);
                            break;
                        case 'I':
                        case 'Z':
                        case 'B':
                        case 'C':
                        case 'S':
                            mv.visitInsn(Opcodes.ICONST_0);
                            mv.visitInsn(Opcodes.IRETURN);
                            break;
                        default: // object or array return type
                            mv.visitInsn(Opcodes.ACONST_NULL);
                            mv.visitInsn(Opcodes.ARETURN);
                    }
                    mv.visitMaxs(2, 0); // recomputed by ClassWriter.COMPUTE_MAXS
                    mv.visitEnd();
                }
            };
        }
    }
}
