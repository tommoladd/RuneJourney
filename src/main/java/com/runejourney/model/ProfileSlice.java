package com.runejourney.model;

import java.util.*;
import lombok.Data;

@Data
public class ProfileSlice
{
	private ProfileData profile;
	private Map<String, Long> clocks = new HashMap<>();
	private Set<String> deletedGoals = new HashSet<>();
}
