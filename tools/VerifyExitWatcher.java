// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

import com.example.httpd.ClientExitWatcher;
import com.example.httpd.HttpdProvider;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Headless check of what {@code ClientExitWatcher} does after the client thread ends - with the real
 * watcher, a real {@code HttpdProvider} server and a stub {@code net.minecraft.client.Minecraft}
 * ({@code tools/stub/}), no game and no window.
 *
 * <p>Two modes, both of which must be judged from the outside because the interesting assertion is
 * "the JVM was <em>not</em> ended for us":</p>
 *
 * <pre>
 * java ... VerifyExitWatcher clean     # 期望：约 0.4 s 后自然退出，码 0，没有 crash report
 * java ... VerifyExitWatcher foreign   # 期望：撑到 3 s，打印 "still alive ..."，码 0（测试自己退的）
 * </pre>
 *
 * <p>In {@code clean} mode nothing but this mod's own threads can keep the JVM alive, so the run
 * ending quickly with exit code 0 proves the teardown removed them. In {@code foreign} mode an
 * immortal non-daemon thread stands in for another mod's leak (Baritone's pool): the run has to
 * survive to the 3 s mark, which proves the watcher does not call {@code System.exit} to hide it,
 * while the observer line at 1 s proves this mod's own dispatcher is gone by then.</p>
 *
 * <pre>
 * LOG4J_API=~/.minecraft/libraries/org/apache/logging/log4j/log4j-api/2.26.0/log4j-api-2.26.0.jar
 * javac --release 25 -encoding UTF-8 -cp "$LOG4J_API" -d /tmp/httpd-verify \
 *   src/main/java/com/example/httpd/{HttpdProvider,PathHandler,ProviderThreads,ClientExitWatcher}.java \
 *   tools/VerifyExitWatcher.java tools/stub/net/minecraft/client/Minecraft.java
 * java -cp "/tmp/httpd-verify:$LOG4J_API" VerifyExitWatcher clean
 * java -cp "/tmp/httpd-verify:$LOG4J_API" VerifyExitWatcher foreign
 * </pre>
 */
public final class VerifyExitWatcher {
	private VerifyExitWatcher() {
	}

	public static void main(String[] args) throws Exception {
		boolean foreign = args.length > 0 && args[0].equals("foreign");

		HttpdProvider.register("/probe", "watcher probe",
				List.of(new HttpdProvider.Endpoint("GET", "/probe/", "probe")),
				(exchange, path) -> {
					byte[] body = "probe\n".getBytes(StandardCharsets.UTF_8);
					exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
					exchange.sendResponseHeaders(200, body.length);
					try (java.io.OutputStream out = exchange.getResponseBody()) {
						out.write(body);
					}
				});

		if (!HttpdProvider.isRunning()) {
			System.out.println("SETUP FAILED: could not bind 127.0.0.1:3420 - is a client running?");
			System.exit(2);
		}
		System.out.println("MARK server started");

		// Stands in for the render thread: it returns shortly, exactly like a closed window.
		Thread render = new Thread(() -> {
			sleep(300L);
			System.out.println("MARK render thread returned");
		}, "Render thread");
		new net.minecraft.client.Minecraft(render);
		render.start();

		ClientExitWatcher.onClientExit(() -> {
			System.out.println("MARK teardown ran");
			HttpdProvider.stop();
		});

		if (foreign) {
			// Another mod's leak: non-daemon, immortal, not this mod's responsibility.
			Thread worker = new Thread(() -> sleep(Long.MAX_VALUE), "foreign-worker");
			worker.start();
			System.out.println("MARK foreign non-daemon thread started");
		}

		Thread observer = new Thread(() -> {
			sleep(1000L);
			System.out.println("MARK non-daemon threads at 1 s: " + nonDaemonNames());
			System.out.println(ownDispatcherGone()
					? "MARK this mod's own dispatcher is gone"
					: "FAIL this mod's own dispatcher is still alive");
		}, "harness-observer");
		observer.setDaemon(true);
		observer.start();

		Thread harnessWatchdog = new Thread(() -> {
			sleep(3000L);
			System.out.println("MARK still alive after 3 s - the JVM was not ended for us");
			System.exit(0);
		}, "harness-watchdog");
		harnessWatchdog.setDaemon(true);
		harnessWatchdog.start();

		System.out.println("MARK main returning");
	}

	private static boolean ownDispatcherGone() {
		return nonDaemonNames().stream().noneMatch(name -> name.startsWith("httpd")
				|| name.equals("HTTP-Dispatcher"));
	}

	private static List<String> nonDaemonNames() {
		return Thread.getAllStackTraces().keySet().stream()
				.filter(thread -> !thread.isDaemon() && thread.isAlive())
				.map(Thread::getName)
				.sorted()
				.collect(Collectors.toList());
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
