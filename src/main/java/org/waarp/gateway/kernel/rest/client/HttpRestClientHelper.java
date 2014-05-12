/**
   This file is part of Waarp Project.

   Copyright 2009, Frederic Bregier, and individual contributors by the @author
   tags. See the COPYRIGHT.txt in the distribution for a full listing of
   individual contributors.

   All Waarp Project is free software: you can redistribute it and/or 
   modify it under the terms of the GNU General Public License as published 
   by the Free Software Foundation, either version 3 of the License, or
   (at your option) any later version.

   Waarp is distributed in the hope that it will be useful,
   but WITHOUT ANY WARRANTY; without even the implied warranty of
   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
   GNU General Public License for more details.

   You should have received a copy of the GNU General Public License
   along with Waarp .  If not, see <http://www.gnu.org/licenses/>.
 */
package org.waarp.gateway.kernel.rest.client;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.handler.codec.http.DefaultHttpRequest;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpMethod;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpVersion;
import org.jboss.netty.handler.codec.http.QueryStringEncoder;
import org.jboss.netty.logging.InternalLoggerFactory;
import org.waarp.common.crypto.ssl.WaarpSslUtility;
import org.waarp.common.logging.WaarpInternalLogger;
import org.waarp.common.logging.WaarpInternalLoggerFactory;
import org.waarp.common.logging.WaarpSlf4JLoggerFactory;
import org.waarp.common.utility.WaarpStringUtils;
import org.waarp.common.utility.WaarpThreadFactory;
import org.waarp.gateway.kernel.exception.HttpInvalidAuthenticationException;
import org.waarp.gateway.kernel.rest.RestArgument;

/**
 * Http Rest Client helper
 * @author "Frederic Bregier"
 *
 */
public class HttpRestClientHelper {
	private static WaarpInternalLogger logger = null;

	/**
	 * ExecutorService Server Boss
	 */
	private final ExecutorService execServerBoss = Executors
			.newCachedThreadPool(new WaarpThreadFactory("ServerBossRetrieve"));

	/**
	 * ExecutorService Server Worker
	 */
	private final ExecutorService execServerWorker = Executors
			.newCachedThreadPool(new WaarpThreadFactory("ServerWorkerRetrieve"));

	private final ChannelFactory channelClientFactory;
	
	private final ClientBootstrap clientBootstrap;
	
	private final HttpHeaders headers;

	private String baseUri = "/";
	
