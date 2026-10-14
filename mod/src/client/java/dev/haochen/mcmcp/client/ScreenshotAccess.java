package dev.haochen.mcmcp.client;

/** Lets mixins see whether a screenshot is being rendered. */
public final class ScreenshotAccess {
	private ScreenshotAccess() {
	}

	public static boolean capturing() {
		return ScreenshotRenderer.capturing;
	}
}
