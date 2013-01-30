package utils;

public class PathHelper {

	public static String path(Class<?> relativeLocation, String...simpleNames) {
		String packageName = relativeLocation.getName();
		packageName = packageName.substring(0, packageName.lastIndexOf('.') + 1);
		String delimiter = " ";
		return path(delimiter, packageName, "", simpleNames);
	}

	public static String[] paths(Class<?> relativeLocation, String...simpleNames) {
		return path(relativeLocation, simpleNames).split(" ");
	}

	private static String path(String delimiter, String prefix, String postfix, String...simpleNames) {
		if (delimiter == null || "".equals(delimiter)) delimiter = " ";
		if (prefix == null) prefix = "";
		if (postfix == null) postfix = "";
		StringBuilder builder = new StringBuilder();
		for (String simpleName : simpleNames) {
			builder
				.append(delimiter)
				.append(prefix)
				.append(simpleName)
				.append(postfix)
			;
		}
		return builder.substring(delimiter.length());
	}
}
