// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

package com.example.httpd.mixin;

import com.example.httpd.WindowTitle;
import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Rewrites the title on its way into GLFW.
 *
 * <p>{@code Window#setTitle} is the narrowest hook that catches <em>every</em> title change - the
 * one Minecraft makes from {@code Minecraft#updateTitle()} when a world is joined or left, and any
 * other mod that sets its own title - without touching the native window handle itself. The mixin
 * is bytecode level and therefore as platform neutral as the GLFW call it decorates.</p>
 */
@Mixin(Window.class)
public class WindowTitleMixin {
	@ModifyVariable(method = "setTitle(Ljava/lang/String;)V", at = @At("HEAD"), argsOnly = true)
	private String httpdprovider$appendPort(String title) {
		return WindowTitle.decorate(title);
	}
}
