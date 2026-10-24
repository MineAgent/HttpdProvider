// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

package com.example.httpd;

import net.fabricmc.api.ClientModInitializer;

/**
 * Client entrypoint: brings up the shared HTTP server and arms the exit watcher.
 *
 * <p>The server itself starts lazily from {@link HttpdProvider#register}, so this entrypoint may
 * run before or after the mods that register on it.</p>
 */
public class HttpdProviderMod implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// HttpServer's "HTTP-Dispatcher" thread is not a daemon, and the JVM only exits once every
		// non-daemon thread has finished. Stopping the server when the render thread is gone removes
		// that thread, so a normal quit really is a normal quit - no 15 s wait, no post-main watchdog
		// crash report about this mod.
		//
		// Nothing beyond that: the JVM is *not* ended here. Other mods leave non-daemon threads
		// behind too (Baritone keeps a worker pool), and a watchdog report about those is a true
		// positive that names them in its thread dump, so it is left in place.
		//
		// It is installed *before* the server is started, and whether or not the server can start at
		// all: the realistic case where binding fails is a second game instance, and the teardown has
		// to be harmless then. HttpdProvider::stop is idempotent, so it is.
		ClientExitWatcher.onClientExit(HttpdProvider::stop);
		// Also tear down on a real JVM shutdown (crash, SIGTERM, System.exit elsewhere, ...).
		Runtime.getRuntime().addShutdownHook(new Thread(HttpdProvider::stop, "httpd-shutdown"));

		// Start last: when the port is taken, start() logs a SEVERE line and the mods registering
		// afterwards simply stay unregistered - the game keeps running and still exits cleanly.
		if (HttpdProvider.start()) {
			// Say which port this instance is serving from the window title. Only when the server
			// really came up: a second instance must not claim 3420. The suffix is then kept on
			// every later title update by WindowTitleMixin.
			WindowTitle.refresh();
		}
	}
}
