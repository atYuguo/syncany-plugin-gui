/*
 * Syncany, www.syncany.org
 * Copyright (C) 2011-2015 Philipp C. Heckel <philipp.heckel@gmail.com> 
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.syncany.operations.gui;

import io.undertow.websockets.WebSocketExtension;
import io.undertow.websockets.client.WebSocketClient;
import io.undertow.websockets.client.WebSocketClientNegotiation;
import io.undertow.websockets.core.AbstractReceiveListener;
import io.undertow.websockets.core.BufferedTextMessage;
import io.undertow.websockets.core.WebSocketCallback;
import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.core.WebSocketVersion;
import io.undertow.websockets.core.WebSockets;
import io.undertow.server.DefaultByteBufferPool;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Map;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Collections;

import javax.net.ssl.SSLContext;

import org.apache.commons.codec.binary.Base64;
import org.syncany.config.DaemonConfigHelper;
import org.syncany.config.GuiEventBus;
import org.syncany.config.UserConfig;
import org.syncany.config.to.DaemonConfigTO;
import org.syncany.config.to.UserTO;
import org.syncany.operations.daemon.WebServer;
import org.syncany.operations.daemon.messages.ListWatchesManagementRequest;
import org.syncany.operations.daemon.messages.api.ExternalEventResponse;
import org.syncany.operations.daemon.messages.api.Message;
import org.syncany.operations.daemon.messages.api.Request;
import org.syncany.operations.daemon.messages.api.XmlMessageFactory;
import org.syncany.util.StringUtil;
import org.xnio.BufferAllocator;
import org.xnio.ByteBufferSlicePool;
import org.xnio.OptionMap;
import org.xnio.Options;
import org.xnio.Pool;
import org.xnio.Xnio;
import org.xnio.XnioWorker;
import org.xnio.ssl.JsseXnioSsl;
import org.xnio.ssl.XnioSsl;

import com.google.common.eventbus.Subscribe;

/**
 * @author Vincent Wiencek <vwiencek@gmail.com>
 * @author Philipp C. Heckel <philipp.heckel@gmail.com>
*/
public class GuiWebSocketClient {
	private static final Logger logger = Logger.getLogger(GuiWebSocketClient.class.getSimpleName());
	private final static String PROTOCOL = "wss://";
	private final static String ENDPOINT = WebServer.API_ENDPOINT_WS_XML;

	private GuiEventBus eventBus;
	private WebSocketChannel webSocketChannel;

	private Thread clientThread;
	private AtomicBoolean clientThreadRunning;
	
	private Queue<Message> failedOutgoingMessages;

	public GuiWebSocketClient() {
		this.eventBus = GuiEventBus.getInstance();
		this.eventBus.register(this);
		
		this.failedOutgoingMessages = new LinkedList<>();
		
		initClientThread();
	}
	
	private void initClientThread() {
		clientThread = new Thread(new Runnable() {
			@Override
			public void run() {
				while (clientThreadRunning.get()) {
					try {
						connectAndWait();
					}
					catch (InterruptedException e) {
						logger.log(Level.INFO, "Web socket interrupted. EXITING websocket client thread.", e);
						clientThreadRunning.set(false);
					}
					catch (Exception e) {
						logger.log(Level.WARNING, "Web socket connect failure. Waiting, then retrying ...", e);
						
						try {
							Thread.sleep(5000);
						}
						catch (InterruptedException e1) {
							logger.log(Level.INFO, "Web socket interrupted. EXITING websocket client thread.", e1);
							clientThreadRunning.set(false);
						}
					}
				}
			} 			
		}, "GuiWsClient");		
	}
	
	public void start() {
		clientThreadRunning = new AtomicBoolean(true);
		clientThread.start();
	}
	
	public void stop() {
		clientThreadRunning.set(false);
		clientThread.interrupt();
	}
	
	public void connectAndWait() throws Exception {
		logger.log(Level.INFO, "Trying to connect to websocket server ...");
		
		DaemonConfigTO daemonConfig = loadDaemonConfig();
		UserTO firstDaemonUser = loadFirstDaemonUser(daemonConfig);
		
		connect(daemonConfig, firstDaemonUser);	
		
		sendFailedOutgoingMessages();
		sendListWatchesRequest();
		
		while (clientThreadRunning.get()) {
			Thread.sleep(500);
		}
	}
	
	private void sendFailedOutgoingMessages() {
		while (failedOutgoingMessages.size() > 0) {
			postMessage(failedOutgoingMessages.poll(), false);
		}
	}

	private void sendListWatchesRequest() {
		onRequest(new ListWatchesManagementRequest());
	}

	private DaemonConfigTO loadDaemonConfig() throws Exception {
		File daemonConfigFile = new File(UserConfig.getUserConfigDir(), UserConfig.DAEMON_FILE);

		if (!daemonConfigFile.exists()) {
			throw new Exception("Daemon configuration does not exist at " + daemonConfigFile);
		}
		
		return DaemonConfigTO.load(daemonConfigFile);									
	}
	
