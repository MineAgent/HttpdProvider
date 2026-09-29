// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

package com.example.httpd;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.glfw.GLFW;

/**
 * Appends the HTTP port to the game window title while the provider is listening, so a client
 * started by this mod is recognizable as the one serving {@code 127.0.0.1:3420}.
 *
 * <p>There is deliberately no platform specific code: the title is written through GLFW (LWJGL),
 * which Minecraft already ships and which maps onto the native window API of Linux, Windows and
 * macOS alike - no {@code user32!SetWindowText}, no X11, no per-OS branch. The suffix is applied
 * inside {@link Window#setTitle(String)} (see {@code WindowTitleMixin}), which is the single place
 * every title change goes through, including Minecraft's own {@code updateTitle()} after joining or
 * leaving a world; {@link #refresh()} applies it once at startup so the very first title - the one
 * the window is created with - carries it as well.</p>
 *
 * <p>When the port could not be bound (a second instance), {@link HttpdProvider#isRunning()} is
 * false and the title is left completely alone.</p>
 */
public final class WindowTitle {
	private static final Logger LOG = LogManager.getLogger("httpd");

	/** Appended to whatever title the game computes, for example {@code Minecraft 26.2 - 3420}. */
	public static final String SUFFIX = " - " + HttpdProvider.PORT;

	private WindowTitle() {
	}

	/**
	 * Decorates a window title: while the HTTP server is listening, the port is appended unless the
	 * title already ends with it (so repeated updates never stack up suffixes).
	 *
	 * @param title the title Minecraft computed, may be null
	 * @return the title to put on the window
	 */
	public static String decorate(String title) {
		if (title == null || title.endsWith(SUFFIX) || !HttpdProvider.isRunning()) {
			return title;
		}
		return title + SUFFIX;
	}

	/**
	 * Re-applies the current title once, right after the server came up. The window carries a title
	 * that was computed before the mods' entrypoints ran, so without this the suffix would only show
	 * up at the first later title update (joining or leaving a world).
	 *
	 * <p>The client entrypoints run inside {@code Minecraft}'s constructor, before the window is
	 * created, so this cannot touch the title directly: {@link Minecraft#execute} queues it for the
	 * render thread, where it runs on the first tick with the window up. GLFW requires its window
	 * functions to be called from that thread anyway.</p>
	 */
	static void refresh() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null) {
			LOG.info("client not constructed yet, the port suffix is applied on the next title update");
			return;
		}
		minecraft.execute(WindowTitle::apply);
	}

	/** Runs on the render thread: re-applies the title so the port suffix shows up right away. */
	private static void apply() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null) {
			return;
		}
		Window window = minecraft.getWindow();
		if (window == null) {
			LOG.info("window not created yet, the port suffix is applied on the next title update");
			return;
		}

		try {
			minecraft.updateTitle();
			LOG.info("window title is now \"" + GLFW.glfwGetWindowTitle(window.handle()) + "\"");
		} catch (Throwable t) {
			// A cosmetic extra must never keep the game from starting.
			LOG.warn("could not append the port to the window title", t);
		}
	}
}
