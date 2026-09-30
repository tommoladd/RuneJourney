package com.runejourney.ui;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;

/**
 * Opens the reports window, reusing it if it's already open.
 */
@Singleton
public class ReportWindowManager
{
	private final Provider<ReportWindow> provider;
	private ReportWindow window;

	@Inject
	ReportWindowManager(Provider<ReportWindow> provider)
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
		ReportWindow w = window;
		window = null;
		if (w != null)
		{
			SwingUtilities.invokeLater(w::dispose);
		}
	}
}
