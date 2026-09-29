// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

import com.example.httpd.HttpdProvider;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Standalone harness for the routing layer: compiles without Minecraft (the exit watcher is only
 * touched by the mod entrypoint). Registers two fake prefixes and checks what the README promises
 * about dispatch:
 *
 * <ol>
 *   <li>{@code /one}, {@code /one/} and everything below arrive as {@code "/"} and {@code "/echo"};</li>
 *   <li>a sibling path like {@code /oneside} is <em>not</em> handed to {@code /one}. The JDK matches
 *       contexts by raw string prefix, so without an explicit guard {@code /aifinfo} answers exactly
 *       like {@code /aif/info} — an accident, not an API;</li>
 *   <li>every response carries {@code Cache-Control: no-store}, including responses a handler writes
 *       itself and never touches;</li>
 *   <li>{@code GET /} lists what is mounted, and an unknown top level path gets the same index with
 *       a 404.</li>
 * </ol>
 *
 * <p>The provider logs through the game's log4j2, so off-game the log4j-api jar has to be on the
 * classpath (Minecraft ships one). With no log4j-core around, log4j falls back to its SimpleLogger:
 * the provider's own lines are simply not printed, the results below go to stdout either way.</p>
 *
 * <pre>
 * LOG4J_API=~/.minecraft/libraries/org/apache/logging/log4j/log4j-api/2.26.0/log4j-api-2.26.0.jar
 * javac --release 25 -encoding UTF-8 -cp "$LOG4J_API" -d /tmp/httpd-verify \
 *   src/main/java/com/example/httpd/{HttpdProvider,PathHandler}.java tools/VerifyProvider.java
 * java -cp "/tmp/httpd-verify:$LOG4J_API" VerifyProvider          # self-check, non-zero on failure
 * java -cp "/tmp/httpd-verify:$LOG4J_API" VerifyProvider --serve  # keep serving, for manual curl
 * </pre>
 */
public final class VerifyProvider {
	private static final HttpClient CLIENT = HttpClient.newHttpClient();

	private static int failures;

	public static void main(String[] args) throws Exception {
		HttpdProvider.register("/one", "Test One", List.of(
				new HttpdProvider.Endpoint("GET", "/one/", "manual of one"),
				new HttpdProvider.Endpoint("GET/POST", "/one/echo", "echoes the sub path")),
				(exchange, path) -> text(exchange, "one -> " + path + "\n"));

		HttpdProvider.register("/two", "Test Two", List.of(
				new HttpdProvider.Endpoint("GET", "/two/widgets", "list of widgets")),
				(exchange, path) -> text(exchange, "two -> " + path + "\n"));

		if (List.of(args).contains("--serve")) {
			System.out.println("READY - http://" + HttpdProvider.HOST + ":" + HttpdProvider.PORT);
			Thread.sleep(Long.MAX_VALUE);
		}

		// 1. the prefix itself, with and without the trailing slash, is the handler's "/"
		checkGet("GET /one arrives as \"/\"", "/one", 200, "one -> /\n");
		checkGet("GET /one/ arrives as \"/\"", "/one/", 200, "one -> /\n");

		// 2. real sub paths keep the leading slash and may nest
		checkGet("GET /one/echo arrives as \"/echo\"", "/one/echo", 200, "one -> /echo\n");
		checkGet("GET /one/inner/deep arrives as \"/inner/deep\"", "/one/inner/deep", 200,
				"one -> /inner/deep\n");

		// 3. the one the JDK's raw prefix match would get wrong: /oneside is NOT /one + "side"
		checkGet("GET /oneside is 404 (a sibling of /one is not inside it)", "/oneside", 404, null);

		// 4. no-store on every response, also the ones the handler writes itself
		check("a handler response carries Cache-Control: no-store",
				"no-store".equals(header("/one/echo", "Cache-Control")),
				"Cache-Control=" + header("/one/echo", "Cache-Control"));
		check("the index carries Cache-Control: no-store",
				"no-store".equals(header("/", "Cache-Control")),
				"Cache-Control=" + header("/", "Cache-Control"));
		check("a 404 carries Cache-Control: no-store",
				"no-store".equals(header("/oneside", "Cache-Control")),
				"Cache-Control=" + header("/oneside", "Cache-Control"));

		// 5. the index lists what is mounted; an unknown top level path 404s with the same index
		check("GET / lists both mounted prefixes",
				status("/") == 200 && body("/").contains("/one") && body("/").contains("/two"));
		check("GET /nosuchprefix is 404 with the index",
				status("/nosuchprefix") == 404 && body("/nosuchprefix").contains("/one"));

		System.out.println(failures == 0
				? "ROUTING TESTS: all passed — prefixes are matched on path boundaries, every response"
						+ " is no-store, and the index lists what is mounted"
				: "ROUTING TESTS: " + failures + " FAILED");

		HttpdProvider.stop();
		System.exit(failures == 0 ? 0 : 1);
	}

	/** What a mod's handler does: write its own response, set only the content type. */
	private static void text(HttpExchange exchange, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
		exchange.sendResponseHeaders(200, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	private static HttpResponse<String> get(String path) throws Exception {
		return CLIENT.send(HttpRequest.newBuilder(URI.create(
						"http://" + HttpdProvider.HOST + ":" + HttpdProvider.PORT + path)).GET().build(),
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private static int status(String path) throws Exception {
		return get(path).statusCode();
	}

	private static String body(String path) throws Exception {
		return get(path).body();
	}

	/** One GET, both halves checked at once; {@code expectedBody} null means "status only". */
	private static void checkGet(String what, String path, int expectedStatus, String expectedBody)
			throws Exception {
		HttpResponse<String> response = get(path);
		boolean ok = response.statusCode() == expectedStatus
				&& (expectedBody == null || response.body().equals(expectedBody));
		check(what, ok, "got " + response.statusCode() + " " + describe(response.body()));
	}

	private static String header(String path, String name) throws Exception {
		return get(path).headers().firstValue(name).orElse(null);
	}

	private static String describe(String body) {
		String single = body.lines().findFirst().orElse("");
		return single.length() > 60 ? single.substring(0, 60) + "..." : single;
	}

	private static void check(String what, boolean ok) {
		check(what, ok, "");
	}

	private static void check(String what, boolean ok, String detail) {
		if (ok) {
			System.out.println("ok   " + what);
		} else {
			failures++;
			System.out.println("FAIL " + what + (detail.isEmpty() ? "" : " -> " + detail));
		}
	}

	private VerifyProvider() {
	}
}
