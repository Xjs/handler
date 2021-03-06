package org.jatronizer.handler;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

public final class ASMSupport {

	// reference the class instead of using a String so the library can be repackaged or refactored
	private static final String INSTRUMENTS_ANNOTATION_DESC = Type.getDescriptor(Instruments.class);
	private static final String SPAWNER_ANNOTATION_DESC = Type.getDescriptor(SpawnsWith.class);

	public static boolean match(MethodNode node, String argDesc, String... names) {
		if (argDesc.equals(node.desc)) {
			for (String name : names) {
				if (node.name.equals(name)) return true;
			}
			return names.length == 0;
		}
		return false;
	}

	public static String getSimpleName(Type type) {
		String className = type.getClassName();
		int lastIndex = className.lastIndexOf('.');
		if (className.lastIndexOf('$') > lastIndex) {
			lastIndex = className.lastIndexOf('$');
		}
		return className.substring(lastIndex + 1);
	}

	public static <I> List<I> addIfNew(List<I> elements, I... newElements) {
		for (I element : newElements) {
			if (!elements.contains(element)) {
				elements.add(element);
			}
		}
		return elements;
	}

	public static String[] toStringsOrNull(List<?> list) {
		return list == null ? null : (String[]) list.toArray(new String[list.size()]);
	}

	public static String unusedName(String desired, String... names) {
		return unusedName(desired, Arrays.asList(names));
	}

	public static String unusedName(String desired, List<String> names) {
		StringBuilder result = new StringBuilder(desired);
		while(names.contains(result.toString())) {
			result.append('_');
		}
		return result.toString();
	}

	public static String objDescriptor(String internalName) {
		return "L" + internalName + ";";
	}

	public static boolean isSomeOf(int mask, int access) {
		return (access & mask) != 0;
	}

	public static boolean isNoneOf(int mask, int access) {
		return (access & mask) == 0;
	}

	public static boolean isAllOf(int mask, int access) {
		return (access & mask) == mask;
	}

	@SuppressWarnings("unchecked")
	public static FieldNode[] getFields(
			ClassNode node, Type type, String name,
			Integer accessRequired, Integer accessForbidden) {
		ArrayList<FieldNode> result = new ArrayList<FieldNode>();
		for (FieldNode field : (List<FieldNode>) node.fields) {
			if ((accessRequired == null || isAllOf(accessRequired, field.access))
					&& (accessForbidden == null || isNoneOf(accessForbidden, field.access))
					&& (name == null || name.equals(field.name))
					&& (type == null || type.getDescriptor().equals(field.desc))) {
				result.add(field);
			}
		}
		return result.toArray(new FieldNode[result.size()]);
	}

	@SuppressWarnings("unchecked")
	public static MethodNode[] getMethods(
			ClassNode node, String name, String desc,
			Integer accessRequired, Integer accessForbidden) {
		ArrayList<MethodNode> result = new ArrayList<MethodNode>();
		for (MethodNode method : (List<MethodNode>) node.methods) {
			if ((accessRequired == null || isAllOf(accessRequired, method.access))
					&& (accessForbidden == null || isNoneOf(accessForbidden, method.access))
					&& (name == null || name.equals(method.name))
					&& (desc == null || desc.equals(method.desc))) {
				result.add(method);
			}
		}
		return result.toArray(new MethodNode[result.size()]);
	}

	public static String[] getNames(FieldNode... fields) {
		String[] result = new String[fields.length];
		for (int i = 0; i < fields.length; i++) {
			result[i] = fields[i].name;
		}
		return result;
	}

	public static String toPath(String binaryName) {
		return binaryName.replace('.', '/') + ".class";
	}

	public static byte[] asBytes(String path) {
		return asBytes(ClassLoader.getSystemResourceAsStream(path));
	}

	public static byte[] asBytes(InputStream in) {
		try {
			return InputStreamDumper.fetchBytes(in);
		} catch (Exception e) {
			return null;
		}
	}

	public static byte[] asBytes(ClassNode source) {
		// Why not COMPUTE_FRAMES:
		// if you don't use a custom ClassWriter, you're in trouble if you use COMPUTE_FRAMES,
		// the default implementation of ClassWriter.getCommonSuperClass loads the class and leads
		// to a LinkingError - you can't instrument it because it's already loaded.
		ClassWriter processor = new ClassWriter(0); // ClassWriter.COMPUTE_MAXS);
		source.accept(processor);
		return processor.toByteArray();
	}

	public static ClassNode asCodelessNode(String path) {
		try {
			return asNode(new ClassReader(ClassLoader.getSystemResourceAsStream(path)), ClassReader.SKIP_CODE);
		} catch (IOException e) {
			throw new InstrumentationException(e);
		}
	}

	public static ClassNode asNode(String path) {
		try {
			return asNode(new ClassReader(ClassLoader.getSystemResourceAsStream(path)));
		} catch (IOException e) {
			throw new InstrumentationException(e);
		}
	}

	public static ClassNode asNode(byte[] bytes) {
		return asNode(new ClassReader(bytes));

	}

	public static ClassNode asNode(InputStream in) {
		try {
			return asNode(new ClassReader(in));
		} catch (IOException e) {
			throw new InstrumentationException(e);
		}
	}

	private static ClassNode asNode(ClassReader reader) {
		return asNode(reader, 0);
	}

	private static ClassNode asNode(ClassReader reader, int readerFlag) {
		ClassNode result = new ClassNode();
		reader.accept(result, readerFlag);
		return result;
	}

	@SuppressWarnings("unchecked")
	private static Object getAnnotationValue(ClassNode type, String annotationDesc) {
		if (type.invisibleAnnotations != null) {
			for (AnnotationNode annotation : (List<AnnotationNode>) type.invisibleAnnotations) {
				if (annotationDesc.equals(annotation.desc)) {
					return annotation.values.get(1);
				}
			}
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	public static String[] getInstrumentationTargets(ClassNode handler) {
		List<Type> instrumentationTargets = (List<Type>) getAnnotationValue(handler, INSTRUMENTS_ANNOTATION_DESC);
		if (instrumentationTargets == null) {
			return new String[0];
		}
		ArrayList<String> targetClassNames = new ArrayList<String>();
		for (Type target : instrumentationTargets) {
			targetClassNames.add(target.getClassName());
		}
		return targetClassNames.toArray(new String[targetClassNames.size()]);
	}

	public static String getSpawner(ClassNode handler) {
		return (String) getAnnotationValue(handler, SPAWNER_ANNOTATION_DESC);
	}
}
