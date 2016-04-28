package org.jatronizer.handler;

import static org.jatronizer.handler.ASMSupport.asBytes;
import static org.jatronizer.handler.ASMSupport.asNode;
import static org.jatronizer.handler.ASMSupport.toPath;


import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import org.objectweb.asm.tree.ClassNode;

public class HandlerLoader extends ClassLoader {

	private final DependencyTree dependencies = new DependencyTree();
	private final Map<String, byte[]> declaredClasses = new HashMap<String, byte[]>();
	private final boolean reload;

	public HandlerLoader() {
		this(false);
	}

	public HandlerLoader(ClassLoader parent) {
		this(parent, false);
	}

	public HandlerLoader(boolean reloadLoadedClasses) {
		super();
		this.reload = reloadLoadedClasses;
	}

	public HandlerLoader(ClassLoader parent, boolean reloadLoadedClasses) {
		super(parent);
		this.reload = reloadLoadedClasses;
	}

	private String[] getClassNamesToLoad(String...binaryNames) {
		return dependencies.getClassesToLoad(binaryNames);
	}

	private byte[] getBytecode(String binaryName) {
		byte[] bytecode = declaredClasses.get(binaryName);
		if (bytecode == null) {
			bytecode = asBytes(toPath(binaryName));
		}
		return bytecode;
	}

	/**
	 * retrieves an Instrumentor for the specified Handler.
	 * Retrieving multiple Instrumentors for the same binaryName will probably cause
	 * errors if there are interdependencies between the instrumented classes.
	 * Instrumenting the same class more then once will cause errors.
	 * There is no error handling in place for these cases yet!
	 * You will be informed by the Verifier if you mess up, though.
	 * @param binaryName binary name of the Handler interface you want to inject
	 * @param defaultHandlerSpawner a static method retrieving the first Handler (set in the Handlees constructor).<br>
	 *		It must be reachable (<code>public</code> method and class, <code>static</code> class if inner class)
	 *		<code>static</code> method.<br>
	 *		It's signature must be <code>HANDLER method(HANDLER, boolean)</code>
	 *		(return the HANDLER parameter for "no build").<br>
	 *		It must not return null or throw or declare Exceptions.<br>
	 *		The Handlee passes itself and a boolean (<code>false</code> if you must not call methods on the
	 *		Handlee because it is not fully initialized yet).<br>
	 *		For a Handler <code>X</code>, this example sets no build (like passing <code>null</code> instead):
	 *		<pre><code>
	 *		package example;
	 *
	 *		class Spawner {
	 *			public static X spawn(X handlee) {
	 *				return handlee;
	 *			}
	 *		}
	 *		</code></pre>
	 *		The parameter for this example is <code>example.Utils$Spawner.spawn</code>
	 *		(just like a binary name with a method suffix).<br>
	 *		The spawner is used to initialize the handlee's build after the <code>super(...)</code>
	 *		constructor was called.
	 */
	public Instrumentor instrumentFor(String binaryName, String defaultHandlerSpawner, String...instrumentedClasses) {
		ClassNode handlerNode = asNode(toPath(binaryName));
		String[] instrumentationTargets = ASMSupport.getInstrumentationTargets(handlerNode);
		String handlerSpawner = defaultHandlerSpawner != null
				? defaultHandlerSpawner
				: ASMSupport.getSpawner(handlerNode);
		Instrumentor instrumentor = new ASMTreeInstrumentor(
			new HandlerInstrumentation(
				handlerNode,
				handlerSpawner,
				null
			),
			dependencies,
			declaredClasses
		);
		instrumentor.transform(instrumentationTargets);
		instrumentor.transform(instrumentedClasses);
		return instrumentor;
	}

	/**
	 * retrieves an Instrumentor for the specified Handler.
	 * @see org.jatronizer.handler.HandlerLoader:instrumentFor(String, String)
	 * @param binaryName binary name of the Handler interface you want to inject
	 */
	public Instrumentor instrumentFor(String binaryName) {
		return instrumentFor(binaryName, null);
	}

	/**
	 * retrieves an Instrumentor for the specified Handler.
	 * @see org.jatronizer.handler.HandlerLoader:instrumentFor(String, String)
	 * @param handler class of the build type
	 * @param defaultHandlerSpawner a static method that retrieves the first Handler (set in the Handlees constructor)
	 */
	public Instrumentor instrumentFor(Class<?> handler, String defaultHandlerSpawner) {
		return instrumentFor(handler.getName(), defaultHandlerSpawner);
	}

	/**
	 * retrieves an Instrumentor for the specified Handler.
	 * @see org.jatronizer.handler.HandlerLoader:instrumentFor(String, String)
	 * @param handler class of the build type
	 */
	public Instrumentor instrumentFor(Class<?> handler) {
		return instrumentFor(handler.getName(), null);
	}

	/**
	 * load bytecode.
	 * This method exists for convenience reasons only and is probably not safe,
	 * but this classloader provides this method's ability anyway and must shielded from malvolent code.
	 * @param bytecode a class' bytecode
	 * @return the class instance
	 */
	public Class<?> loadClass(byte[] bytecode) {
		return loadClass(bytecode, false);
	}

	/**
	 * load and resolve bytecode.
	 * This method exists for convenience reasons only and is probably not safe,
	 * but this classloader provides this method's ability anyway and must shielded from malvolent code.
	 * @param bytecode a class' bytecode
	 * @param resolve <code>true</code> if the class should be resolved
	 * @return the class instance
	 */
	public synchronized Class<?> loadClass(byte[] bytecode, boolean resolve) {
		Class<?> result = defineClass(null, bytecode, 0, bytecode.length);
		if (resolve) {
			resolveClass(result);
		}
		return result;
	}

