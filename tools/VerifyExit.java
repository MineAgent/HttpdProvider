// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

import com.example.httpd.HttpdProvider;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Headless check of the shutdown story — no Minecraft, no window, no client.
 *
 * <p>Why the client needs an explicit exit at all: after the render thread returns from
 * {@code Minecraft#run()}, vanilla's {@code Main} starts a post-main watchdog; if the JVM has not
 * finished 15 seconds later it writes a bogus {@code Client shutdown from post-main} crash report
 * and calls {@code System.exit(-8)}. The JVM only finishes when every <em>non-daemon</em> thread is
 * done, and {@code com.sun.net.httpserver} owns exactly such a thread ("HTTP-Dispatcher"), as do
 * other mods (Baritone keeps a worker pool). {@code ClientExitWatcher} therefore joins the render
 * thread, stops the server and calls {@code System.exit(0)} itself — and it is armed before the
 * server is started, so a client that could not bind 3420 (a second instance) is covered too.</p>
 *
 * <p>The parts of that story that do not need the game are checked here:</p>
 *
 * <ol>
 *   <li>the JDK really does leave a non-daemon HTTP-Dispatcher thread behind (the hazard is real),</li>
 *   <li>the provider's own worker pool is a daemon, so it can never be the reason the JVM stays
 *       alive,</li>
 *   <li>{@code HttpdProvider.stop()} removes the non-daemon dispatcher thread and is idempotent,</li>
 *   <li>after the teardown no non-daemon thread of the provider is left.</li>
 * </ol>
 *
 * <pre>
 * javac --release 25 -encoding UTF-8 -d /tmp/httpd-verify \
 *   src/main/java/com/example/httpd/{HttpdProvider,PathHandler}.java tools/VerifyExit.java
 * java -cp /tmp/httpd-verify VerifyExit
 * </pre>
 */
public final class VerifyExit {
	private static int failures;

	public static void main(String[] args) throws Exception {
		Map<String, Boolean> before = daemonByName();

		// 1. mount a probe prefix exactly like a client mod does (register starts the server)
		HttpdProvider.register("/probe", "VerifyExit probe",
				List.of(new HttpdProvider.Endpoint("GET", "/probe/", "probe")),
				(exchange, path) -> {
					byte[] body = "probe\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
					exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
					exchange.sendResponseHeaders(200, body.length);
					try (java.io.OutputStream out = exchange.getResponseBody()) {
						out.write(body);
					}
				});

		Thread dispatcher = awaitThread("HTTP-Dispatcher", 2000);
		check("registering a prefix started the server and the JDK left a non-daemon"
						+ " 'HTTP-Dispatcher' thread behind (the hazard is real)",
				dispatcher != null && !dispatcher.isDaemon(),
				"found=" + (dispatcher != null)
						+ (dispatcher == null ? "" : " daemon=" + dispatcher.isDaemon()));

		// 2. the provider's own pool is a daemon (the thread only exists once a request ran)
		check("serving works while probing", get("/probe/") == 200, "the probe endpoint answered");
		Thread worker = awaitThread("httpd-http", 2000);
		check("the HTTP worker pool is a daemon thread", worker != null && worker.isDaemon(),
				"threads=" + modThreads());

		// 3. the teardown removes the non-daemon thread and is idempotent
		HttpdProvider.stop();
		HttpdProvider.stop();
		check("HttpdProvider.stop() removed the non-daemon dispatcher thread",
				awaitThread("HTTP-Dispatcher", 2000) == null, "still there: " + threadNames());
		check("HttpdProvider.stop() removed the HTTP worker pool", awaitThread("httpd-http", 2000) == null,
				"threads=" + modThreads());

		// 4. the same thing a second client instance with a taken port would do: none of the
		//    provider's threads exist, yet its stop() must stay harmless
		HttpdProvider.stop();
		check("stop() on a server that never started is harmless (the port-busy case)",
				nonDaemonNames().stream().noneMatch(name -> name.startsWith("httpd")
						|| name.equals("HTTP-Dispatcher")),
				"non-daemon: " + nonDaemonNames());

		Set<String> nonDaemonNow = nonDaemonNames();
		nonDaemonNow.removeAll(before.keySet());
		check("no non-daemon thread is left behind by this mod", nonDaemonNow.isEmpty(),
				"left over: " + nonDaemonNow);
		check("the JVM would now exit on its own (no thread of ours keeps it alive)",
				nonDaemonNames().stream().noneMatch(name -> name.startsWith("httpd")
						|| name.equals("HTTP-Dispatcher")),
				"non-daemon: " + nonDaemonNames());

		System.out.println(failures == 0
				? "EXIT TESTS: all passed — the client needs System.exit(0) because of the JDK"
						+ " dispatcher (gone after the teardown) and other mods' threads (Baritone),"
						+ " and no thread of the provider is non-daemon"
				: "EXIT TESTS: " + failures + " FAILED");
		System.exit(failures == 0 ? 0 : 1);
	}

	/** @return every live thread whose name contains {@code needle} */
	private static Thread find(String needle) {
		return Thread.getAllStackTraces().keySet().stream()
				.filter(thread -> thread.getName().contains(needle))
				.findFirst().orElse(null);
	}

	/** @return the thread once it exists, waiting up to {@code timeoutMs} for it */
	private static Thread awaitThread(String needle, long timeoutMs) throws InterruptedException {
		Thread found = find(needle);

		for (long waited = 0; found == null && waited < timeoutMs; waited += 50) {
			Thread.sleep(50);
			found = find(needle);
		}

		return found;
	}

	/** One GET against the probing server; returns the status code. */
	private static int get(String path) throws IOException {
		HttpURLConnection connection = (HttpURLConnection)
				new URL("http://127.0.0.1:3420" + path).openConnection();
		connection.setRequestMethod("GET");
		int status = connection.getResponseCode();
		try (InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
			if (in != null) {
				in.readAllBytes();
			}
		}
		return status;
	}

	private static Set<String> modThreads() {
		return threadNames().stream().filter(name -> name.startsWith("httpd")
				|| name.equals("HTTP-Dispatcher")).collect(Collectors.toCollection(TreeSet::new));
	}

	private static Set<String> threadNames() {
		return Thread.getAllStackTraces().keySet().stream()
				.map(Thread::getName).collect(Collectors.toCollection(TreeSet::new));
	}

	private static Map<String, Boolean> daemonByName() {
		Map<String, Boolean> map = new TreeMap<>();
		Thread.getAllStackTraces().keySet().forEach(thread -> map.put(thread.getName(), thread.isDaemon()));
		return map;
	}

	private static Set<String> nonDaemonNames() {
		return Thread.getAllStackTraces().keySet().stream()
				.filter(thread -> !thread.isDaemon())
				.map(Thread::getName).collect(Collectors.toCollection(TreeSet::new));
	}

	private static void check(String what, boolean ok, String detail) {
		if (ok) {
			System.out.println("ok   " + what);
		} else {
			failures++;
			System.out.println("FAIL " + what + " -> " + detail);
		}
	}

	private VerifyExit() {
	}
}
