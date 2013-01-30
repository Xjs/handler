package org.jatronizer.handler;

import static org.jatronizer.handler.CallableSpawner.CALL_RESULT;

import org.junit.Test;

import junit.framework.TestCase;

public class SpawnerTest extends TestCase {

	public int rawCall() {
		return ~CALL_RESULT;
	}

	public int call() {
		return rawCall();
	}

	@Test
	public void testInitiallyUsesSpawner() {
		assertTrue("use behavior from Handler on startup when a spawner is used", CALL_RESULT == call());
		assertTrue("different return values due to initialization spawner", rawCall() != call());
		((Callable) this).setCallable(null);
		assertTrue("matching return values as handler was reset", rawCall() == call());
		assertTrue("revert to uninstrumented behavior after setting the handler to null", rawCall() == call());
	}
}
