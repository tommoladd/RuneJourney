package com.runejourney.ui;

import javax.inject.*;
import javax.swing.SwingUtilities;

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
