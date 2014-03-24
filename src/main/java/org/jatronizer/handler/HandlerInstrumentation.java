package org.jatronizer.handler;

import static org.jatronizer.handler.ASMSupport.*;
import static org.objectweb.asm.Opcodes.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.objectweb.asm.Attribute;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/**
 * instruments another class so it applies the Handler Pattern.<br>
 * The Handler Pattern as supported by this class gives full control over a method's behavior in a class.
 * An interface serves as a configuration file for the instrumentation.<br>
 * Usage scenario:<br />
 * You want to check how often a method <code>charToInt</code> is called on an instance of class <code>Caster</code>.
 * <pre><code>
 *	public final class Caster {
 *		public Caster() {
 *		}
 *
 *		// we want to instrument this method
 *		public int charToInt(char c) {
 *			return (int) c;
 *		}
 *	}
 * </code></pre>
 * To measure the number of calls to <code>charToInt</code> with the Handler Pattern, you create an interface
 * <code>CharToIntHandler</code>
 * <pre><code>
 *	public interface CharToIntHandler {
 *		// the new version of our method
 *		int charToInt(CharToIntHandler handler, char c);
 *
 *		// we need this to set our Handler, HandlerInstrumentation will create this
 *		void setCharToIntHandler(CharToIntHandler handler);
 *	}
 * </code></pre>
 * <code>HandlerInjector</code> injects <code>CharToIntHandler</code> into <code>Caster</code>
 * and reroutes the method calls. <code>Caster</code> now becomes
 * <pre><code>
 *	public final class Caster implements CharToIntHandler {
 *	    // where the Handler is stored
 *		private CharToIntHandler charToIntHandler;
 *
 *		public Caster() {
 *			// set to this at initialization so we can safely call methods
 *			charToIntHandler = this;
 *		}
 *
 *		// the original version with a changed signature
 *		public int charToInt(CharToIntHandler handler, char c) {
 *			return (int) c;
 *		}
 *
 *		// all calls end up here and are rerouted to the Handler
 *		public int charToInt(char c) {
 *			return charToIntHandler.charToInt(this, c);
 *		}
 *
 *		// we need this to set the Handler without reflection (casting only)
 *		public CharToIntHandler setCharToIntHandler(CharToIntHandler charToIntHandler) {
 *			this.charToIntHandler = charToIntHandler == null ? this : charToIntHandler;
 *		}
 *	}
 * </code></pre>
 * Now every instance of <code>Caster</code> is castable to <code>CharToIntHandler</code> at runtime.
 * Given this <code>CharToIntHandler</code> (not thread-safe, but that's up to you)
 * <pre><code>
 *	public class CallCounter implements CharToIntHandler {
 *		private int calls = 0;
 *
 *		public int getCallCount() {
 *			return calls;
 *		}
 *
 *		public int charToInt(CharToIntHandler handlee, char c) {
 *			calls++;
 *			return handlee.charToInt(null, c);
 *		}
 *
 *		public void setCharToIntHandler(CharToIntHandler handler) {
 *			// this will never get called on a handler, it's for the handlee
 *			throw new UnsupportedOperationException("you can't handle this!");
 *		}
 *	}
 * </code></pre>
 * You can get the number of charToInt calls your Caster instance receives:
 * <pre><code>
 *	Caster caster = new Caster();
 *	CallCounter counter = new CallCounter();
 *	caster.charToInt(0);
 *	assertTrue(counter.getCallCount() == 0);
 *	((CharToIntHandler) caster).setCharToIntHandler(counter);
 *	caster.charToInt(0);
 *	assertTrue(counter.getCallCount() == 1);
 *	caster.charToInt(0);
 *	assertTrue(counter.getCallCount() == 2);
 * </code></pre>
 *
 * When this is applied, you are able to intercept, record, change, redirect or ignore method calls.<br>
 * What could this be used for? Some obvious choices - but only scratching the surface:
 * easy creation of mocks for any objects, lightweight / focused profilers,
 * logging on method calls, opening up rigid and restricting libraries...
 *
 * @author Arne Hormann
 */
public class HandlerInstrumentation implements ASMTreeInstrumentation {

	public static enum NullPointerGuard {
		assignBeforeSuper,
		assignAfterSuper,
		checkBeforeCall
	}

