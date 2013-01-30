# Handler

A java instrumentation library for easy method routing.

It's [MIT-licensed](https://github.com/jatronizer/handler/LICENSE).

## What does it do?

It applies the Handler Pattern - a Design Pattern made for tools. It instruments bytecode (a class seen as `[]byte`)
The Handler Pattern as supported by this library gives full control over a method's behavior in a class.
It is a design pattern designed for use with bytecode instrumentation.
An interface serves as a configuration file for the instrumentation.

## How do I use it?

You only declare an interface and point the library at it.

## What can I do with THAT?!?

When this is applied, you are able to intercept, record, change, redirect or ignore method calls. What could this be used for? Some obvious choices - but only scratching the surface:
 * easy creation of mocks for any objects
 * lightweight / focused profilers
 * logging on method calls
 * opening up rigid and restricting libraries
 * ...

## How... What?!?

You want to check how often a method `charToInt` is called on an instance of class `Caster`.

```java
	public final class Caster {
		public Caster() {
		}

		// we want to instrument this method
		public int charToInt(char c) {
			return (int) c;
		}
	}
```

To measure the number of calls to `charToInt` with the Handler Pattern, you create an interface `CharToIntHandler`

```java
	public interface CharToIntHandler {
		// the new version of our method
		int charToInt(CharToIntHandler handler, char c);

		// we need this to set our Handler, HandlerInstrumentation will create this
		void setCharToIntHandler(CharToIntHandler handler);
	}
```

`HandlerInjector` injects `CharToIntHandler` into `Caster` and reroutes the method calls. `Caster` now becomes

```java
	public final class Caster implements CharToIntHandler {
	    // where the Handler is stored
		private CharToIntHandler charToIntHandler;

		public Caster() {
			// set to this at initialization so we can safely call methods
			charToIntHandler = this;
		}

		// the original version with a changed signature
		public int charToInt(CharToIntHandler handler, char c) {
			return (int) c;
		}

		// all calls end up here and are rerouted to the Handler
		public int charToInt(char c) {
			return charToIntHandler.charToInt(this, c);
		}

		// we need this to set the Handler without reflection (casting only)
		public CharToIntHandler setCharToIntHandler(CharToIntHandler charToIntHandler) {
			this.charToIntHandler = charToIntHandler == null ? this : charToIntHandler;
		}
	}
```

Now every instance of `Caster` is castable to `CharToIntHandler` at runtime. Given this `CharToIntHandler` (not thread-safe, but that's up to you)

```java
	public class CallCounter implements CharToIntHandler {
		private int calls = 0;

		public int getCallCount() {
			return calls;
		}

		public int charToInt(CharToIntHandler handlee, char c) {
			calls++;
			return handlee.charToInt(null, c);
		}

		public void setCharToIntHandler(CharToIntHandler handler) {
			// this will never get called on a handler, it's for the handlee
			throw new UnsupportedOperationException("you can't handle this!");
		}
	}
```

You can get the number of charToInt calls your Caster instance receives:

```java
Caster caster = new Caster();
CallCounter counter = new CallCounter();
caster.charToInt(0);
assertTrue(counter.getCallCount() == 0);
((CharToIntHandler) caster).setCharToIntHandler(counter);
caster.charToInt(0);
assertTrue(counter.getCallCount() == 1);
caster.charToInt(0);
assertTrue(counter.getCallCount() == 2);
```