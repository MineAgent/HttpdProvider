// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

package com.example.httpd;

import net.minecraft.client.Minecraft;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs an action as soon as the Minecraft client has exited, then ends the JVM.
 *
 * <p>Why this is needed: when the player quits, the render thread returns from
 * {@code Minecraft#run()} and {@code net.minecraft.client.main.Main} starts its post-main watchdog
 * just before {@code main()} returns. The JVM only shuts down once every non-daemon thread has
 * finished, and {@code com.sun.net.httpserver} owns a non-daemon {@code HTTP-Dispatcher} thread.
 * Any such thread left behind makes {@code DestroyJavaVM} wait; 15 seconds later the watchdog
 * writes a bogus {@code Client shutdown from post-main} crash report and calls
 * {@code System.exit(-8)}.</p>
 *
 * <p>A JVM shutdown hook cannot help there: the JVM never begins to shut down, so the hook never
 * runs. This watcher instead listens for the client thread to die, tears the HTTP server down, and
 * then exits the JVM explicitly. Other mods can leave non-daemon threads behind as well (Baritone
 * keeps a worker pool), so simply stopping the HTTP server is not always enough.</p>
 *
 * <p>By the time the watcher fires, Minecraft has already run {@code exitWorldAndClose()} — the
 * world is saved, the window is closed — and {@code System.exit} still runs every shutdown hook
 * (Minecraft's own included), so this only skips the watchdog.</p>
 */
public final class ClientExitWatcher {
	private static final Logger LOG = Logger.getLogger("httpd");

	/** How often to look for a running client while the game is still starting up. */
	private static final long POLL_MS = 100L;

	/**
	 * Set by {@link #onClientExit}'s shutdown hook. When a shutdown is already under way (a crash
	 * calling {@code System.exit}, SIGTERM, ...) the watcher must not start its own exit: the JVM is
	 * going down anyway, and racing it could overwrite the exit code of the failure that caused it.
	 */
	private static volatile boolean shuttingDown;

	private ClientExitWatcher() {
	}

	/**
	 * Starts a daemon watcher: after the client thread has stopped, {@code onExit} runs once on the
	 * watcher thread (never on the render thread, which is already gone by then) and the JVM is
	 * then terminated.
	 *
	 * <p>Installing this must not depend on anything else having succeeded (binding the HTTP port,
	 * for instance): the post-main watchdog fires whenever <em>any</em> non-daemon thread is left
	 * behind, and this mod is not the only source of those.</p>
	 */
	public static void onClientExit(Runnable onExit) {
		// A real shutdown (crash, SIGTERM, System.exit) runs the hooks: remember that so the watcher
		// below leaves its exit code alone.
		Runtime.getRuntime().addShutdownHook(new Thread(() -> shuttingDown = true, "httpd-exit-guard"));

		Thread watcher = new Thread(() -> {
			try {
				Thread client = awaitClientThread();
				LOG.fine("watching client thread " + client.getName() + " for exit");
				client.join();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}

			LOG.info("client exited, stopping the HTTP server");
			try {
				onExit.run();
			} catch (Throwable t) {
				LOG.log(Level.WARNING, "teardown after client exit failed", t);
			}

			if (shuttingDown) {
				LOG.info("a JVM shutdown is already in progress, leaving its exit code alone");
				return;
			}

			// Nothing of the game is left to do, and waiting for other mods' non-daemon threads
			// would only let the post-main watchdog fire.
			LOG.info("exiting the JVM so the post-main shutdown watchdog cannot fire");
			System.exit(0);
		}, "httpd-client-exit");

		// A daemon thread: it must never be the reason the JVM stays alive itself.
		watcher.setDaemon(true);
		watcher.start();
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
