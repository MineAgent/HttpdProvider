// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

package com.example.httpd;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shared HTTP server bound to {@code 127.0.0.1:3420}.
 *
 * <p>Client mods register a path prefix ({@code /ctl} for mcctl, {@code /aif} for
 * AdvancedInfoFetcher, ...) and serve everything below it; {@code GET /} lists what is mounted
 * right now. The provider also owns the clean shutdown: when the client thread exits it stops the
 * server and ends the JVM, so Minecraft's post-main watchdog never writes a crash report.</p>
 *
 * <p>Registration is order independent: the server is started lazily by {@link #register} as well
 * as by the mod's own entrypoint, so it does not matter which mod's entrypoint runs first.</p>
 *
 * <p>Requests are matched on path boundaries: {@code /aif} owns {@code /aif} and everything below
 * it, but not {@code /aifinfo}.</p>
 */
public final class HttpdProvider {
	public static final String HOST = "127.0.0.1";
	public static final int PORT = 3420;

	private static final Logger LOG = LogManager.getLogger("httpd");

	private static final Map<String, Registration> REGISTRATIONS = new TreeMap<>();

	private static HttpServer server;
	private static ExecutorService pool;
	private static boolean failed;

	private HttpdProvider() {
	}

	/**
	 * One line of the {@code GET /} index.
	 *
	 * @param method      HTTP method shown in the index, e.g. {@code GET}, {@code POST}, {@code GET/POST}
	 * @param path        full path shown in the index, e.g. {@code /ctl/prtsc}
	 * @param description one short line describing it
	 */
	public record Endpoint(String method, String path, String description) {
	}

	private record Registration(String prefix, String name, List<Endpoint> endpoints, PathHandler handler) {
	}

	/**
	 * Registers everything below {@code prefix} (for example {@code /ctl}). Starting the server on
	 * first use keeps the mods independent of entrypoint order.
	 *
	 * @param prefix    path prefix, with or without a trailing slash
	 * @param name      human readable owner shown in the {@code GET /} index
	 * @param endpoints the owner's endpoints, for the index
	 * @param handler   serves every request below {@code prefix}
	 */
	public static synchronized void register(String prefix, String name, List<Endpoint> endpoints,
			PathHandler handler) {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(handler, "handler");
		String normalized = normalize(prefix);

		if (!start()) {
			return;
		}

		Registration registration = new Registration(normalized, name, List.copyOf(endpoints), handler);
		REGISTRATIONS.put(normalized, registration);
		server.createContext(normalized, exchange -> dispatch(registration, exchange));
		LOG.info("registered " + normalized + " (" + name + ")");
	}

	/**
	 * Starts the server if it is not running yet (idempotent). Called by the mod entrypoint and by
	 * {@link #register}.
	 *
	 * @return true when the server is listening; false when the port could not be bound (logged)
	 */
	public static synchronized boolean start() {
		if (server != null) {
			return true;
		}
		if (failed) {
			return false;
		}
		try {
			server = HttpServer.create(new InetSocketAddress(HOST, PORT), 16);
			server.createContext("/", HttpdProvider::handleIndex);
			pool = Executors.newFixedThreadPool(2, runnable -> {
				Thread thread = new Thread(runnable, "httpd-http");
				thread.setDaemon(true);
				return thread;
			});
			server.setExecutor(pool);
			server.start();
			LOG.info("MGHttpdProvider listening on http://" + HOST + ":" + PORT);
			return true;
		} catch (IOException e) {
			server = null;
			failed = true;
			LOG.error("MGHttpdProvider could not bind to " + HOST + ":" + PORT
					+ " - is another instance already running?", e);
			return false;
		}
	}

	/**
	 * @return true while the server is listening on {@link #HOST}:{@link #PORT}. False before
	 *         {@link #start()}, after a failed bind, and after {@link #stop()}. See
	 *         {@code WindowTitle}, which only appends the port to the window title while this holds.
	 */
	public static synchronized boolean isRunning() {
		return server != null;
	}

	/** Stops the server and its worker pool (idempotent). */
	public static synchronized void stop() {
		if (server != null) {
			server.stop(0);
			server = null;
		}
		if (pool != null) {
			pool.shutdownNow();
			pool = null;
		}
	}

	private static String normalize(String prefix) {
		String normalized = prefix == null ? "" : prefix.trim();
		if (normalized.isEmpty()) {
			throw new IllegalArgumentException("prefix must not be empty");
		}
		if (!normalized.startsWith("/")) {
			normalized = "/" + normalized;
		}
		while (normalized.length() > 1 && normalized.endsWith("/")) {
			normalized = normalized.substring(0, normalized.length() - 1);
		}
		if ("/".equals(normalized)) {
			throw new IllegalArgumentException("prefix must not be /");
		}
		return normalized;
	}

	/**
	 * Strips the prefix and hands the request to the mod that registered it.
	 *
	 * <p>{@code HttpServer} picks a context by raw string prefix, so without the boundary check a
	 * request to {@code /aifinfo} would land in the {@code /aif} handler and be sliced into
	 * {@code /info} — answering another mod's endpoint by accident. Only the prefix itself and paths
	 * below it belong to a mod; anything else is answered here instead of being handed on.</p>
	 */
	private static void dispatch(Registration registration, HttpExchange exchange) {
		try {
			String requestPath = exchange.getRequestURI().getPath();

			// Promised for every response the server sends, including the ones a mod writes itself:
			// handlers never go through respond(), so this has to be set before they run.
			exchange.getResponseHeaders().set("Cache-Control", "no-store");

			if (!isBelowPrefix(requestPath, registration.prefix())) {
				respond(exchange, 404, "text/plain; charset=utf-8",
						"no endpoint at " + requestPath + "\n\n" + index());
				return;
			}

			String path = requestPath.equals(registration.prefix())
					? "/"
					: requestPath.substring(registration.prefix().length());
			registration.handler().handle(exchange, path);
		} catch (Exception e) {
			LOG.warn("endpoint " + registration.prefix() + " failed", e);
			try {
				respond(exchange, 500, "text/plain; charset=utf-8", "internal error: " + e + "\n");
			} catch (IOException ignored) {
				// the handler already started the response
			}
		} finally {
			exchange.close();
		}
	}

	/**
	 * @return true when {@code requestPath} is the prefix itself or a path below it, i.e. when the
	 *         prefix is followed by {@code /} or by nothing. {@code /aifinfo} is not below
	 *         {@code /aif}; {@code /aif/info} is.
	 */
	private static boolean isBelowPrefix(String requestPath, String prefix) {
		return requestPath.equals(prefix) || requestPath.startsWith(prefix + "/");
	}

	/** {@code GET /}: the index of everything that is mounted right now. */
	private static void handleIndex(HttpExchange exchange) throws IOException {
		String method = exchange.getRequestMethod();
		if (!"GET".equals(method) && !"HEAD".equals(method)) {
			exchange.getResponseHeaders().set("Allow", "GET, HEAD");
			respond(exchange, 405, "text/plain; charset=utf-8", "method not allowed: " + method + "\n");
			return;
		}

		String path = exchange.getRequestURI().getPath();
		if (!path.isEmpty() && !"/".equals(path)) {
			respond(exchange, 404, "text/plain; charset=utf-8",
					"no endpoint at " + path + "\n\n" + index());
			return;
		}

		respond(exchange, 200, "text/plain; charset=utf-8", index());
	}

	private static synchronized String index() {
		StringBuilder out = new StringBuilder();
		out.append("MGHttpdProvider — Minecraft 客户端 HTTP 服务 (Fabric, Minecraft 26.2)\n")
				.append("监听地址: http://").append(HOST).append(':').append(PORT).append("\n\n")
				.append("  GET  /                         ").append("本说明 (当前可用的 endpoint 列表)\n");

		if (REGISTRATIONS.isEmpty()) {
			out.append("\n还没有模组注册 endpoint: 装上 mcctl 或 AdvancedInfoFetcher 后这里会列出来。\n");
			return out.toString();
		}

		out.append("\n当前可用的 endpoint\n");
		for (Registration registration : REGISTRATIONS.values()) {
			out.append("\n  ").append(registration.prefix()).append(" — ")
					.append(registration.name()).append('\n');
			for (Endpoint endpoint : registration.endpoints()) {
				out.append("    ").append(pad(endpoint.method(), 9)).append(' ')
						.append(pad(endpoint.path(), 24))
						.append(endpoint.description()).append('\n');
			}
		}

		out.append("\n各模组的使用说明在它的前缀根路径下:");
		for (String prefix : REGISTRATIONS.keySet()) {
			out.append(" GET ").append(prefix).append('/');
		}
		out.append('\n');
		return out.toString();
	}

	private static String pad(String value, int width) {
		String text = value == null ? "" : value;
		if (text.length() >= width) {
			return text;
		}
		StringBuilder padded = new StringBuilder(width).append(text);
		while (padded.length() < width) {
			padded.append(' ');
		}
		return padded.toString();
	}

	private static void respond(HttpExchange exchange, int status, String contentType, String body)
			throws IOException {
		respond(exchange, status, contentType, body.getBytes(StandardCharsets.UTF_8));
	}

	private static void respond(HttpExchange exchange, int status, String contentType, byte[] body)
			throws IOException {
		exchange.getResponseHeaders().set("Content-Type", contentType);
		exchange.getResponseHeaders().set("Cache-Control", "no-store");
		if ("HEAD".equals(exchange.getRequestMethod()) || body.length == 0) {
			exchange.sendResponseHeaders(status, -1);
			return;
		}
		exchange.sendResponseHeaders(status, body.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(body);
		}
	}
}
