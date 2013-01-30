package org.jatronizer.handler;

import static org.jatronizer.handler.ASMSupport.asBytes;
import static org.jatronizer.handler.ASMSupport.asNode;
import static org.jatronizer.handler.ASMSupport.toPath;

import java.util.Map;

import org.objectweb.asm.tree.ClassNode;

// insulated by an interface so asm does not leak into the api
public class ASMTreeInstrumentor implements Instrumentor {

	private final ASMTreeInstrumentation instrumentation;
	private final DependencyTree dependencies;
	private final Map<String, byte[]> declaredClasses;

	/**
	 * create an Instrumentor relying on the ASM tree api
	 * @param instrumentation the instrumentation to apply
	 */
	public ASMTreeInstrumentor(ASMTreeInstrumentation instrumentation,
                               DependencyTree dependencies,
                               Map<String, byte[]> declaredClasses) {
		this.instrumentation = instrumentation;
		this.dependencies = dependencies;
		this.declaredClasses = declaredClasses;
		if (instrumentation == null || dependencies == null || declaredClasses == null) {
			throw new InstrumentationException(
					"No instrumentation, dependency tree or map from name to bytecode specified");
		}
	}

	public ASMTreeInstrumentor transform(String...binaryNames) {
		if (binaryNames == null || binaryNames.length == 0) {
			return this;
		}
		StringBuilder errors = null;
		ClassNode[] nodes = new ClassNode[binaryNames.length];
		for (int i = 0; i < binaryNames.length; i++) {
			try {
				ClassNode instrumentationTarget = asNode(toPath(binaryNames[i]));
				nodes[i] = instrumentationTarget;
			} catch (InstrumentationException e) {
				if (errors == null) {
					errors = new StringBuilder(" Error on <");
				} else {
					errors.append(", <");
				}
				errors.append(binaryNames[i]).append("> (")
					.append(e.getCause().getMessage()).append(")");
			}
		}
		if (errors != null) {
			throw new InstrumentationException(errors.toString());
		}
		return this.transform(nodes);
	}

	public ASMTreeInstrumentor transform(byte[]...bytecodes) {
		if (bytecodes == null || bytecodes.length == 0) {
			return this;
		}
		StringBuilder errors = null;
		ClassNode[] nodes = new ClassNode[bytecodes.length];
		for (int i = 0; i < bytecodes.length; i++) {
			try {
				nodes[i] = asNode(bytecodes[i]);
			} catch (InstrumentationException e) {
				if (errors == null) {
					errors = new StringBuilder("Error on elements: [");
				} else {
					errors.append(", [");
				}
				errors.append(i).append("] (")
					.append(e.getCause().getMessage()).append(")");
			}
		}
		return transform(new ClassNode[0]);
	}

	public ASMTreeInstrumentor transform(ClassNode...instrumentationTargets) {
		if (instrumentationTargets == null || instrumentationTargets.length == 0) {
			return this;
		}
		StringBuilder errors = null;
		for (ClassNode instrumentationTarget : instrumentationTargets) {
			try {
				ClassNode instrumented = instrumentation.instrument(instrumentationTarget);
				dependencies.add(instrumented);
				declaredClasses.put(instrumented.name.replace('/', '.'), asBytes(instrumented));
			} catch (Exception e) {
				if (errors == null) {
					errors = new StringBuilder(" Error instrumenting <");
				} else {
					errors.append(", <");
				}
				String className = instrumentationTarget == null
						? "null"
						: instrumentationTarget.name.replace('/', '.');
				errors.append(className).append(">: ")
                        .append(e.getClass().getSimpleName())
                        .append(" \"").append(e.getMessage()).append("\"");
			}
		}
		if (errors != null) {
			throw new InstrumentationException(errors.toString());
		}
		return this;
	}
}