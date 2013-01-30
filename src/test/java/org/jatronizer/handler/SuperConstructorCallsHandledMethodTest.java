package org.jatronizer.handler;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import junit.framework.TestCase;

public class SuperConstructorCallsHandledMethodTest extends TestCase {

	// these are arbitrary, I just did not want them to be 0
	private static final int PARENT = 7;
	private static final int CHILD = ~PARENT;

	public static class Parent {

		public final int result;

		public Parent() {
			result = call();
		}

		public int call() {
			return PARENT;
		}
	}

	public static class Child extends Parent {

		public Child() {
			super();
		}

		public int call() {
			return CHILD;
		}
	}

	public static class InstrumentedChild extends Parent implements Callable {

		public transient volatile Callable Callable;

		public InstrumentedChild() {
			super();
			this.Callable = this;
		}

		public int call(Callable Callable) {
			return CHILD;
		}

		public int call() {
			return Callable.call(this);
		}

		public void setCallable(Callable Callable) {
			if (Callable == null) Callable = this;
			this.Callable = Callable;
		}
	}

	@Test
	public void testInstrumentedBehaviour() {
		Child child = new Child();
		assertEquals(new Parent().result, PARENT);
		assertEquals(child.result, CHILD);
		assertEquals(child.call(), CHILD);
		final AtomicInteger callResult = new AtomicInteger(0);
		Callable childHandler = new Callable() {
			public int call(Callable caller) {
				return callResult.get();
			}

			public void setCallable(Callable callee) {
				throw new UnsupportedOperationException();
			}
		};
		((Callable) child).setCallable(childHandler);
		for (int i = 0; i < 10; i++) {
			callResult.incrementAndGet();
			assertEquals(child.call(), callResult.get());
		}
	}
}