	private final String simpleName;
	private final Type handlerType;
	private final NullPointerGuard guard;
	private final String spawnerClass;
	private final String spawnerMethod;
	private final String spawnerDesc;
	private final String nativePrefix;
	private final String[] handlerMethods;
	private final String[] getHandlers;
	private final String[] setHandlers;

	/**
	 * creates a new HandlerInjector.
	 * @param config the handler's structure using the interface as a declarative configuration
	 * @param guard specifies how to guard against NullPointerExceptions.
	 *		This has to be handled as the Handler field is not set after initialization.<br>
	 *		Passing <code>null</code> uses the default value.
	 *		<code>assignBeforeSuper</code>:
	 *			set Handler field to <code>this</code> in constructor right before calling <code>super(...)</code>,
	 *			guard against <code>null</code> in setHandler.<br>
	 *			This approach is fast and safe, but the created bytecode is invalid in Java 1.7+.
	 *			As it relies on a hack, some tools may have (or cause) problems with this approach.
	 *		<code>assignAfterSuper</code>:
	 *			set Handler field to <code>this</code> in constructor right after calling <code>super(...)</code>,
	 *			guard against <code>null</code> in setHandler.<br>
	 *			This approach will throw a NullPointerException when a handled method
	 *			overrides a method in <code>super</code> which is called in the <code>super(...)</code> constructor.
	 *		<code>checkBeforeCall</code> or <code>null</code> (default):
	 *			check Handler field before each call of each handled method,
	 *			do not use a Handler when none is set (call the method on <code>this</code> or a spawned Handler).
	 *			This is probably slightly slower, but it is robust.
	 * @param defaultHandlerSpawner a static method that retrieves the Handler used when none is explicitly set.<br>
	 *		<code>null</code> always sets the default Handler to <code>this</code>.<br>
	 *		If specified, the Spawner must be a reachable (<code>public</code> method and class)
	 *		<code>static</code> method.<br>
	 *		It's signature must be <code>HANDLER method(HANDLER)</code>.
	 *		Return the parameter for "no handler" default behavior.<br>
	 *		It must not return null and must not declare or throw Throwables.<br>
	 *		The Handlee passes itself on initialisation.<br>
	 *		For a Handler <code>X</code>, this example sets no handler - like passing <code>null</code> instead:
	 *		<pre><code>
	 *		package example;
	 *
	 *		class Spawner {
	 *			public static X spawn(X handlee) {
	 *				return handlee;
	 *			}
	 *		}
	 *		</code></pre>
	 *		The parameter for this example is <code>example.Utils$Spawner.spawn</code>,
	 *		just like a binary name with a method suffix.<br>
	 *		For all guards but <code>checkBeforeCall</code>, the spawner is called once after the
	 *		<code>super(...)</code> constructor is called.
	 *		For <code>checkBeforeCall</code>, it is called on usage of the handled method.
	 * @param nativePrefix prefix for native methods.
	 *		If specified, this defines a prefix and wraps calls to native methods to allow instrumentation.
	 *		To avoid conflicts, use something not allowed by idiomatic Java but by the JVM - like "$Handler$".
	 *		Pass <code>null</code> if you don't instrument native methods.<br>
	 *		This is only usable by a ClassFileTransformer (Java 1.6+),
	 *		it uses java.lang.instrument.Instrumentation.setNativeMethodPrefix(...).
	 *		If the <code>config</code> bytecode version is older than Java 1.6, it fails silently.
	 */
	@SuppressWarnings("unchecked")
	public HandlerInstrumentation(
			ClassNode config,
			NullPointerGuard guard,
			String defaultHandlerSpawner,
			String nativePrefix) {
		this.guard = guard == null ? NullPointerGuard.checkBeforeCall : guard;
		this.handlerType = Type.getObjectType(config.name);
		// this check is simple. We could check for presence of the method we need, but that requires class loading
		this.nativePrefix = config.version >= V1_6 ? nativePrefix : null;
		this.simpleName = getSimpleName(handlerType);
		if (defaultHandlerSpawner != null) {
			defaultHandlerSpawner = defaultHandlerSpawner.replace('.', '/');
			int endOfClass = defaultHandlerSpawner.lastIndexOf('/');
			this.spawnerClass = defaultHandlerSpawner.substring(0, endOfClass);
			this.spawnerMethod = defaultHandlerSpawner.substring(endOfClass + 1);
			this.spawnerDesc = "(" + handlerType.getDescriptor() + ")" + handlerType.getDescriptor();
		} else {
			this.spawnerClass = null;
			this.spawnerMethod = null;
			this.spawnerDesc = null;
		}
		ArrayList<String> handlerMethods = new ArrayList<String>();
		ArrayList<String> getHandlers = new ArrayList<String>();
		ArrayList<String> setHandlers = new ArrayList<String>();
		String handlerDesc = handlerType.getDescriptor();
		for (MethodNode method : (Collection<MethodNode>) config.methods) {
			if (match(
					method, "()" + handlerDesc,
					"get" + simpleName, simpleName, downcaseFirst(simpleName))) {
				// a method to retrieve handler X: "X getX()", "X X()" or "X x()"
				getHandlers.add(method.name);
			} else if (match(
					method, "(" + handlerDesc + ")V",
					"set" + simpleName, simpleName, downcaseFirst(simpleName))) {
				// a method to set handler X: "void setX(X)", "void X(X)" or "void x(X)"
				setHandlers.add(method.name);
			} else if (method.desc.substring(1).startsWith(handlerType.getDescriptor())) {
				handlerMethods.add(method.name + "(" + method.desc.substring(method.desc.indexOf(';') + 1));
			}
		}
		this.handlerMethods = toStringsOrNull(handlerMethods);
		this.getHandlers = toStringsOrNull(getHandlers);
		this.setHandlers = toStringsOrNull(setHandlers);
	}

