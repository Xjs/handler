package allTests;

import junit.framework.JUnit4TestAdapter;
import junit.framework.TestSuite;

import org.jatronizer.handler.Callable;
import org.jatronizer.handler.HandlerLoader;
import org.junit.runner.JUnitCore;
import org.junit.runner.RunWith;
import utils.PathHelper;

@RunWith(org.junit.runners.AllTests.class)
public class PostInstrumentationTests {

	private static final Class<?> CLASS = Callable.class;
	private static final HandlerLoader LOADER = instrumentTestSubjects();

	private static HandlerLoader instrumentTestSubjects() {
		HandlerLoader loader = new HandlerLoader();
		loader.instrumentFor(CLASS);
		return loader.loadIntoClassLoader(CLASS);
	}

	public static TestSuite suite() throws Exception {
		TestSuite suite = new TestSuite();
		for (String binaryNameOfTest : PathHelper.paths(CLASS,
                "InnerClassesTest", "SuperConstructorCallsHandledMethodTest", "SpawnerTest")) {
			suite.addTest(new JUnit4TestAdapter(Class.forName(binaryNameOfTest)));
		}
		return suite;
	}

	public static void main(String[] args) {
		System.out.println(PostInstrumentationTests.class + ".main");
		try {
			JUnitCore.main(PostInstrumentationTests.class.getName());
		} catch (Exception e) {
			System.err.println("failed");
			e.printStackTrace(System.err);
		}
	}
}
