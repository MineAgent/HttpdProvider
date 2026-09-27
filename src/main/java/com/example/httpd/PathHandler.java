// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

package com.example.httpd;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;

/**
 * Serves every request below one registered path prefix.
 *
 * <p>Called on the HTTP worker threads, never on the game thread: implementations that need the
 * game state hop onto {@code Minecraft.execute} themselves, exactly like the mods did when they
 * owned their own server.</p>
 */
@FunctionalInterface
public interface PathHandler {
	/**
	 * @param exchange the HTTP exchange; the provider closes it after this method returns
	 * @param path     the request path with the registered prefix removed, always starting with
	 *                 {@code '/'} — a request to the prefix itself arrives as {@code "/"}
	 */
	void handle(HttpExchange exchange, String path) throws IOException;
}
