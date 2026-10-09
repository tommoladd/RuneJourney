package com.runejourney.ui;

import javax.inject.*;
import javax.swing.SwingUtilities;

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
