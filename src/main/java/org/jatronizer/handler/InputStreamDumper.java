package org.jatronizer.handler;

import java.io.IOException;
import java.io.InputStream;

class InputStreamDumper {

	private byte[] data;
	private InputStreamDumper next;

	private InputStreamDumper() {
	}

	public static byte[] fetchBytes(InputStream in) throws IOException {
		InputStreamDumper first = new InputStreamDumper();
		InputStreamDumper current = first;
		int size = 0;
		for (int available = in.available(); available > 0; available = in.available()) {
			byte[] data = new byte[available];
			int read = 0;
			while (read < available) {
				read += in.read(data, read, data.length - read);
			}
			current.data = data;
			current.next = new InputStreamDumper();
			current = current.next;
			size += read;
		}
		byte[] result = null;
		if (first.next != null) {
			result = new byte[size];
			int offset = 0;
			current = first;
			for (byte[] data = current.data; data != null; current = current.next, data = current.data) {
				System.arraycopy(data, 0, result, offset, data.length);
				offset += data.length;
			}
		} else {
			result = first.data;
		}
		return result;
	}
}