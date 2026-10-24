// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

package com.example.httpd;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Which threads of this mod are still alive?
 *
 * <p>The JVM exits once every non-daemon thread has finished, so the only thing the provider has to
 * guarantee about a quit is that <em>none of the remaining non-daemon threads is its own</em>. What
 * other mods leave behind is their business: it is neither waited for nor worked around here.</p>
 *
 * <p>Two names can be the provider's: the JDK's {@code HTTP-Dispatcher} for the server it started,
 * and its worker pool (daemon, so it never counts as a non-daemon leftover). The shutdown hook is
 * excluded on purpose - a hook thread only exists while the JVM is already shutting down, so it is
 * not a leftover.</p>
 *
 * <p>Deliberately free of any Minecraft type, so {@code tools/VerifyExit.java} can check it in a
 * plain JVM.</p>
 */
public final class ProviderThreads {
	/** The mod's shutdown hook: alive only during an already-running JVM shutdown. */
	private static final String SHUTDOWN_HOOK = "httpd-shutdown";

	private ProviderThreads() {
	}

	/** @return the provider's live non-daemon threads (empty once the server has been stopped) */
	public static Set<Thread> ownThreads() {
		return liveNonDaemon(ProviderThreads::isOwn);
	}

	/**
	 * Waits up to {@code timeoutMs} for {@link #ownThreads()} to become empty.
	 *
	 * @return true when none of them is left
	 */
	public static boolean awaitOwnThreadsGone(long timeoutMs) {
		long deadline = System.nanoTime() + timeoutMs * 1_000_000L;

		while (!ownThreads().isEmpty()) {
			if (System.nanoTime() >= deadline) {
				break;
			}
			try {
				Thread.sleep(20L);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}

		return ownThreads().isEmpty();
	}

	/** @return {@code "name (state), name (state)"}, for a log line */
	public static String describe(Set<Thread> threads) {
		return threads.stream()
				.map(thread -> thread.getName() + " (" + thread.getState() + ")")
				.collect(Collectors.joining(", "));
	}

	private static boolean isOwn(String threadName) {
		return !SHUTDOWN_HOOK.equals(threadName)
				&& (threadName.startsWith("httpd") || threadName.equals("HTTP-Dispatcher"));
	}

	private static Set<Thread> liveNonDaemon(Predicate<String> include) {
		Thread self = Thread.currentThread();
		Set<Thread> threads = new LinkedHashSet<>();

		for (Thread thread : allThreads()) {
			if (thread != self && thread.isAlive() && !thread.isDaemon() && include.test(thread.getName())) {
				threads.add(thread);
			}
		}

		return threads.stream()
				.sorted(Comparator.comparing(Thread::getName))
				.collect(Collectors.toCollection(LinkedHashSet::new));
	}

	/** Every live thread of this JVM; cheap enumeration, no stack dumps. */
	private static Thread[] allThreads() {
		ThreadGroup root = Thread.currentThread().getThreadGroup();
		while (root.getParent() != null) {
			root = root.getParent();
		}

		Thread[] threads = new Thread[root.activeCount() + 8];
		int count;
		while ((count = root.enumerate(threads, true)) >= threads.length) {
			threads = new Thread[count + 8];
		}

		Thread[] live = new Thread[count];
		System.arraycopy(threads, 0, live, 0, count);
		return live;
	}
}
