package com.runejourney.ui;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.function.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

final class FormDialog<T>
{
	private static final int MIN_WIDTH = 400;

	private final JDialog dialog;
	private final JScrollPane scroll;
	private final Supplier<T> build;
	private T result;

	private FormDialog(Component parent, String title, JComponent form, Supplier<T> build)
	{
		this.build = build;
		Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
		dialog = new JDialog(owner, title, Dialog.ModalityType.APPLICATION_MODAL);
		dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
		dialog.setResizable(true);

		form.setBorder(new EmptyBorder(12, 12, 8, 12));
		scroll = new JScrollPane(form, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.getVerticalScrollBar().setUnitIncrement(16);

		JButton ok = new JButton("OK");
		ok.addActionListener(e -> submit());
		JButton cancel = new JButton("Cancel");
		cancel.addActionListener(e -> dialog.dispose());
		JPanel buttons = new JPanel(new GridLayout(1, 2, 8, 0));
		buttons.add(ok);
		buttons.add(cancel);
		JPanel south = new JPanel(new BorderLayout());
		south.setBorder(new EmptyBorder(4, 12, 12, 12));
		south.add(buttons, BorderLayout.EAST);

		JPanel content = new JPanel(new BorderLayout());
		content.add(scroll, BorderLayout.CENTER);
		content.add(south, BorderLayout.SOUTH);
		dialog.setContentPane(content);
		dialog.getRootPane().setDefaultButton(ok);
		dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(),
			KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
	}

	static <T> T show(Component parent, String title, JComponent form, Supplier<T> build, Consumer<Runnable> refitHook)
	{
		FormDialog<T> d = new FormDialog<>(parent, title, form, build);
		refitHook.accept(d::refit);
		d.refit();
		d.dialog.setLocationRelativeTo(parent == null ? null : SwingUtilities.getWindowAncestor(parent));
		d.dialog.setVisible(true);
		return d.result;
	}

	private void submit()
	{
		try
		{
			result = build.get();
			dialog.dispose();
		}
		catch (IllegalArgumentException e)
		{
			JOptionPane.showMessageDialog(dialog, e.getMessage(), "RuneJourney", JOptionPane.WARNING_MESSAGE);
		}
	}

	private void refit()
	{
		scroll.getViewport().getView().revalidate();
		dialog.pack();

		GraphicsConfiguration gc = dialog.getGraphicsConfiguration();
		Rectangle screen = gc != null ? gc.getBounds() : new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
		Insets insets = gc != null ? Toolkit.getDefaultToolkit().getScreenInsets(gc) : new Insets(0, 0, 0, 0);
		int maxHeight = (int) ((screen.height - insets.top - insets.bottom) * 0.9);

		Dimension size = dialog.getSize();
		int width = Math.max(MIN_WIDTH, size.width + (size.height > maxHeight ? scroll.getVerticalScrollBar().getPreferredSize().width : 0));
		int height = Math.min(size.height, maxHeight);
		dialog.setSize(width, height);

		Rectangle b = dialog.getBounds();
		int bottom = screen.y + screen.height - insets.bottom;
		if (b.y + b.height > bottom)
		{
			dialog.setLocation(b.x, Math.max(screen.y + insets.top, bottom - b.height));
		}
		dialog.validate();
	}
}