	/**
	 * @param baseUri base of all URI, in general simply "/" (default if null)
	 * @param nbclient max number of client connected at once
	 * @param timeout timeout used in connection
	 * @param pipelineFactory the associated client pipeline factory
	 */
	public HttpRestClientHelper(String baseUri, int nbclient, long timeout, ChannelPipelineFactory pipelineFactory) {
		if (logger == null) {
			logger = WaarpInternalLoggerFactory.getLogger(HttpRestClientHelper.class);
		}
		if (baseUri != null) {
			this.baseUri = baseUri;
		}
		channelClientFactory = new NioClientSocketChannelFactory(
				execServerBoss,
				execServerWorker,
				nbclient);
		clientBootstrap = new ClientBootstrap(channelClientFactory);
		clientBootstrap.setPipelineFactory(pipelineFactory);
		clientBootstrap.setOption("tcpNoDelay", true);
		clientBootstrap.setOption("reuseAddress", true);
		clientBootstrap.setOption("connectTimeoutMillis", timeout);
		// will ignore real request
        HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1,
                HttpMethod.GET, baseUri);
        headers = request.headers();
        headers.set(HttpHeaders.Names.ACCEPT_ENCODING,
                HttpHeaders.Values.GZIP + "," + HttpHeaders.Values.DEFLATE);
        headers.set(HttpHeaders.Names.ACCEPT_CHARSET,
                "utf-8;q=0.7,*;q=0.7");
        headers.set(HttpHeaders.Names.ACCEPT_LANGUAGE, "fr,en");
        headers.set(HttpHeaders.Names.USER_AGENT,
                "Netty Simple Http Rest Client side");
        headers.set(HttpHeaders.Names.ACCEPT,
                "text/html,text/plain,application/xhtml+xml,application/xml,application/json;q=0.9,*/*;q=0.8");
        // connection will not close but needed
        /*request.headers().set(HttpHeaders.Names.CONNECTION,
        HttpHeaders.Values.KEEP_ALIVE);*/
        // request.setHeader("Connection","keep-alive");
        // request.setHeader("Keep-Alive","300");
	}


	
	/**
	 * Create one new connection to the remote host using port
	 * @param host
	 * @param port
	 * @return the channel if connected or Null if not
	 */
	public Channel getChannel(String host, int port) {
		 // Start the connection attempt.
        ChannelFuture future = clientBootstrap.connect(new InetSocketAddress(host, port));
        // Wait until the connection attempt succeeds or fails.
        Channel channel = WaarpSslUtility.waitforChannelReady(future);
        if (channel != null) {
        	RestFuture futureChannel = new RestFuture(true);
        	channel.setAttachment(futureChannel);
        }
        return channel;
	}

	/**
	 * Send an HTTP query using the channel for target
	 * @param channel target of the query
	 * @param method HttpMethod to use
	 * @param host target of the query (shall be the same as for the channel)
	 * @param addedUri additional uri, added to baseUri (shall include also extra arguments) (might be null)
	 * @param user user to use in authenticated Rest procedure (might be null)
	 * @param pwd password to use in authenticated Rest procedure (might be null)
	 * @param uriArgs arguments for Uri if any (might be null)
	 * @param json json to send as body in the request (might be null); Useful in PUT, POST but should not in GET, DELETE, OPTIONS
	 * @return the RestFuture associated with this request
	 */
	public RestFuture sendQuery(Channel channel, HttpMethod method, String host, String addedUri, String user, String pwd, Map<String, String> uriArgs, String json) {
		// Prepare the HTTP request.
		logger.debug("Prepare request: "+method+":"+addedUri+":"+json);
		RestFuture future = ((RestFuture) channel.getAttachment());
        QueryStringEncoder encoder = null;
        if (addedUri != null) {
        	encoder = new QueryStringEncoder(baseUri+addedUri);
        } else {
        	encoder = new QueryStringEncoder(baseUri);
        }
        // add Form attribute
        if (uriArgs != null) {
        	for (Entry<String, String> elt : uriArgs.entrySet()) {
				encoder.addParam(elt.getKey(), elt.getValue());
			}
        }
        String [] result = null;
        try {
			result = RestArgument.getBaseAuthent(encoder, user, pwd);
			logger.debug("Authent encoded");
		} catch (HttpInvalidAuthenticationException e) {
			logger.error(e.getMessage(), e);
			future.setFailure(e);
            return future;
		}
        URI uri;
		try {
			uri = encoder.toUri();
		} catch (URISyntaxException e) {
            logger.error(e.getMessage());
            future.setFailure(e);
            return future;
        }
		logger.debug("Uri ready: "+uri.toASCIIString());

        HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1,
                method, uri.toASCIIString());
        // it is legal to add directly header or cookie into the request until finalize
        request.headers().add(this.headers);
        request.headers().set(HttpHeaders.Names.HOST, host);
        if (user != null) {
            request.headers().set(RestArgument.REST_ROOT_FIELD.ARG_X_AUTH_USER.field, user);
        }
        request.headers().set(RestArgument.REST_ROOT_FIELD.ARG_X_AUTH_TIMESTAMP.field, result[0]);
        request.headers().set(RestArgument.REST_ROOT_FIELD.ARG_X_AUTH_KEY.field, result[1]);
        if (json != null) {
    		logger.debug("Add body");
        	ChannelBuffer buffer = ChannelBuffers.wrappedBuffer(json.getBytes(WaarpStringUtils.UTF8));
            request.setContent(buffer);
            request.headers().set(HttpHeaders.Names.CONTENT_LENGTH, buffer.readableBytes());
        }
        // send request
		logger.debug("Send request");
		channel.write(request);
		logger.debug("Request sent");
		return future;
	}

	/**
	 * Finalize the HttpRestClientHelper
	 */
	public void closeAll() {
		clientBootstrap.releaseExternalResources();
		channelClientFactory.releaseExternalResources();
	}
	
	/**
	 * 
	 * @param args as uri (http://host:port/uri user pwd [json])
	 */
	public static void main(String[] args) {
		InternalLoggerFactory.setDefaultFactory(new WaarpSlf4JLoggerFactory(null));
        final WaarpInternalLogger logger = WaarpInternalLoggerFactory.getLogger(HttpRestClientHelper.class);
		if (args.length < 4) {
			logger.error("Need more arguments: http://host:port/uri method user pwd [json]");
		}
		String uri = args[0];
		String meth = args[1];
		String user = args[2];
		String pwd = args[3];
		String json = null;
		if (args.length > 4) {
			json = args[4].replace("'", "\"");
		}
		HttpMethod method = HttpMethod.valueOf(meth);
		int port = -1;
		String host = null;
		String path = null;
		try {
			URI realUri = new URI(uri);
			port = realUri.getPort();
			host = realUri.getHost();
			path = realUri.getPath();
		} catch (URISyntaxException e) {
			logger.error("Error", e);
			return;
		}
		HttpRestClientHelper client = new HttpRestClientHelper(path, 1, 30000, new HttpRestClientSimplePipelineFactory());
		Channel channel = client.getChannel(host, port);
		if (channel == null) {
			client.closeAll();
			logger.error("Cannot connect to "+host+" on port "+port);
			return;
		}
		RestFuture future = client.sendQuery(channel, method, host, null, user, pwd, null, json);
		try {
			future.await();
		} catch (InterruptedException e) {
			client.closeAll();
			logger.error("Interruption", e);
			return;
		}
   		WaarpSslUtility.closingSslChannel(channel);
		if (future.isSuccess()) {
			logger.warn(future.getRestArgument().prettyPrint());
		} else {
			RestArgument ra = future.getRestArgument();
			if (ra != null) {
				logger.error(ra.prettyPrint());
			} else {
				logger.error("Query in error", future.getCause());
			}
		}
		client.closeAll();
	}
}