	private static String downcaseFirst(String name) {
		return Character.toLowerCase(name.charAt(0)) + name.substring(1);
	}

	private boolean usesSpawner() {
		return this.spawnerClass != null && spawnerMethod != null && spawnerDesc != null;
	}

	@SuppressWarnings("unchecked")
	public ClassNode instrument(ClassNode handlee) {
		// add Handler interface to Handlee
		addIfNew(handlee.interfaces, handlerType.getInternalName());
		// add Handler-field + get/set methods. field is public so it can be easily set by reflection
		final int fieldModifiers = ACC_PUBLIC | ACC_TRANSIENT | ACC_VOLATILE | ACC_SYNTHETIC;
		final String fieldName = addProperty(handlee, handlerType, fieldModifiers, simpleName, getHandlers, setHandlers);
		// process handled methods
		final Type handleeType = Type.getObjectType(handlee.name);
		String[] methods = this.handlerMethods.clone();
		for (MethodNode method : (MethodNode[]) handlee.methods.toArray(new MethodNode[handlee.methods.size()])) {
			// inject initial value of Handler-field in constructors if relaying is not guarded per call
			if ("<init>".equals(method.name)) {
				assignHandler(method, handleeType.getInternalName(), fieldName);
			}
			// per method: apply handler pattern if applicable
			String methodSignature = method.name + method.desc;
			for (int i = 0; i < methods.length; i++) {
				if (methodSignature.equals(methods[i])) {
					methods[i] = null; // mark as processed
					applyHandlerPattern(handlee, method, handleeType, fieldName);
				}
			}
		}
		// look for methods in Handler which are not in Handlee and throw an Exception if there are some
		StringBuilder builder = null;
		for (String unhandled : methods) {
			if (unhandled != null) {
				if (builder == null) {
					builder = new StringBuilder("unhandled methods in ").append(handlee.name).append(": ");
				}
				builder.append(unhandled).append(", ");
			}
		}
		if (builder != null) {
			throw new InstrumentationException(builder.substring(0, builder.length() - 2));
		}
		// return the processed Handlee
		return handlee;
	}

	/**
	 * create the instructions needed to store a handler in the handler field or in a temporary variable.
	 * If handlee and handlerField are non-null, the result is stored in the field on handlee.
	 * Otherwise, it's stored in a temporary variable at position varSlot.
	 */
	private InsnList putHandlerOnStack(
			String handleeInternalName,
			String handlerField,
			int varSlot,
			boolean fromSpawner) {
		final boolean storeInField = handleeInternalName != null && handlerField != null;
		if (!storeInField && varSlot <= 0) {
			throw new RuntimeException("varSlot must be > 0 or the handler field information must be specified");
		}
		InsnList instructions = new InsnList();
		if (fromSpawner && usesSpawner()) {
			// result is spawned handler
			instructions.add(new VarInsnNode(ALOAD, 0));
			instructions.add(new MethodInsnNode(INVOKESTATIC, spawnerClass, spawnerMethod, spawnerDesc, false));
		} else {
			// result is this
			instructions.add(new VarInsnNode(ALOAD, 0));
		}
		if (storeInField) {
			// put "this" on top of stack for PUT_FIELD-instruction (first instruction)
			instructions.insertBefore(instructions.getFirst(), new VarInsnNode(ALOAD, 0));
			// store result in this.<handlerField>
			instructions.add(
					new FieldInsnNode(PUTFIELD, handleeInternalName, handlerField, handlerType.getDescriptor())
			);
		} else {
			instructions.add(new VarInsnNode(ASTORE, varSlot));
		}
		return instructions;
	}

