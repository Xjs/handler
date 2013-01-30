package org.jatronizer.handler;

public class InstrumentationException extends RuntimeException {

	private static final long serialVersionUID = 4810124386668248111L;

	public InstrumentationException() {
		super();
	}

	public InstrumentationException(String message, Throwable cause) {
		super(message, cause);
	}

	public InstrumentationException(String message) {
		super(message);
	}

	public InstrumentationException(Throwable cause) {
		super(cause);
	}
}