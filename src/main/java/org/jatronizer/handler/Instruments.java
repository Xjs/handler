package org.jatronizer.handler;

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
public @interface Instruments {
	Class<?>[] value();
}