	private InsnList storeHandlerInField(String handleeInternalName, String handlerField, boolean fromSpawner) {
		return putHandlerOnStack(handleeInternalName, handlerField, -1, fromSpawner);
	}

	private InsnList storeHandlerInVarSlot(int varSlot, boolean fromSpawner) {
		return putHandlerOnStack(null, null, varSlot, fromSpawner);
	}

	private void storeHandlerInVarSlot(MethodNode mn, int varSlot, boolean fromSpawner) {
		storeHandlerInVarSlot(varSlot, fromSpawner).accept(mn);
	}

	/**
	 * retrieves the MethodInsnNode used to call <code>super(...)</code> or <code>this(...)</code>.
	 * @return <code>null</code> if InsnList does not contain a call to the constructor,
	 * the MethodInsnNode for the call otherwise
	 */
	private MethodInsnNode findConstructorCall(InsnList instructions) {
		for (AbstractInsnNode node = instructions.getFirst(); node != null; node = node.getNext()) {
			if (node.getOpcode() == INVOKESPECIAL
					&& node instanceof MethodInsnNode
					&& "<init>".equals(((MethodInsnNode) node).name)) {
				return (MethodInsnNode) node;
			}
		}
		return null;
	}

	/**
	 * instrument constructor and add <code>this.HANDLER = this;</code> or the result of an invocation of the spawner.
	 * If super is not <code>java.lang.Object</code> and the guard type is <code>assignBeforeSuper</code>,
	 * the assignment of <code>this</code> is injected before calling <code>super(...)</code> (see below).
	 * The result of the spawner is injected after calling <code>super(...)</code> if a spawner was specified.
	 */
	private void assignHandler(MethodNode method, String handleeInternalName, String handlerField) {
		if (this.guard == NullPointerGuard.checkBeforeCall) {
			return;
		}
		InsnList instructions = method.instructions;
		final boolean usesLabels = instructions.getFirst() instanceof LabelNode;
		final MethodInsnNode constructorCall = findConstructorCall(instructions);
		// skip modification on delegation to this(...)
		if (handleeInternalName.equals(constructorCall.owner)) {
			return;
		}
		// not delegating to this(...), inject initialization of Handler field
		AbstractInsnNode node = instructions.getFirst();
		InsnList storeHandler;
		switch (this.guard) {
			case assignBeforeSuper:
				storeHandler = storeHandlerInField(handleeInternalName, handlerField, false);
				if (usesLabels) {
					storeHandler.add(new LabelNode());
					instructions.insert(node, storeHandler);
				} else {
					instructions.insertBefore(node, storeHandler);
				}
				if (!usesSpawner()) {
					break;
				} // else fallthrough
			case assignAfterSuper:
				storeHandler = storeHandlerInField(handleeInternalName, handlerField, true);
				if (usesLabels) {
					storeHandler.insertBefore(storeHandler.getFirst(), new LabelNode());
				}
				instructions.insert(constructorCall, storeHandler);
				break;
			default:
				throw new InstrumentationException(this.guard + " is not implemented yet");
		}
		method.maxStack += usesSpawner() ? 2 : 1;
	}

