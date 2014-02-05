import java.io.*;
import java.net.*;
import java.util.*;
import java.security.*;
import javax.xml.bind.*;

/**
* A wrapper for the java.net.Socket class which provides a
* simple implementation of the WebSockets protocol.
* Upon construction this object will attempt to negotiate
* the transition from HTTP to WebSockets and afterwards
* datagrams can be read or written over the resulting channel.
*
* @author John Earnest
**/

public class WebSocket {
	
	private final Socket s;

	/**
	* Negotiate a WebSocket connection via a given TCP Socket.
	* Blocks until negotiation is complete.
	*
	* @param s the Socket to communicate through.
	**/
	public WebSocket(Socket s) throws IOException {
		this.s = s;

		// parse HTTP header to find websocket key:
		Scanner in = new Scanner(s.getInputStream());
		String key = null;
		while(in.hasNextLine()) {
			String line = in.nextLine();
			if (line.equals("")) { break; }
			if (line.startsWith("Sec-WebSocket-Key")) {
				key = line.substring(line.indexOf(':')+1).trim();
			}
		}

		// construct and send HTTP handshake response:
		PrintWriter out = new PrintWriter(s.getOutputStream());
		String response = "nope";
		try {
			String magic = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
			MessageDigest sha = MessageDigest.getInstance("SHA-1");
			byte[] hashed = sha.digest((key + magic).getBytes("UTF-8"));
			response = DatatypeConverter.printBase64Binary(hashed);
		}
		catch(Exception e) {
			// we shouldn't ever get an exception, because the sources
			// which could throw related to UTF-8 encoding and SHA-1
			// hashing being available, which should ALWAYS be there.
			e.printStackTrace();
		}
		out.format("HTTP/1.1 101 Switching Protocols\r\n");
		out.format("Upgrade: websocket\r\n");
		out.format("Connection: Upgrade\r\n");
		out.format("Sec-WebSocket-Accept: %s\r\n", response);
		out.format("\r\n");
		out.flush();
	}

	/**
	* Write some data payload as a single WebSocket transmission.
	* This implementation can handle data payloads up to 64k bytes.
	*
	* @param data the payload to transmit.
	**/
	public void writeFrame(byte[] data) throws IOException {
		byte[] m;
		if (data.length > 125) {
			m = new byte[4 + data.length];
			m[1] = (byte)0x7E; // no mask, 16-bit extended payload length
			m[2] = (byte)((data.length >>> 8) & 0xFF);
			m[3] = (byte)((data.length      ) & 0xFF);
		}
		else {
			m = new byte[2 + data.length];
			m[1] = (byte)(data.length & 0x7F); // no mask, 7-bit short payload length
		}
		m[0] = (byte)0x82; // final (only) fragment, binary frame
		System.arraycopy(data, 0, m, m.length-data.length, data.length);
		s.getOutputStream().write(m);
		s.getOutputStream().flush();
	}

	/**
	* Block on the input channel until a complete datagram
	* payload has been accepted. This implementation can handle
	* data payloads up to 125 bytes, and only accepts single-chunk
	* transmissions. It's probably brittle for non-tiny messages.
	*
	* @returns a zero length array if the connection has closed.
	**/
	public byte[] readFrame() throws IOException {
		InputStream in = s.getInputStream();
		int flags = in.read();
		if (flags == -1)          { return new byte[0]; }
		if ((flags & 0xF) == 0x8) { return new byte[0]; }

		if (flags != 0x82) { throw new Error("unimplemented datagram format: " + flags); }
		int lenm = in.read();

		// TODO: handle extended payload lengths?

		byte[] mask = null;
		if ((lenm & 0x80) != 0) {
			mask = new byte[4];
			for(int x = 0; x < 4; x++) { mask[x] = (byte)in.read(); }
		}
		int payload = (lenm & 0x7F);
		byte[] ret = new byte[payload];
		int index = 0;
		while(index < payload) {
			index += in.read(ret, index, payload-index);
		}
		if (mask != null) {
			for(int x = 0; x < payload; x++) { ret[x] ^= mask[x % 4]; }
		}
		return ret;
	}
}
