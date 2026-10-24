// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

package com.example.httpd;

import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Tears this mod's own server down as soon as the Minecraft client has exited. It never ends the
 * JVM.
 *
 * <p>Why a teardown is needed at all: {@code com.sun.net.httpserver} owns a non-daemon
 * {@code HTTP-Dispatcher} thread, and the JVM only exits once every non-daemon thread has finished.
 * Left alone, that thread keeps the process alive after the player quit, and 15 seconds later
 * Minecraft's post-main watchdog writes a {@code Client shutdown from post-main} crash report and
 * calls {@code System.exit(-8)}. Stopping the server removes the thread, so a normal quit stays a
 * normal quit.</p>
 *
 * <p>What this class deliberately does <em>not</em> do: it does not call {@code System.exit}. Other
 * mods leave non-daemon threads behind as well (Baritone keeps a worker pool), and the watchdog's
 * report about those is a true positive - it carries a full thread dump naming every live thread -
 * so it is left in place instead of being suppressed. This class only makes sure that nothing of
 * <em>this</em> mod is among the reasons the JVM cannot exit, and logs it when that fails.</p>
 *
 * <p>The entrypoint also installs a shutdown hook that runs the same teardown, so a real JVM
 * shutdown (crash, SIGTERM, {@code System.exit} somewhere else) releases the port as well.</p>
 */
public final class ClientExitWatcher {
	private static final Logger LOG = LogManager.getLogger("httpd");

	/** How often to look for a running client while the game is still starting up. */
	private static final long POLL_MS = 100L;

	/**
	 * How long to wait for this mod's own threads to disappear after the teardown. Small on purpose:
	 * a teardown that needs longer than this is broken and should be reported, not waited out.
	 */
	private static final long OWN_THREADS_TIMEOUT_MS = 2000L;

	private ClientExitWatcher() {
	}

	/**
	 * Starts a daemon watcher: after the client thread has stopped, {@code onExit} runs once on the
	 * watcher thread (never on the render thread, which is already gone by then).
	 *
	 * <p>Installing this must not depend on anything else having succeeded (binding the HTTP port,
	 * for instance): the teardown is called either way and has to be idempotent, because this mod is
	 * not the only possible source of non-daemon threads.</p>
	 */
	public static void onClientExit(Runnable onExit) {
		Thread watcher = new Thread(() -> {
			try {
				Thread client = awaitClientThread();
				LOG.debug("watching client thread " + client.getName() + " for exit");
				client.join();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}

			tearDown(onExit);
		}, "httpd-client-exit");

		// A daemon thread: it must never be the reason the JVM stays alive itself.
		watcher.setDaemon(true);
		watcher.start();
	}

	/** Runs on the watcher thread once the client thread has ended. */
	private static void tearDown(Runnable onExit) {
		LOG.info("client exited, stopping the HTTP server");
		try {
			onExit.run();
		} catch (Throwable t) {
			LOG.error("teardown after client exit failed - this mod's own threads may keep the JVM"
					+ " alive", t);
		}

		// Only our own threads are checked, and only to notice when the teardown above did not do its
		// job. Foreign threads are neither reported nor worked around: keeping the JVM alive is their
		// mod's bug, and Minecraft's post-main watchdog reports it with a thread dump.
		if (ProviderThreads.awaitOwnThreadsGone(OWN_THREADS_TIMEOUT_MS)) {
			LOG.debug("this mod's own threads are gone; the JVM can exit once every other non-daemon"
					+ " thread has finished");
		} else {
			LOG.error("this mod's own non-daemon thread(s) are still alive: "
					+ ProviderThreads.describe(ProviderThreads.ownThreads())
					+ " - the JVM cannot exit until they do");
		}
	}

	/**
	 * @return the render thread, i.e. the thread running {@code Minecraft#run()}. The client is
	 *         resolved lazily because this runs before/while {@code Minecraft} is constructed.
	 */
	private static Thread awaitClientThread() throws InterruptedException {
		while (true) {
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft != null) {
				Thread client = minecraft.getRunningThread();
				if (client != null) {
					return client;
				}
			}
			Thread.sleep(POLL_MS);
		}
	}
}