	/**
	 * adds Handler parameter to the handled method and adds the delegating method to the class
	 */
	private void applyHandlerPattern(
			ClassNode handlee,
			MethodNode handledMethod,
			Type handleeType,
			String fieldName) {
		final int invalidAccessors = ACC_ABSTRACT | ACC_STATIC | (nativePrefix == null ? ACC_NATIVE : 0);
		if (isSomeOf(invalidAccessors, handledMethod.access)) {
			throw new InstrumentationException(handlee.name.replace('/', '.')
					+ '.' + handledMethod.name + handledMethod.desc
					+ " must not be abstract, static or native");
		}
		MethodNode handlerMethod = createHandlerMethod(handledMethod, handleeType.getInternalName(), fieldName);
		swapAttributesAndAnnotations(handlerMethod, handledMethod);
		handlerMethod.accept(handlee);
		if (nativePrefix == null) {
			changeHandledMethod(handledMethod, fieldName);
		} else {
			// create a new method calling the native one
			MethodNode wrapper = wrapNativeMethod(handlee.name, handledMethod);
			// this happens during the swap above, but if we change the implementation, it has to happen here as well
			swapAttributesAndAnnotations(wrapper, handledMethod);
			wrapper.accept(handlee);
			// rename previous native method
			String oldName = handledMethod.name;
			handledMethod.name = nativePrefix + oldName;
		}
	}

	/**
	 * creates the handler method:
	 * <pre><code>
	 *	public MODIFIERS RETURN_TYPE METHOD_NAME(...) EXCEPTIONS {
	 *		HANDLER_TYPE tmp = this.HANDLER_FIELD;
	 *		return tmp.METHOD_NAME(this, ...);
	 *	}
	 * </code></pre>
	 *
	 * or a guarded handler method (checkBeforeCall):
	 * <pre><code>
	 *	public MODIFIERS RETURN_TYPE METHOD_NAME(...) EXCEPTIONS {
	 *		HANDLER_TYPE tmp = this.HANDLER_FIELD;
	 *		if (tmp == null) {
	 *			tmp = this; // or defaultHandlerSpawner(this, true)
	 *		}
	 *		return tmp.METHOD_NAME(this, ...);
	 *	}
	 * </code></pre>
	 * if a spawner for a default handler is given, it is used instead of <code>this</code>
	 */
	private MethodNode createHandlerMethod(
			MethodNode handledMethod,
			String handleeInternalName,
			String field) {
		final boolean guarded = guard == NullPointerGuard.checkBeforeCall;
		final Type[] argTypes = Type.getArgumentTypes(handledMethod.desc);
		MethodNode mn = new MethodNode(
				(handledMethod.access | ACC_PUBLIC) & ~(ACC_PROTECTED | ACC_PRIVATE),
				handledMethod.name,
				handledMethod.desc,
				handledMethod.signature,
				toStringsOrNull(handledMethod.exceptions)
		);
		mn.visitCode();
		Label start = new Label();
		mn.visitLabel(start);
		// build guard
		mn.visitVarInsn(ALOAD, 0);
		mn.visitFieldInsn(GETFIELD, handleeInternalName, field, handlerType.getDescriptor());
		Label afterJump = null;
		if (guarded) {
			// store handler in temporary var
			int varSlot = 1;
			for (Type t : argTypes) {
				varSlot += t.getSize();
			}
			mn.visitVarInsn(ASTORE, varSlot);
			// build conditional
			mn.visitVarInsn(ALOAD, varSlot);
			mn.visitJumpInsn(IFNONNULL, afterJump = new Label());
			storeHandlerInVarSlot(mn, varSlot, true);
			mn.visitLabel(afterJump);
			Object[] locals = new Object[argTypes.length + 2];
			locals[0] = handleeInternalName;
			for (int i = 0; i < argTypes.length; i++) {
				locals[i + 1] = getFrameType(argTypes[i]);
			}
			locals[locals.length - 1] = handlerType.getInternalName();
			mn.visitFrame(F_NEW, locals.length, locals, 0, new Object[0]);
			mn.visitVarInsn(ALOAD, varSlot);
		}
		// first argument is "this"
		mn.visitVarInsn(ALOAD, 0);
		for (int i = 0, slot = 1; i < argTypes.length; i++) {
			// then args...
			Type type = argTypes[i];
			mn.visitVarInsn(type.getOpcode(ILOAD), slot);
			slot += type.getSize();
		}
		// call handler method on handler field with (this, other arguments)
		String targetMethodArgDescriptor = "(" + handlerType.getDescriptor() + handledMethod.desc.substring(1);
		mn.visitMethodInsn(
				INVOKEINTERFACE,
				handlerType.getInternalName(),
				handledMethod.name,
				targetMethodArgDescriptor,
				true
		);
		// return / return result
		mn.visitInsn(Type.getReturnType(handledMethod.desc).getOpcode(IRETURN));
		Label end = new Label();
		mn.visitLabel(end);
		// wrap up: used vars...
		int slot = 0;
		mn.visitLocalVariable("this", objDescriptor(handleeInternalName), null, start, end, slot);
		slot++;
		for (int i = 0; i < argTypes.length; i++) {
			Type type = argTypes[i];
			mn.visitLocalVariable("arg" + i, type.getDescriptor(), null, start, end, slot);
			slot += type.getSize();
		}
		if (guarded) {
			mn.visitLocalVariable(field, handlerType.getDescriptor(), null, afterJump, end, slot);
		}
		mn.visitMaxs(slot + 1, slot + 2);
		mn.visitEnd();
		return mn;
	}

