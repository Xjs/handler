package org.jatronizer.handler;

import org.junit.Test;

import junit.framework.TestCase;

public class InnerClassesTest extends TestCase {

	public int call() {
		return 1;
	}

	abstract class CallableAdapter implements Callable {
		public void setCallable(Callable unused) {}
	}

	class ConstantHandler extends CallableAdapter {
		private final int value;
		public ConstantHandler(int value) {
			this.value = value;
		}
		public int call(Callable handler) {
			return value;
		}
	}

	class ExceptionalHandler extends CallableAdapter {
		private final RuntimeException exception;
		public ExceptionalHandler(RuntimeException exception) {
			this.exception = exception;
		}
		public int call(Callable handler) {
			throw exception;
		}
	}

	public void setHandler(Callable callable) {
		((Callable) this).setCallable(callable);
	}

	@Test
	public void testDefaultBehaviour() {
		assertEquals(1, call());
	}

	@Test
	public void testChangedResult() {
		setHandler(new ConstantHandler(1234));
		assertEquals(1234, call());
		setHandler(null);
		assertEquals(1, call());
	}

	@Test
	public void testThrowsException() {
		final RuntimeException exception = new RuntimeException();
		setHandler(new ExceptionalHandler(exception));
		try {
			call();
			fail();
		} catch (RuntimeException e) {
			assertEquals(exception, e);
		}
	}

	@Test
	public void testAnonymousInnerThrowsException() {
		final RuntimeException exception = new RuntimeException();
		setHandler(
			new CallableAdapter(){
				public int call(Callable handler) {
					throw exception;
				}
			}
		);
		try {
			call();
			fail();
		} catch (RuntimeException e) {
			assertEquals(exception, e);
		}
	}
}
