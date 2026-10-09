package com.runejourney.ui;

import com.runejourney.service.OverlayData;
import java.awt.*;
import javax.inject.*;
import net.runelite.client.ui.overlay.*;
import net.runelite.client.ui.overlay.components.*;

@Singleton
public class RuneJourneyOverlay extends OverlayPanel
{
	private static final Color GOLD = new Color(0xE0B040);
	private static final Color MUTED = new Color(0xB0B0B0);
	private static final Color BAR = new Color(0x3C7A3C);
	private static final Color BAR_BACKGROUND = new Color(0x2A2A2A);

	private volatile OverlayData data;

	@Inject
	RuneJourneyOverlay()
	{
		setPosition(OverlayPosition.TOP_LEFT);
		panelComponent.setPreferredSize(new Dimension(180, 0));
	}

	public void setData(OverlayData data)
	{
		this.data = data;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		OverlayData d = data;
		if (d == null || d.isEmpty())
		{
			return null;
		}

		if (d.getGoalName() != null)
		{
			panelComponent.getChildren().add(TitleComponent.builder().text(d.getGoalName()).color(GOLD).build());
			if (d.getGoalPercent() >= 0)
			{
				panelComponent.getChildren().add(bar(d.getGoalPercent()));
			}
			if (d.getGoalDetail() != null)
			{
				panelComponent.getChildren().add(LineComponent.builder().left(d.getGoalDetail()).leftColor(MUTED).build());
			}
		}

		if (!d.getWeek().isEmpty())
		{
			panelComponent.getChildren().add(LineComponent.builder().left("This week").leftColor(GOLD).build());
			for (OverlayData.Row row : d.getWeek())
			{
				panelComponent.getChildren().add(LineComponent.builder()
					.left(row.getLabel())
					.right(row.getValue())
					.rightColor(row.getProgress() >= 1 ? Color.GREEN : Color.WHITE)
					.build());
			}
		}

		line("Today", d.getToday());
		line("Session", d.getSession());
		line("Streak", d.getStreak());
		line("Net worth", d.getNetWorth());
		return super.render(graphics);
	}

	private void line(String label, String value)
	{
		if (value == null)
		{
			return;
		}
		panelComponent.getChildren().add(LineComponent.builder().left(label).leftColor(GOLD).build());
		panelComponent.getChildren().add(LineComponent.builder().left(value).build());
	}

	private static ProgressBarComponent bar(double fraction)
	{
		ProgressBarComponent bar = new ProgressBarComponent();
		bar.setMaximum(1000);
		bar.setValue(Math.round(fraction * 1000));
		bar.setForegroundColor(BAR);
		bar.setBackgroundColor(BAR_BACKGROUND);
		bar.setLabelDisplayMode(ProgressBarComponent.LabelDisplayMode.PERCENTAGE);
		return bar;
	}
}