	private Object getFrameType(Type type) {
		// TODO should not be Type: http://asm.ow2.org/asm40/javadoc/user/org/objectweb/asm/tree/MethodNode.html#visitFrame(int, int, java.lang.Object[], int, java.lang.Object[])
		// TODO inspiration: http://websvn.ow2.org/filedetails.php?repname=asm&path=%2Ftrunk%2Fasm%2Fsrc%2Forg%2Fobjectweb%2Fasm%2Fcommons%2FLocalVariablesSorter.java
		switch (type.getSort()) {
			case Type.BOOLEAN:
			case Type.CHAR:
			case Type.BYTE:
			case Type.SHORT:
			case Type.INT:
				return Opcodes.INTEGER;
			case Type.LONG:
				return Opcodes.LONG;
			case Type.FLOAT:
				return Opcodes.FLOAT;
			case Type.DOUBLE:
				return Opcodes.DOUBLE;
			case Type.ARRAY:
				return type.getDescriptor();
			case Type.OBJECT:
				return type.getInternalName();
			case Type.VOID:
			case Type.METHOD:
		}
		throw new InstrumentationException(type + " can not be converted to frame type");
	}

	/**
	 * changes handled method; adds HANDLER argument, removes private/protected:
	 * <pre><code>
	 *	public MODIFIERS RETURN_TYPE METHOD_NAME(HANDLER handler, [other arguments]) EXCEPTIONS {
	 *		// ...
	 *	}
	 * </code></pre>
	 */
	@SuppressWarnings("unchecked")
	private void changeHandledMethod(MethodNode handledMethod, String field) {
		// TODO prepend "this" (TOP?) to locals in FrameNodes
		handledMethod.access = (handledMethod.access | ACC_PUBLIC) & ~(ACC_PROTECTED | ACC_PRIVATE);
		handledMethod.desc = "(" + handlerType.getDescriptor() + handledMethod.desc.substring(1);
		InsnList instructions = handledMethod.instructions;
		for (AbstractInsnNode node = instructions.getFirst(); node != null; node = node.getNext()) {
			if (node instanceof IincInsnNode) {
				IincInsnNode iinc = (IincInsnNode) node;
				if (iinc.var > 0) {
					iinc.var++;
				}
			} else if (node instanceof VarInsnNode) {
				VarInsnNode var = (VarInsnNode) node;
				if (var.var > 0) {
					var.var++;
				}
			} else if (node instanceof FrameNode) {
				FrameNode frame = (FrameNode) node;
				int type = frame.type;
				if (type == Opcodes.F_FULL || type == Opcodes.F_NEW) {
					frame.local.add(0, handlerType.getDescriptor());
				}
			}
		}
		List<LocalVariableNode> locals = (List<LocalVariableNode>) handledMethod.localVariables;
		if (locals != null) {
			LocalVariableNode thisNode = locals.get(0);
			LabelNode start = thisNode.start;
			LabelNode end = thisNode.end;
			for (int i = 1; i < locals.size(); i++) {
				locals.get(i).index++;
			}
			locals.add(1, new LocalVariableNode(field, handlerType.getDescriptor(), null, start, end, 1));
		}
		handledMethod.maxLocals++;
	}

