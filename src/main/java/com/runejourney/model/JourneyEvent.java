package com.runejourney.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class JourneyEvent
{
	/**
	 * Epoch millis when the event happened.
	 */
	private long time;
	private EventType type;
	private String title;
	private String detail;
	/**
	 * Skill name for skill-related events, used to show the skill icon.
	 */
	private String skill;
	/**
	 * File name of an associated screenshot inside the profile's screenshot folder.
	 */
	private String screenshot;
	/**
	 * Highlights are shown on the Today page and in recaps.
	 */
	private boolean highlight;
	/**
	 * GP value associated with the event (drops), used to rank "best moments".
	 */
	private long value;
}