	private UserTO loadFirstDaemonUser(DaemonConfigTO daemonConfig) throws Exception {
		UserTO firstDaemonUser = DaemonConfigHelper.getFirstDaemonUser(daemonConfig);
		
		if (firstDaemonUser == null) {
			throw new Exception("Daemon configuration does not contain any users.");
		}
		
		return firstDaemonUser;
	}

	private void connect(final DaemonConfigTO daemonConfig, final UserTO daemonUser) throws Exception {
		logger.log(Level.INFO, "Starting GUI websocket client with user " + daemonUser.getUsername() + " at " + daemonConfig.getWebServer().getBindAddress() + " ...");
		
		SSLContext sslContext = UserConfig.createUserSSLContext();
		Xnio xnio = Xnio.getInstance(this.getClass().getClassLoader());
		//Pool<ByteBuffer> buffer = new ByteBufferSlicePool(BufferAllocator.BYTE_BUFFER_ALLOCATOR, 1024, 1024);

		DefaultByteBufferPool buffer = new DefaultByteBufferPool(false, 1024, 1024, 0);

		OptionMap workerOptions = OptionMap.builder()
				.set(Options.WORKER_IO_THREADS, 2)
				.set(Options.WORKER_TASK_CORE_THREADS, 30)
				.set(Options.WORKER_TASK_MAX_THREADS, 30)
				.set(Options.SSL_PROTOCOL, sslContext.getProtocol())
				.set(Options.SSL_PROVIDER, sslContext.getProvider().getName())
				.set(Options.TCP_NODELAY, true)
				.set(Options.CORK, true)
				.getMap();

		XnioWorker worker = xnio.createWorker(workerOptions);
		XnioSsl xnioSsl = new JsseXnioSsl(xnio, OptionMap.create(Options.USE_DIRECT_BUFFERS, true), sslContext);
		URI uri = new URI(PROTOCOL + daemonConfig.getWebServer().getBindAddress() + ":" + daemonConfig.getWebServer().getBindPort() + ENDPOINT);

		WebSocketClientNegotiation clientNegotiation = new WebSocketClientNegotiation(new ArrayList<String>(), new ArrayList<WebSocketExtension>()) {
			@Override
			public void beforeRequest(Map<String, List<String>> headers) {
				String basicAuthPlainUserPass = daemonUser.getUsername() + ":" + daemonUser.getPassword();
				String basicAuthEncodedUserPass = Base64.encodeBase64String(StringUtil.toBytesUTF8(basicAuthPlainUserPass));
				
				headers.put("Authorization", Collections.singletonList("Basic " + basicAuthEncodedUserPass));
			}
		};
		
		webSocketChannel = WebSocketClient.connect(worker, xnioSsl, buffer, workerOptions, uri, WebSocketVersion.V13, clientNegotiation).get();
		
		webSocketChannel.getReceiveSetter().set(new AbstractReceiveListener() {
			@Override
			protected void onFullTextMessage(WebSocketChannel channel, BufferedTextMessage textMessage) throws IOException {
				String messageStr = textMessage.getData();
				Message message;
				
				try {
					logger.log(Level.FINEST, "GUI received message: " + messageStr);
					
					message = XmlMessageFactory.toMessage(messageStr);
					eventBus.post(message);
				}
				catch (Exception e) {
					logger.log(Level.WARNING, "Unable to parse message: " + e);
				}
			}

			@Override
			protected void onError(WebSocketChannel channel, Throwable error) {
				logger.log(Level.WARNING, "Error: " + error.getMessage());
				waitAndReconnect();
			}
		});
				
		webSocketChannel.resumeReceives();
	}

	protected void waitAndReconnect() {
		try {
			logger.log(Level.WARNING, "Web socket cannot connect. Waiting, then retrying ...");
			Thread.sleep(2000);
						
			connectAndWait();
		}		
		catch (Exception e) {
			logger.log(Level.WARNING, "Unable to reconnect to daemon", e);
		}
	}

	@Subscribe
	public void onRequest(Request request) {
		postMessage(request, true);
	}
	
	@Subscribe
	public void onEventResponse(ExternalEventResponse eventResponse) {
		postMessage(eventResponse, true);
	}
	
	private void postMessage(final Message message, final boolean retryOnFailure) {		
		// Parse message
		String messageStr = null;
		
		try {
			messageStr = XmlMessageFactory.toXml(message);						
		}
		catch (Exception e) {
			logger.log(Level.WARNING, "Unable to transform message to XML. Throwing message away!", e);
			return;
		}
		
		// Send to websocket
		if (webSocketChannel != null) {
			logger.log(Level.FINEST, "Sending WS message to daemon: " + messageStr);

			WebSockets.sendText(messageStr, webSocketChannel, new WebSocketCallback<Void>() {
				@Override
				public void onError(WebSocketChannel channel, Void context, Throwable throwable) {
					logger.log(Level.SEVERE, "WS Error", throwable);
					
					if (retryOnFailure) {
						failedOutgoingMessages.add(message);
					}
				}
	
				@Override
				public void complete(WebSocketChannel channel, Void context) {
					// Nothing.
				}
			});
		}
		else {
			logger.log(Level.WARNING, "Failed to send WS message to daemon. Not (yet) connected; " + messageStr);
			
			if (retryOnFailure) {
				failedOutgoingMessages.add(message);
			}
		}
	}
}
