/*
 * todesbaum-lib -
 * Copyright (C) 2006 David Roden
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */
package pluginbase.de.todesbaum.util.freenet.fcp2;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * A physical connection to a Freenet node.
 *
 * @author David Roden &lt;droden@gmail.com&gt;
 * @version $Id$
 */
public class Connection {

	/**
	 * The listeners that receive events from this connection.
	 */
	private final List<ConnectionListener> connectionListeners = new ArrayList<>();

	/**
	 * The node this connection is connected to.
	 */
	private final Node node;

	/**
	 * The name of this connection.
	 */
	private final String name;

	/**
	 * The network socket of this connection.
	 */
	private Socket nodeSocket;

	/**
	 * The input stream that reads from the socket.
	 */
	private InputStream nodeInputStream;

	/**
	 * The output stream that writes to the socket.
	 */
	private OutputStream nodeOutputStream;

	/**
	 * The thread that reads from the socket.
	 */
	private NodeReader nodeReader;

	/**
	 * A writer for the output stream.
	 */
	private Writer nodeWriter;

	/**
	 * The NodeHello message sent by the node on connect.
	 */
	protected Message nodeHello;

	/**
	 * The temp directory to use.
	 */
	private String tempDirectory;

	/**
	 * Creates a new connection to the specified node with the specified name.
	 *
	 * @param node The node to connect to
	 * @param name The name of this connection
	 */
	public Connection(Node node, String name) {
		this.node = node;
		this.name = name;
	}

	/**
	 * Adds a listener that gets notified on connection events.
	 *
	 * @param connectionListener The listener to add
	 */
	public void addConnectionListener(ConnectionListener connectionListener) {
		connectionListeners.add(connectionListener);
	}

	/**
	 * Removes a listener from the list of registered listeners. Only the first
	 * matching listener is removed.
	 *
	 * @param connectionListener The listener to remove
	 * @see List#remove(java.lang.Object)
	 */
	public void removeConnectionListener(ConnectionListener connectionListener) {
		connectionListeners.remove(connectionListener);
	}

	/**
	 * Notifies listeners about a received message.
	 *
	 * @param message The received message
	 */
	protected void fireMessageReceived(Message message) {
		for (ConnectionListener connectionListener : connectionListeners) {
			connectionListener.messageReceived(this, message);
		}
	}

	/**
	 * Notifies listeners about the loss of the connection.
	 */
	protected void fireConnectionTerminated() {
		for (ConnectionListener connectionListener : connectionListeners) {
			connectionListener.connectionTerminated(this);
		}
	}

	/**
	 * Returns the name of the connection.
	 *
	 * @return The name of the connection
	 */
	public String getName() {
		return name;
	}

	/**
	 * Sets the temp directory to use for creation of temporary files.
	 *
	 * @param tempDirectory The temp directory to use, or {@code null} to use
	 * the default temp directory
	 */
	public void setTempDirectory(String tempDirectory) {
		this.tempDirectory = tempDirectory;
	}

	/**
	 * Connects to the node.
	 *
	 * @return <code>true</code> if the connection succeeded and the node
	 * returned a NodeHello message
	 * @throws IOException if an I/O error occurs
	 * @see #getNodeHello()
	 */
	public synchronized boolean connect() throws IOException {
		nodeSocket = null;
		nodeInputStream = null;
		nodeOutputStream = null;
		nodeWriter = null;
		nodeReader = null;
		try {
			nodeSocket = new Socket(node.getHostname(), node.getPort());
			nodeSocket.setReceiveBufferSize(65535);
			nodeInputStream = nodeSocket.getInputStream();
			nodeOutputStream = nodeSocket.getOutputStream();
			nodeWriter = new OutputStreamWriter(nodeOutputStream, Charset.forName("UTF-8"));
			nodeReader = new NodeReader(nodeInputStream);
			Thread nodeReaderThread = new Thread(nodeReader);
			nodeReaderThread.setDaemon(true);
			nodeReaderThread.setName("KeepAlive FCP Thread");
			nodeReaderThread.start();
			ClientHello clientHello = new ClientHello();
			clientHello.setName(name);
			clientHello.setExpectedVersion("2.0");
			execute(clientHello);
			synchronized (this) {
				try {
					wait(10000);
				} catch (InterruptedException e) {
				}
			}
			return nodeHello != null;
		} catch (IOException ioe1) {
			disconnect();
			throw ioe1;
		}
	}

	/**
	 * Returns whether this connection is still connected to the node.
	 *
	 * @return <code>true</code> if this connection is still valid,
	 * <code>false</code> otherwise
	 */
	public boolean isConnected() {
		return (nodeHello != null) && (nodeSocket != null) && (nodeSocket.isConnected());
	}

	/**
	 * Returns the NodeHello message the node sent on connection.
	 *
	 * @return The NodeHello message of the node
	 */
	public Message getNodeHello() {
		return nodeHello;
	}