	/**
	 * swaps attributes and annotations of two methods.
	 * Used to move Attributes / Annotations from the uninstrumented to the instrumented version of a handled method.
	 * @param m1 method
	 * @param m2 method
	 */
	@SuppressWarnings("unchecked")
	private void swapAttributesAndAnnotations(MethodNode m1, MethodNode m2) {
		// with comment based spacing to improve pattern recognition:
		Object annotationDefault = m1.annotationDefault;
		m1.annotationDefault = m2.annotationDefault;
		m2.annotationDefault = annotationDefault;
		//
		List<Attribute> attributes = m1.attrs;
		m1.attrs = m2.attrs;
		m2.attrs = attributes;
		//
		List<AnnotationNode> annotations = m1.invisibleAnnotations;
		m1.invisibleAnnotations = m2.invisibleAnnotations;
		m2.invisibleAnnotations = annotations;
		//
		annotations = m1.visibleAnnotations;
		m1.visibleAnnotations = m2.visibleAnnotations;
		m2.visibleAnnotations = annotations;
		//
		List<AnnotationNode>[] parameterAnnotations = m1.invisibleParameterAnnotations;
		m1.invisibleParameterAnnotations = m2.invisibleParameterAnnotations;
		m2.invisibleParameterAnnotations = parameterAnnotations;
		//
		parameterAnnotations = m1.visibleParameterAnnotations;
		m1.visibleParameterAnnotations = m2.visibleParameterAnnotations;
		m2.visibleParameterAnnotations = parameterAnnotations;
	}

	/**
	 * wraps a handled native method:
	 * <pre><code>
	 *	public final MODIFIERS RETURN_TYPE METHOD_NAME(HANDLER handler, [other arguments]) EXCEPTIONS {
	 *		return <nativePrefix>METHOD_NAME([other arguments]);
	 *	}
	 * </code></pre>
	 */
	private MethodNode wrapNativeMethod(String handleeInternalName, MethodNode handledMethod) {
		// TODO create an agent able to change native methods, use, test and fix this method
		if (this != this) { // we want to crash without dead code warnings: non-trivial way to say "true"
			throw new UnsupportedOperationException(
					"this implementation is not tested yet (but it provides a decent outline)"
			);
		}
		final boolean isStatic = isSomeOf(ACC_STATIC, handledMethod.access);
		final Type[] argTypes = Type.getArgumentTypes(handledMethod.desc);
		final int newAccess = (handledMethod.access | ACC_PUBLIC | ACC_FINAL)
				& ~(ACC_PROTECTED | ACC_PRIVATE | ACC_NATIVE);
		MethodNode mn = new MethodNode(newAccess, handledMethod.name, handledMethod.desc,
				handledMethod.signature, toStringsOrNull(handledMethod.exceptions));
		mn.visitCode();
		Label start = new Label();
		mn.visitLabel(start);
		// first argument is "this"
		if (!isStatic) {
			mn.visitVarInsn(ALOAD, 0);
		}
		for (int i = 0, slot = 1; i < argTypes.length; i++) {
			// then args...
			Type type = argTypes[i];
			mn.visitVarInsn(type.getOpcode(ILOAD), slot);
			slot += type.getSize();
		}
		// call wrapped method
		mn.visitMethodInsn(
				isStatic ? INVOKESTATIC : INVOKESPECIAL,
				handlerType.getInternalName(),
				nativePrefix + handledMethod.name,
				handledMethod.desc,
				false
		);
		// return / return result
		mn.visitInsn(Type.getReturnType(handledMethod.desc).getOpcode(IRETURN));
		Label end = new Label();
		mn.visitLabel(end);
		// wrap up: used vars...
		int slot = 0;
		if (!isStatic) {
			mn.visitLocalVariable("this", handleeInternalName, null, start, end, slot);
			slot++;
		}
		for (int i = 0; i < argTypes.length; i++) {
			Type type = argTypes[i];
			mn.visitLocalVariable("arg" + i, type.getDescriptor(), null, start, end, slot);
			slot += type.getSize();
		}
		mn.visitMaxs(slot, isStatic ? slot : slot - 1);
		mn.visitEnd();
		return mn;
	}

