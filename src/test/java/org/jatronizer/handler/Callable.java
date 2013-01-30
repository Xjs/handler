package org.jatronizer.handler;

@Instruments({
	InnerClassesTest.class,
	SuperConstructorCallsHandledMethodTest.Child.class,
	SpawnerTest.class
})
@SpawnsWith("org.jatronizer.handler.CallableSpawner.spawner")
public interface Callable {

	int call(Callable caller);
	void setCallable(Callable callee);
}
