// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

package net.minecraft.client;

/**
 * Test stub for the two members {@code ClientExitWatcher} uses, so that the exit handling can be
 * exercised in a plain JVM ({@code tools/VerifyExitWatcher.java}). It is compiled from
 * {@code tools/stub/} only and never ends up in the mod jar.
 */
public class Minecraft {
	private static Minecraft instance;

	private final Thread runningThread;

	public Minecraft(Thread runningThread) {
		this.runningThread = runningThread;
		instance = this;
	}

	public static Minecraft getInstance() {
		return instance;
	}

	public Thread getRunningThread() {
		return runningThread;
	}
}
