package com.runejourney.ui;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;

/**
 * Opens the screenshot gallery, reusing it if it's already open.
 */
@Singleton
public class ScreenshotWindowManager
{
	private final Provider<ScreenshotWindow> provider;
	private ScreenshotWindow window;

	@Inject
	ScreenshotWindowManager(Provider<ScreenshotWindow> provider)
	{
		this.provider = provider;
	}

	/**
	 * Must be called on the Swing thread.
	 */
	public void open()
	{
		if (window == null || !window.isDisplayable())
		{
			window = provider.get();
		}
		window.setVisible(true);
		window.toFront();
	}

	public void close()
	{
		ScreenshotWindow w = window;
		window = null;
		if (w != null)
		{
			SwingUtilities.invokeLater(w::dispose);
		}
	}
}
