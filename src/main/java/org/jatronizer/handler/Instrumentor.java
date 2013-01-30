package org.jatronizer.handler;

public interface Instrumentor {

	/**
	 * apply the underlying instrumentation to all specified classes
	 * @param binaryNames binary names of the classes (e.g. <code>somepackage.SomeClass$PublicStaticInnerClass</code>)
	 * @return <code>this</code> (so you can conveniently call the next method)
	 */
	Instrumentor transform(String...binaryNames);

	/**
	 * apply the underlying instrumentation to all specified classes
	 * @param bytecodes bytecode for classes that should be instrumented
	 * @return <code>this</code> (so you can conveniently call the next method)
	 */
	Instrumentor transform(byte[]...bytecodes);
}