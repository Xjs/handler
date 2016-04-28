package org.jatronizer.handler;

import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.Map;

import org.objectweb.asm.tree.ClassNode;

public class Agent implements ClassFileTransformer {

	private static class HandlerSetup {
		private static final char HANDLER_SEPARATOR = ';';
		private static final char SPAWNER_SEPARATOR = ':';
		private static final char HANDLEE_ASSOCIATOR = '=';
		private static final char HANDLEE_SEPARATOR = ',';

		public static HandlerSetup[] parseArgs(String args) {
			String[] handlerSpecs = args.split("" + HANDLER_SEPARATOR);
			HandlerSetup[] result = new HandlerSetup[handlerSpecs.length];
			for (int i = 0; i < handlerSpecs.length; i++) {
				result[i] = new HandlerSetup(handlerSpecs[i]);
			}
			return result;
		}

		public final String handlerName;
		public final String handlerSpawnerName;
		public final String[] handleeNames;

		private HandlerSetup(String handlerSpec) {
			int indexOfHandlees = handlerSpec.indexOf(HANDLEE_ASSOCIATOR);
			if (indexOfHandlees < 0) {
				this.handleeNames = handlerSpec.substring(indexOfHandlees + 1).split("" + HANDLEE_SEPARATOR);
			} else {
				handlerSpec = handlerSpec.substring(0, indexOfHandlees);
				this.handleeNames = new String[0];
			}
			int indexOfSpawner = handlerSpec.indexOf(SPAWNER_SEPARATOR);
			if (indexOfSpawner < 0) {
				this.handlerName = handlerSpec;
				this.handlerSpawnerName = null;
			} else {
				this.handlerName = handlerSpec.substring(0, indexOfSpawner);
				this.handlerSpawnerName = handlerSpec.substring(indexOfSpawner);
			}
		}
	}

	private HandlerSetup[] setups;
	private final Map<String, ASMTreeInstrumentation> instrumentationPlan;

	public Agent(HandlerSetup... setups) {
		if (setups != null && setups.length == 0) {
			// guarantee it is never an array with length == 0
			// so we can avoid further length checks later
			setups = null;
		}
		this.setups = setups;
		this.instrumentationPlan =  new HashMap<String, ASMTreeInstrumentation>();
	}

	public Agent(Map<String, ASMTreeInstrumentation> instrumentationPlan) {
		this.instrumentationPlan = instrumentationPlan;
	}

	private void init(ClassLoader loader, HandlerSetup...setups) {
		for (HandlerSetup setup : setups) {
			final InputStream bytecodeStream = loader.getResourceAsStream(
					setup.handlerName.replace('.', '/')
			);
			init(ASMSupport.asBytes(bytecodeStream), setup);
		}
	}

	private void init(byte[] handlerBytecode, HandlerSetup setup) {
		ClassNode handlerNode = ASMSupport.asNode(handlerBytecode);
		HandlerInstrumentation instrumentation = new HandlerInstrumentation(
				handlerNode,
				setup.handlerSpawnerName,
				"$" + setup.handlerName.replace('.', '_') + "$"
		);
		for (String handlee : setup.handleeNames) {
			instrumentationPlan.put(handlee, instrumentation);
		}
		for (String handlee : ASMSupport.getInstrumentationTargets(handlerNode)) {
			instrumentationPlan.put(handlee, instrumentation);
		}
	}

	// for native methods (probably not useful for Handler-Injection):
	// java.lang.instrument.Instrumentation.setNativeMethodPrefix(ClassFileTransformer, String)
	public byte[] transform(ClassLoader loader, String clsName, Class<?> cls,
							ProtectionDomain pdom, byte[] buffer) throws IllegalClassFormatException {
		// this is a little convoluted - initialize the object on first call of transform
		// and keep the performance penalty for the initalization check low.
		// "this.setups == null" indicates everything is initialized.
		// This has to happen here, we need access to classes outside of the agent jar
		final HandlerSetup[] setups = this.setups;
		if (setups != null) {
			synchronized (setups) {
				// setups[0] == null is our indicator in setups that initialization is complete
				if (setups[0] != null) {
					init(loader, this.setups);
					setups[0] = null;
					this.setups = null;
				}
			}
			System.out.println(" < " + clsName);
			for (String targetClass : instrumentationPlan.keySet()) {
				System.out.println(" > " + targetClass);
			}
		}
		// it's initialized, we can continue
		ASMTreeInstrumentation instrumentation = instrumentationPlan.get(clsName);
		if (instrumentation != null) {
			try {
				return ASMSupport.asBytes(instrumentation.instrument(ASMSupport.asNode(buffer)));
			} catch (Exception e) {
				throw new InstrumentationException("could not instrument " + clsName + " in agent", e);
			}
		}
		return buffer;
	}

	public static void premain(String agentArgs, Instrumentation inst) {
		agentmain(agentArgs, inst);
	}

	public static void agentmain(String agentArgs, Instrumentation inst) {
		if (agentArgs == null || agentArgs.isEmpty()) {
			usage(true);
		}
		inst.addTransformer(new Agent(HandlerSetup.parseArgs(agentArgs)));
	}

	public static void usage(boolean argError) {
		if (argError) {
			System.err.println(
					"missing handler class specification (agent args):"
			);
		} else {
			System.err.println(
					"This is not an executable jar, it is a library which makes Java more adaptable and flexible.\n"
					+ "Have a look at the API documentation. The concept is explained in the class HandlerInstrumentation,\n"
					+ "but to use it you have to provide an interface (which should be annotated with Instruments).\n"
					+ "Then, you can modify classes by loading them through HandlerLoader.\n\n"
					+ "You may also use this as an agent and start another jar with:\n"
					+ "  -javaagent:JAR=ARGS\n"
					+ "whereas ARGS is:"
			);
		}
		System.err.println(
				"  one or more handler specifications (separated by ';')\n" +
				"  a handler specification consists of the handler name and the handlees (',' separated)\n" +
				"  handlees specified with the Instruments annotation in the handler are automatically added\n" +
				"  examples:\n" +
				"    my.Handler\n" +
				"    my.Handler=my.Handlee\n" +
				"    my.Handler=my.Handlee1,my.Handlee2\n" +
				"    my.HandlerWithAnnotation;my.Handler2=my.Handlee1,my.Handlee2$Inner"
		);
	}
}