	/**
	 * Disconnects from the node.
	 */
	public void disconnect() {
		if (nodeWriter != null) {
			try {
				nodeWriter.close();
			} catch (IOException ioe1) {
			}
			nodeWriter = null;
		}
		if (nodeOutputStream != null) {
			try {
				nodeOutputStream.close();
			} catch (IOException ioe1) {
			}
			nodeOutputStream = null;
		}
		if (nodeInputStream != null) {
			try {
				nodeInputStream.close();
			} catch (IOException ioe1) {
			}
			nodeInputStream = null;
		}
		if (nodeSocket != null) {
			try {
				nodeSocket.close();
			} catch (IOException ioe1) {
			}
			nodeSocket = null;
		}
		synchronized (this) {
			notify();
		}
		fireConnectionTerminated();
	}

	/**
	 * Executes the specified command.
	 *
	 * @param command The command to execute
	 * @throws IllegalStateException if the connection is not connected
	 * @throws IOException if an I/O error occurs
	 */
	public synchronized void execute(Command command) throws IllegalStateException, IOException {
		if (nodeSocket == null) {
			throw new IllegalStateException("connection is not connected");
		}
		nodeWriter.write(command.getCommandName() + Command.LINEFEED);
		command.write(nodeWriter);
		nodeWriter.write("EndMessage" + Command.LINEFEED);
		nodeWriter.flush();
		if (command.hasPayload()) {
			InputStream payloadInputStream = null;
			try {
				payloadInputStream = command.getPayload();
				StreamCopier.copy(payloadInputStream, nodeOutputStream, command.getPayloadLength());
			} finally {
				Closer.close(payloadInputStream);
			}
			nodeOutputStream.flush();
		}
	}

	/**
	 * The reader thread for this connection. This is essentially a thread that
	 * reads lines from the node, creates messages from them and notifies
	 * listeners about the messages.
	 *
	 * @author David Roden &lt;droden@gmail.com&gt;
	 * @version $Id$
	 */
	protected class NodeReader implements Runnable {        // changed by jeriadoc 11/2011: private -> protected

		/**
		 * The input stream to read from.
		 */
		@SuppressWarnings("hiding")
		private final InputStream nodeInputStream;

		/**
		 * Creates a new reader that reads from the specified input stream.
		 *
		 * @param nodeInputStream The input stream to read from
		 */
		public NodeReader(InputStream nodeInputStream) {
			this.nodeInputStream = nodeInputStream;
		}

		/**
		 * Main loop of the reader. Lines are read and converted into
		 * {@link Message} objects.
		 */
		@Override
		public void run() {
			LineInputStream nodeReader = null;
			try {
				nodeReader = new LineInputStream(nodeInputStream);
				String line;
				Message message = null;
				while (true) {
					line = nodeReader.readLine();
					// System.err.println("> " + line);
					if (line == null) {
						break;
					}
					if (message == null) {
						message = new Message(line);
						continue;
					}
					if ("Data".equals(line)) {
						/* need to read message from stream now */
						File tempFile;
						try {
							tempFile = File.createTempFile("fcpv2", "data", (tempDirectory != null) ? new File(tempDirectory) : null);
							tempFile.deleteOnExit();
							try (FileOutputStream tempFileOutputStream = new FileOutputStream(tempFile)) {
								long dataLength = Long.parseLong(message.get("DataLength"));
								StreamCopier.copy(nodeInputStream, tempFileOutputStream, dataLength);
							}
							message.setPayloadInputStream(new TempFileInputStream(tempFile));
						} catch (IOException ioe1) {
							ioe1.printStackTrace();
						}
					}
					if ("Data".equals(line) || "EndMessage".equals(line)) {
						if (message.getName().equals("NodeHello")) {
							nodeHello = message;
							synchronized (Connection.this) {
								Connection.this.notify();
							}
						} else {
							fireMessageReceived(message);
						}
						message = null;
						continue;
					}
					int equalsPosition = line.indexOf('=');
					if (equalsPosition > -1) {
						String key = line.substring(0, equalsPosition).trim();
						String value = line.substring(equalsPosition + 1).trim();
						if (key.equals("Identifier")) {
							message.setIdentifier(value);
						} else {
							message.put(key, value);
						}
						continue;
					}
					/* skip lines consisting of whitespace only */
					if (line.trim().length() == 0) {
						continue;
					}
					/* if we got here, some error occured! */
					throw new IOException("Unexpected line: " + line);
				}
			} catch (IOException ioe1) {
				// ioe1.printStackTrace();
			} finally {
				if (nodeReader != null) {
					try {
						nodeReader.close();
					} catch (IOException ioe1) {
					}
				}
				if (nodeInputStream != null) {
					try {
						nodeInputStream.close();
					} catch (IOException ioe1) {
					}
				}
			}
			Connection.this.disconnect();
		}

	}

}
