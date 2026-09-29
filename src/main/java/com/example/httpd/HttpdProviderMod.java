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
		// HttpServer's "HTTP-Dispatcher" thread is not a daemon, and other mods leave non-daemon
		// threads behind as well, so without an explicit exit Minecraft's post-main watchdog would
		// write a crash report 15 s after a normal quit. The watcher stops the server and ends the
		// JVM once the render thread is gone.
		//
		// It is installed *before* the server is started, and whether or not the server can start at
		// all: the realistic case where binding fails is a second game instance, and the watchdog
		// fires because of *other* mods' threads (Baritone keeps a worker pool) even when this mod's
		// own server never came up. HttpdProvider::stop is idempotent, so the teardown is harmless
		// then.
		ClientExitWatcher.onClientExit(HttpdProvider::stop);
		// Still tear down on a real JVM shutdown (crash, SIGTERM, System.exit, ...).
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
