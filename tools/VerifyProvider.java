// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

import com.example.httpd.HttpdProvider;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Standalone harness for the routing layer: compiles without Minecraft (the exit watcher is only
 * touched by the mod entrypoint). Registers two fake prefixes and prints the endpoints it mounts,
 * then {@code curl} {@code /}, {@code /one/echo}, {@code /two/...} to verify dispatch and the index.
 *
 * <pre>
 * javac --release 25 -encoding UTF-8 -d /tmp/httpd-verify \
 *   src/main/java/com/example/httpd/{HttpdProvider,PathHandler}.java tools/VerifyProvider.java
 * java -cp /tmp/httpd-verify VerifyProvider
 * </pre>
 */
public final class VerifyProvider {
	public static void main(String[] args) throws Exception {
		HttpdProvider.register("/one", "Test One", List.of(
				new HttpdProvider.Endpoint("GET", "/one/", "manual of one"),
				new HttpdProvider.Endpoint("GET/POST", "/one/echo", "echoes the sub path")),
				(exchange, path) -> text(exchange, "one -> " + path + "\n"));

		HttpdProvider.register("/two", "Test Two", List.of(
				new HttpdProvider.Endpoint("GET", "/two/widgets", "list of widgets")),
				(exchange, path) -> text(exchange, "two -> " + path + "\n"));

		System.out.println("READY - http://" + HttpdProvider.HOST + ":" + HttpdProvider.PORT);
		Thread.sleep(Long.MAX_VALUE);
	}

	private static void text(HttpExchange exchange, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
		exchange.sendResponseHeaders(200, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}
}
