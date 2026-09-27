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
		if (!HttpdProvider.start()) {
			return;
		}

		// HttpServer's "HTTP-Dispatcher" thread is not a daemon, and other mods leave non-daemon
		// threads behind as well, so without an explicit exit Minecraft's post-main watchdog would
		// write a crash report 15 s after a normal quit. The watcher stops the server and ends the
		// JVM once the render thread is gone.
		ClientExitWatcher.onClientExit(HttpdProvider::stop);
		// Still tear down on a real JVM shutdown (crash, SIGTERM, System.exit, ...).
		Runtime.getRuntime().addShutdownHook(new Thread(HttpdProvider::stop, "httpd-shutdown"));
	}
}
