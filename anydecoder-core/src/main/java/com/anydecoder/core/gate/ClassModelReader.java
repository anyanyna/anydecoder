package com.anydecoder.core.gate;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.util.Printer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a {@link ClassModel} from a class file using ASM.
 *
 * <p>This replaces the javap text scraping the original gate used. That approach produced four
 * confirmed defects in the checker itself, two of them silent: the parser read the literal
 * token {@code Class} as the caught exception type (so the exception-table signal degenerated
 * into comparing row counts), and it required five whitespace-separated fields per row, which
 * silently discarded every {@code any} row -- that is, every {@code finally} handler, making a
 * source that had dropped its {@code finally} compare equal. Reading structured data removes
 * the whole failure mode.
 *
 * <p>Exception-table positions are expressed as <em>instruction indices</em>, not bytecode
 * offsets. Offsets differ between compilers for reasons that carry no meaning, and ASM only
 * guarantees label offsets after a class has been written. Indices are stable and are all the
 * normaliser needs.
 */
public final class ClassModelReader {

    private ClassModelReader() {
    }

    public static ClassModel read(Path classFile) throws IOException {
        try (InputStream in = Files.newInputStream(classFile)) {
            return read(in);
        }
    }

    public static ClassModel read(byte[] classBytes) {
        return build(new ClassReader(classBytes));
    }

    public static ClassModel read(InputStream in) throws IOException {
        return build(new ClassReader(in.readAllBytes()));
    }

    private static ClassModel build(ClassReader reader) {
        ClassNode node = new ClassNode();
        // Debug info is needed (the LocalVariableTable is one of the advisory signals) but
        // stack map frames are compiler-generated scaffolding with no source-level meaning.
        reader.accept(node, ClassReader.SKIP_FRAMES);

        List<FieldModel> fields = new ArrayList<>();
        for (FieldNode f : node.fields) {
            fields.add(new FieldModel(f.access, f.name, f.desc, f.signature));
        }

        List<MethodModel> methods = new ArrayList<>();
        for (MethodNode m : node.methods) {
            methods.add(toMethodModel(m));
        }

        return new ClassModel(
                node.version,
                node.access,
                node.name,
                node.superName,
                List.copyOf(node.interfaces),
                List.copyOf(fields),
                List.copyOf(methods));
    }

    private static MethodModel toMethodModel(MethodNode m) {
        List<Insn> instructions = new ArrayList<>();
        Map<LabelNode, Integer> labelPositions = new IdentityHashMap<>();

        for (AbstractInsnNode node = m.instructions.getFirst(); node != null; node = node.getNext()) {
            if (node instanceof LabelNode label) {
                // A label sits *before* whatever comes next, so its position is the count of
                // real instructions emitted so far.
                labelPositions.put(label, instructions.size());
                continue;
            }
            if (node instanceof LineNumberNode) {
                continue;
            }
            Insn insn = toInsn(node);
            if (insn != null) {
                instructions.add(insn);
            }
        }

        List<ExceptionEntry> table = new ArrayList<>();
        for (TryCatchBlockNode block : m.tryCatchBlocks) {
            int start = position(labelPositions, block.start, instructions.size());
            int end = position(labelPositions, block.end, instructions.size());
            int handler = position(labelPositions, block.handler, instructions.size());
            table.add(new ExceptionEntry(start, end, handler, block.type));
        }

        List<LocalVar> locals = new ArrayList<>();
        if (m.localVariables != null) {
            for (LocalVariableNode v : m.localVariables) {
                locals.add(new LocalVar(v.name, v.desc));
            }
        }

        List<String> declaredExceptions = m.exceptions == null
                ? List.of()
                : List.copyOf(m.exceptions);

        return new MethodModel(m.access, m.name, m.desc, m.signature, declaredExceptions,
                instructions, table, locals);
    }

    private static int position(Map<LabelNode, Integer> positions, LabelNode label, int fallback) {
        Integer p = positions.get(label);
        return p == null ? fallback : p;
    }

