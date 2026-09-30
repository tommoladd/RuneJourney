package com.runejourney.ui;

import javax.swing.JPanel;

abstract class RefreshableTab extends JPanel
{
	/**
	 * Rebuild from the latest data. {@code force} is false for periodic updates while playing,
	 * which tabs may skip when nothing they show has changed.
	 */
	abstract void refresh(boolean force);

	void rebuild()
	{
		revalidate();
		repaint();
	}
}
