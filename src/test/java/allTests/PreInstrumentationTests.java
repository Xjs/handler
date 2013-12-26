package allTests;

import static utils.PathHelper.path;

import junit.framework.JUnit4TestAdapter;
import junit.framework.Test;
import junit.framework.TestSuite;

import org.jatronizer.handler.Callable;
import org.jatronizer.handler.InnerClassesTest;
import org.jatronizer.handler.SpawnerTest;
import org.jatronizer.handler.SuperConstructorCallsHandledMethodTest;
import org.junit.runner.JUnitCore;
import org.junit.runner.RunWith;

// if you want to compare results with/without instrumentation, run this one
@RunWith(org.junit.runners.AllTests.class)
public class PreInstrumentationTests {

	private static Test test(String className) throws ClassNotFoundException {
		return test(Class.forName(path(Callable.class, className)));
	}

	private static Test test(Class<?> testClass) throws ClassNotFoundException {
		return new JUnit4TestAdapter(testClass);
	}

	public static TestSuite suite() throws Exception {
		TestSuite suite = new TestSuite();
		suite.addTest(test("InnerClassesTest"));
		suite.addTest(test("SuperConstructorCallsHandledMethodTest"));
		suite.addTest(test("SpawnerTest"));
		return suite;
	}

	public static void main(String[] args) {
		System.out.println(PreInstrumentationTests.class + ".main");
		try {
			JUnitCore.main(PreInstrumentationTests.class.getName());
		} catch (Exception e) {
			System.err.println("failed");
			e.printStackTrace(System.err);
			System.exit(-1);
		}
	}
}