    private static Insn toInsn(AbstractInsnNode node) {
        int opcode = node.getOpcode();
        if (opcode < 0) {
            // Pseudo-instructions (frames) carry no executable meaning.
            return null;
        }
        String mnemonic = Printer.OPCODES[opcode];

        if (node instanceof InsnNode) {
            return new Insn(opcode, mnemonic, "", OpcodeSafety.canThrow(opcode));
        }
        if (node instanceof IntInsnNode i) {
            if (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) {
                // Which width a compiler picks for a small constant is its own business.
                return new Insn(opcode, "push", Integer.toString(i.operand), false);
            }
            if (opcode == Opcodes.NEWARRAY) {
                return new Insn(opcode, mnemonic, arrayTypeName(i.operand), true);
            }
            return new Insn(opcode, mnemonic, Integer.toString(i.operand), OpcodeSafety.canThrow(opcode));
        }
        if (node instanceof VarInsnNode) {
            // The slot number is the register allocator's choice, not the source's.
            return new Insn(opcode, mnemonic, "", OpcodeSafety.canThrow(opcode));
        }
        if (node instanceof TypeInsnNode t) {
            return new Insn(opcode, mnemonic, t.desc, OpcodeSafety.canThrow(opcode));
        }
        if (node instanceof FieldInsnNode f) {
            return new Insn(opcode, mnemonic,
                    f.owner + "." + canonicalMemberName(f.name) + ":" + f.desc, true);
        }
        if (node instanceof MethodInsnNode mi) {
            return new Insn(opcode, mnemonic,
                    mi.owner + "." + canonicalMemberName(mi.name) + mi.desc, true);
        }
        if (node instanceof InvokeDynamicInsnNode indy) {
            // The bootstrap method is synthesised by the compiler; name and descriptor are
            // what a faithful recompilation reproduces.
            return new Insn(opcode, mnemonic, indy.name + indy.desc, true);
        }
        if (node instanceof JumpInsnNode) {
            // Branch targets move between compilers; nothing about them is source-visible.
            return new Insn(opcode, mnemonic, "", OpcodeSafety.canThrow(opcode));
        }
        if (node instanceof LdcInsnNode ldc) {
            return new Insn(opcode, "ldc", constantOf(ldc.cst), false);
        }
        if (node instanceof IincInsnNode iinc) {
            return new Insn(opcode, mnemonic, (iinc.incr >= 0 ? "+" : "") + iinc.incr, false);
        }
        if (node instanceof TableSwitchInsnNode ts) {
            StringBuilder sb = new StringBuilder();
            for (int i = ts.min; i <= ts.max; i++) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(i);
            }
            return new Insn(opcode, mnemonic, sb.toString(), false);
        }
        if (node instanceof LookupSwitchInsnNode ls) {
            StringBuilder sb = new StringBuilder();
            for (int key : ls.keys) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(key);
            }
            return new Insn(opcode, mnemonic, sb.toString(), false);
        }
        if (node instanceof MultiANewArrayInsnNode ma) {
            return new Insn(opcode, mnemonic, ma.desc + " x" + ma.dims, true);
        }
        return new Insn(opcode, mnemonic, "", OpcodeSafety.canThrow(opcode));
    }

    /**
     * Collapses the numbering on the synthetic accessors a compiler generates for inner-class
     * access.
     *
     * <p>These names are pure compiler bookkeeping and the two compilers number them differently:
     * ecj writes {@code access$0}, {@code access$1} by order of generation, javac writes
     * {@code access$000}, {@code access$100} encoding the kind of member reached. A call to one
     * is a call to an accessor, in both cases.
     *
     * <p>What this gives up is the ability to tell two different accessors apart within one
     * method. That is affordable, because an accessor's own body is compared like any other
     * method, and it is the real member access inside it that carries meaning.
     */
    static String canonicalMemberName(String name) {
        if (!name.startsWith("access$") || name.length() == "access$".length()) {
            return name;
        }
        for (int i = "access$".length(); i < name.length(); i++) {
            if (!Character.isDigit(name.charAt(i))) {
                return name;
            }
        }
        return "access$";
    }

    private static String arrayTypeName(int operand) {
        return switch (operand) {
            case Opcodes.T_BOOLEAN -> "boolean";
            case Opcodes.T_CHAR -> "char";
            case Opcodes.T_FLOAT -> "float";
            case Opcodes.T_DOUBLE -> "double";
            case Opcodes.T_BYTE -> "byte";
            case Opcodes.T_SHORT -> "short";
            case Opcodes.T_INT -> "int";
            case Opcodes.T_LONG -> "long";
            default -> "type" + operand;
        };
    }

    /**
     * Renders a constant pool entry by <em>value</em>. The pool index is stripped on purpose:
     * it is assigned by the compiler and differs for identical source.
     */
    static String constantOf(Object cst) {
        if (cst == null) {
            return "null";
        }
        if (cst instanceof String s) {
            return "String:" + s;
        }
        if (cst instanceof Integer i) {
            return "int:" + i;
        }
        if (cst instanceof Long l) {
            return "long:" + l;
        }
        if (cst instanceof Float f) {
            return "float:" + Float.floatToRawIntBits(f);
        }
        if (cst instanceof Double d) {
            return "double:" + Double.doubleToRawLongBits(d);
        }
        if (cst instanceof Type t) {
            return "Type:" + t.getDescriptor();
        }
        if (cst instanceof Handle h) {
            return "Handle:" + h.getOwner() + "." + h.getName() + h.getDesc();
        }
        if (cst instanceof ConstantDynamic cd) {
            return "Condy:" + cd.getName() + cd.getDescriptor();
        }
        return String.valueOf(cst);
    }

    /** Maps a class's internal name to the path of its {@code .class} file. */
    public static String toPath(String internalName) {
        return internalName + ".class";
    }
}