	/**
	 * @param owner the class to be instrumented
	 * @param fieldType the type of the field. <code>null</code> specifies an existing field must be used
	 *		(<code>field</code> has to be specified if <code>fieldType</code> is <code>null</code>)
	 * @param fieldAccess the access modifier (public, protected, package private, private).
	 * @param field the name of the field. <code>null</code> specifies it should be derived
	 *		(remove leading "set" from setter; change name if field exists)
	 * @param getters the names of the get-methods (accessor). <code>null</code> specifies no getter should be created
	 * @param setters the names of the set-methods (modifier).<code>null</code> specifies no setter should be created
	 * @return name of the field
	 */
	private String addProperty(
			ClassNode owner,
			Type fieldType,
			int fieldAccess,
			String field,
			String[] getters,
			String[] setters) {
		FieldNode[] fields = getFields(owner, null, field, null, null);
		if (field == null) {
			String fieldName = setters != null && setters.length > 0
					? setters[0]
					: getters != null && getters.length > 0
					? getters[0]
					: "$" + fieldType.getClassName().replace('/', '_').replace('.', '_') + "$";
			if (fieldName.length() > 3 && fieldName.startsWith("set") || fieldName.startsWith("get")) {
				fieldName = fieldName.substring(3);
			} else if (fieldName.length() > 2 && fieldName.startsWith("is")) {
				fieldName = fieldName.substring(2);
			}
			field = unusedName(fieldName, getNames(fields));
		}
		if (fieldType != null) {
			owner.visitField(fieldAccess, field, fieldType.getDescriptor(), null, null);
		}
		for (String getter : getters != null ? getters : new String[0]) {
			if (getter != null && !"".equals(getter)
					&& getMethods(owner, getter, null, null, null).length == 0) {
				createGetter(owner.name, fieldType, field, getter).accept(owner);
			}
		}
		for (String setter : setters != null ? setters : new String[0]) {
			if (setter != null && !"".equals(setter)
					&& getMethods(owner, setter, null, null, null).length == 0) {
				createSetter(owner.name, fieldType, field, setter).accept(owner);
			}
		}
		return field;
	}

	/**
	 * creates "getter":
	 * <pre><code>
	 *	public FIELD_TYPE METHOD() {
	 *		return this.FIELD;
	 *	}
	 * </code></pre>
	 */
	private MethodNode createGetter(
			String ownerInternalName,
			Type fieldType,
			String field,
			String method) {
		MethodNode mn = new MethodNode(ACC_PUBLIC, method, "()" + fieldType.getDescriptor(), null, null);
		mn.visitCode();
		Label start = new Label();
		mn.visitLabel(start);
		mn.visitVarInsn(ALOAD, 0);
		mn.visitFieldInsn(GETFIELD, ownerInternalName, field, fieldType.getDescriptor());
		mn.visitInsn(fieldType.getOpcode(IRETURN));
		Label end = new Label();
		mn.visitLabel(end);
		mn.visitLocalVariable("this", objDescriptor(ownerInternalName), null, start, end, 0);
		mn.visitMaxs(fieldType.getSize(), 1);
		mn.visitEnd();
		return mn;
	}

	/**
	 * creates "setter":
	 * <pre><code>
	 *	public void METHOD(FIELD_TYPE arg) {
	 *		this.FIELD = arg;
	 *	}
	 * </code></pre>
	 *
	 * or (for Handler-Setters):
	 *
	 * <pre><code>
	 *	public void METHOD(FIELD_TYPE arg) {
	 *		if (arg == null) {
	 *			arg = this;
	 *		}
	 *		this.FIELD = arg;
	 *	}
	 * </code></pre>
	 */
	private MethodNode createSetter(
			String ownerInternalName,
			Type fieldType,
			String field,
			String method) {
		MethodNode mn = new MethodNode(ACC_PUBLIC, method, "(" + fieldType.getDescriptor() + ")V", null, null);
		mn.visitCode();
		Label start = new Label();
		mn.visitLabel(start);
		if (this.handlerType.equals(fieldType)) {
			// on setters for set handler-field to <this> if argument is <null>
			mn.visitVarInsn(ALOAD, 1);
			Label jumpAfter = new Label();
			mn.visitJumpInsn(IFNONNULL, jumpAfter);
			mn.visitVarInsn(ALOAD, 0);
			mn.visitVarInsn(ASTORE, 1);
			mn.visitLabel(jumpAfter);
			mn.visitFrame(F_SAME, 0, null, 0, null);
		}
		mn.visitVarInsn(ALOAD, 0);
		mn.visitVarInsn(ALOAD, 1);
		mn.visitFieldInsn(PUTFIELD, ownerInternalName, field, fieldType.getDescriptor());
		mn.visitInsn(RETURN);
		Label end = new Label();
		mn.visitLabel(end);
		mn.visitLocalVariable("this", objDescriptor(ownerInternalName), null, start, end, 0);
		mn.visitLocalVariable(field, fieldType.getDescriptor(), null, start, end, 1);
		final int maxSlots = 1 + fieldType.getSize();
		mn.visitMaxs(maxSlots, maxSlots);
		mn.visitEnd();
		return mn;
	}
}
