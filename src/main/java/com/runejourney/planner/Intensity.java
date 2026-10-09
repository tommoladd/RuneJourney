package com.runejourney.planner;

import lombok.*;

@Getter
@RequiredArgsConstructor
public enum Intensity
{
	EFFICIENT("Efficient"),
	BALANCED("Balanced"),
	RELAXED("Relaxed / AFK");

	private final String label;

	@Override
	public String toString()
	{
		return label;
	}
}
