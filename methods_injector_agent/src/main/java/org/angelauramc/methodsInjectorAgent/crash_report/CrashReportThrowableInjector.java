package org.angelauramc.methodsInjectorAgent.crash_report;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

/**
 * Prints the primary throwable of every Minecraft crash report into System.out,
 * which the launcher captures into the profile launch log (jrelog stream).
 *
 * <p>MC builds its crash reports via {@code CrashReport.forThrowable(t, title)},
 * but the primary exception itself is only written to the crash-reports/ file.
 * The in-app log ends up showing at best a secondary warning (e.g. the oshi
 * AccessDenied stack printed while the report was being assembled), which made
 * crashes impossible to diagnose from the log screen alone.</p>
 *
 * <p>Patch: at the very start of the static {@code forThrowable(Throwable, String)}
 * emit, before the original body:</p>
 * <pre>
 * System.out.println("Morphyz Launcher: === GAME CRASH ===");
 * System.out.println(title);
 * t.printStackTrace(new PrintStream(System.out));
 * </pre>
 * <p>The original body may unwrap {@code CompletionException} or reuse a
 * {@code ReportedException}'s report - the printed chain includes all causes
 * anyway. Straight-line insertion at method entry: no new branch targets, no
 * stack-map frames affected, {@code COMPUTE_MAXS} suffices.</p>
 */
public class CrashReportThrowableInjector {

    private static final String TARGET_CLASS = "net/minecraft/CrashReport";
    private static final String TARGET_METHOD = "forThrowable";
    private static final String TARGET_DESC =
            "(Ljava/lang/Throwable;Ljava/lang/String;)Lnet/minecraft/CrashReport;";

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
                        CrashPrint visitor = new CrashPrint(cw);
                        cr.accept(visitor, 0);
                        if (!visitor.patched) {
                            log("CrashReport.forThrowable not found - crash printer inactive");
                            return null;
                        }
                        log("Crash printer active: primary game exceptions now go to the launch log");
                        return cw.toByteArray();
                    } catch (Throwable t) {
                        log("Failed to patch CrashReport: " + t);
                        return null;
                    }
                }
            });
        } catch (Throwable t) {
            log("Could not register CrashReport transformer: " + t);
        }
    }

    private static void log(String message) {
        try { // Minecraft makes it ugly if we use println
            System.out.write(("Morphyz Launcher: " + message + "\n").getBytes());
            System.out.flush();
        } catch (Exception ignored) {}
    }

    private static class CrashPrint extends ClassVisitor {
        boolean patched = false;

        CrashPrint(ClassVisitor cv) {
            super(Opcodes.ASM9, cv);
        }

        @Override
        public MethodVisitor visitMethod(int access, final String name, final String desc,
                                         String signature, final String[] exceptions) {
            // NOTE: named "delegate", not "mv" - an anonymous MethodVisitor
            // inherits a protected field called mv (null here), which would shadow
            // a local named mv and NPE on first use.
            final MethodVisitor delegate = super.visitMethod(access, name, desc, signature, exceptions);
            if (!TARGET_METHOD.equals(name) || !TARGET_DESC.equals(desc)) {
                return delegate;
            }
            return new MethodVisitor(Opcodes.ASM9, delegate) {
                @Override
                public void visitCode() {
                    super.visitCode();
                    // System.out.println("Morphyz Launcher: === GAME CRASH ===");
                    delegate.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out",
                            "Ljava/io/PrintStream;");
                    delegate.visitLdcInsn("Morphyz Launcher: === GAME CRASH ===");
                    delegate.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println",
                            "(Ljava/lang/String;)V", false);
                    // System.out.println(title);  (static method: slot 0 = Throwable, 1 = String)
                    delegate.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out",
                            "Ljava/io/PrintStream;");
                    delegate.visitVarInsn(Opcodes.ALOAD, 1);
                    delegate.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println",
                            "(Ljava/lang/String;)V", false);
                    // t.printStackTrace(new PrintStream(System.out)); -> printStackTrace(PrintStream)
                    delegate.visitVarInsn(Opcodes.ALOAD, 0);
                    delegate.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out",
                            "Ljava/io/PrintStream;");
                    delegate.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Throwable",
                            "printStackTrace", "(Ljava/io/PrintStream;)V", false);
                    patched = true;
                }
            };
        }
    }
}