	@Override
	public Class<?> loadClass(String binaryName) throws ClassNotFoundException {
		// we need to make sure there's only one entry point to classloading
		return loadClass(binaryName, false);
	}

	@Override
	protected synchronized Class<?> loadClass(String binaryName, boolean resolve) throws ClassNotFoundException {
		// load outermost classes first (by recursion). Otherwise, you'd get a linking error
		if (binaryName.indexOf('$') >= 0) {
			loadClass(binaryName.substring(0, binaryName.lastIndexOf('$')), resolve);
		}
		Class<?> result;
		if ((result = findLoadedClass(binaryName)) != null) {
			return result;
		}
		if (dependencies.contains(binaryName) || reload) {
			result = findLoadedClass(binaryName);
			if (result == null) {
				result = findClass(binaryName);
			}
			if (resolve) {
				resolveClass(result);
			}
		} else {
			result = super.loadClass(binaryName, resolve);
		}
		return result;
	}

	@Override
	protected Class<?> findClass(String binaryName) throws ClassNotFoundException {
		byte[] bytecode = declaredClasses.get(binaryName);
		if (bytecode == null || reload) {
			if (binaryName.startsWith("java.")) {
				// "java" and its subpackages can't be instrumented in a classloader, only by using an agent
				return getSystemClassLoader().loadClass(binaryName);
			}
			bytecode = asBytes(toPath(binaryName));
		}
		if (bytecode != null) {
			return defineClass(binaryName, bytecode, 0, bytecode.length);
		}
		return null;
	}

	/**
	 * load the specified classes (or all instrumented classes) and all their dependencies with the SystemClassLoader.
	 * @param binaryNames binary names of all classes you want to load - none loads all instrumented classes
	 * @return <code>this</code> (it's a fluent interface - you can chain calls)
	 */
	public HandlerLoader loadIntoSystemClassLoader(String...binaryNames) {
		return loadIntoClassLoader(ClassLoader.getSystemClassLoader(), binaryNames);
	}

	/**
	 * load the specified classes (or all instrumented classes) and all their dependencies
	 * with the ClassLoader of the specified class.
	 * @param loadedClass a class loaded by the ClassLoader you want to load these classes with
	 * @param binaryNames binary names of all classes you want to load - none loads all instrumented classes
	 * @return <code>this</code> (it's a fluent interface - you can chain calls)
	 */
	public HandlerLoader loadIntoClassLoader(Class<?> loadedClass, String...binaryNames) {
		return loadIntoClassLoader(loadedClass.getClassLoader(), binaryNames);
	}

	/**
	 * load the specified classes (or all instrumented classes)
	 * and all their dependencies with the specified ClassLoader.
	 * @param loader ClassLoader
	 * @param binaryNames binary names of all classes you want to load - none loads all instrumented classes
	 * @return <code>this</code> (it's a fluent interface - you can chain calls)
	 */
	public HandlerLoader loadIntoClassLoader(ClassLoader loader, String...binaryNames) {
		final String[] classNamesToLoad = getClassNamesToLoad(binaryNames);
		Method defineClass = null;
		Method findLoadedClass = null;
		try {
			// crack ClassLoader wide open and force-feed it with our classes
			defineClass = ClassLoader.class.getDeclaredMethod(
					"defineClass", String.class, byte[].class, int.class, int.class);
			defineClass.setAccessible(true);
			findLoadedClass = ClassLoader.class.getDeclaredMethod(
					"findLoadedClass", String.class);
			findLoadedClass.setAccessible(true);
			for (String binaryName : classNamesToLoad) {
				if (!binaryName.startsWith("java.")) {
					if (findLoadedClass.invoke(loader, binaryName) == null) {
						byte[] bytecode = getBytecode(binaryName);
						defineClass.invoke(loader, binaryName, bytecode, 0, bytecode.length);
					} else if (declaredClasses.containsKey(binaryName)) {
						// if a declared class was already loaded, instrumentation failed
						throw new InstrumentationException(
								"Class " + binaryName + " was already loaded, it must not be redeclared"
						);
					}
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
			throw new InstrumentationException("could not load classes into ClassLoader", e);
		} finally {
			hideMethod(findLoadedClass);
			hideMethod(defineClass);
		}
		return this;
	}

	private void hideMethod(Method m) {
		if (m != null) {
			try {
				m.setAccessible(false);
			} catch (Exception e) {
			}
		}
	}

	/**
	 * call the <code>main</code> method with the specified arguments on a class.
	 * @param binaryName the binary name of the class (e.g. <code>somepackage.SomeClass$PublicStaticInnerClass</code>)
	 * @param args the arguments for <code>main</code>
	 */
	public void callMain(String binaryName, String...args) throws
			IllegalArgumentException, SecurityException,
			IllegalAccessException, InvocationTargetException,
			NoSuchMethodException, ClassNotFoundException {
		callMain(loadClass(binaryName), args);
	}

	/**
	 * call the <code>main</code> method with the specified arguments on a class.
	 * @param bytecode the bytecode of the class <code>main</code> should be called on
	 * @param args the arguments for <code>main</code>
	 */
	public void callMain(byte[] bytecode, String...args) throws
			IllegalArgumentException, SecurityException,
			IllegalAccessException, InvocationTargetException,
			NoSuchMethodException, ClassNotFoundException {
		callMain(loadClass(bytecode), args);
	}

	/**
	 * call the <code>main</code> method with the specified arguments on a class.
	 * @param c the class <code>main</code> should be called on
	 * @param args the arguments for <code>main</code>
	 */
	public void callMain(Class<?> c, String...args) throws
			IllegalArgumentException, SecurityException,
			IllegalAccessException, InvocationTargetException,
			NoSuchMethodException, ClassNotFoundException {
		c.getMethod("main", String[].class).invoke(null, new Object[]{args});
	}
}
