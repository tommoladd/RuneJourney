package com.runejourney.model;

import java.util.*;
import lombok.Data;

@Data
public class DaySync
{
	private DaySlice own;
	private Map<String, DaySlice> remotes = new HashMap<>();
}
