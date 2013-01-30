package org.jatronizer.handler;

import org.objectweb.asm.tree.ClassNode;

public interface ASMTreeInstrumentation {
	ClassNode instrument(ClassNode source);
}
