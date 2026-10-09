package com.runejourney.model;

import lombok.*;

@Data
@NoArgsConstructor
public class JourneyEvent
{
	private String id;
	private long time;
	private EventType type;
	private String title;
	private String detail;
	private String skill;
	private String screenshot;
	private boolean highlight;
	private long value;
	private String note;
	private boolean away;
	private boolean memory;

	public JourneyEvent(long time, EventType type, String title, String detail, String skill, String screenshot,
		boolean highlight, long value)
	{
		this.time = time;
		this.type = type;
		this.title = title;
		this.detail = detail;
		this.skill = skill;
		this.screenshot = screenshot;
		this.highlight = highlight;
		this.value = value;
	}

	public JourneyEvent copy()
	{
		JourneyEvent c = new JourneyEvent(time, type, title, detail, skill, screenshot, highlight, value);
		c.setId(id);
		c.setNote(note);
		c.setAway(away);
		c.setMemory(memory);
		return c;
	}
}
