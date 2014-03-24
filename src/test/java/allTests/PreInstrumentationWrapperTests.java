package allTests;

import static utils.PathHelper.path;

import junit.framework.TestSuite;

import org.jatronizer.handler.Callable;
import org.jatronizer.handler.HandlerLoader;
import org.junit.runner.JUnitCore;
import org.junit.runner.RunWith;

@RunWith(org.junit.runners.AllTests.class)
public class PreInstrumentationWrapperTests {

	private final static String WRAPPED_TESTS =
			path(PreInstrumentationWrapperTests.class, "PreInstrumentationTests");

	private static HandlerLoader instrumentTestSubjects() {
		HandlerLoader loader = new HandlerLoader();
		loader.instrumentFor(Callable.class);
		return loader.loadIntoClassLoader(Callable.class);
	}

	public static TestSuite suite() throws Exception {
		HandlerLoader loader = instrumentTestSubjects();
		return (TestSuite) loader.loadClass(WRAPPED_TESTS).getMethod("suite").invoke(null);
	}

	public static void main(String[] args) {
		try {
			HandlerLoader loader = instrumentTestSubjects();
			JUnitCore.main(WRAPPED_TESTS);
		} catch (Exception e) {
			System.err.println("failed");
			e.printStackTrace(System.err);
			System.exit(-1);
		}
	}
}
