package com.runejourney;

import com.runejourney.service.*;
import java.util.*;
import javax.inject.*;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.gameval.DBTableID;
import net.runelite.client.util.Text;

@Slf4j
@Singleton
class AchievementsReader
{
	private static final int[] ENUM_CA_TIERS = {3981, 3982, 3983, 3984, 3985, 3986};
	private static final int PARAM_CA_ID = 1306;
	private static final int PARAM_CA_NAME = 1308;
	private static final int PARAM_CA_DESCRIPTION = 1309;
	private static final int PARAM_CA_TYPE = 1311;
	private static final int PARAM_CA_MONSTER = 1312;
	private static final int MAX_TASK_ID = RuneJourneyPlugin.CA_TASK_VARPS.length * 32 - 1;
	private static final int ENUM_CA_TYPES = 3969;
	private static final int ENUM_CA_MONSTERS = 3971;
	private static final int QUEST_TYPE_MINIQUEST = 1;

	private static final int REFRESH_TICKS = 100;

	@Inject
	private Client client;

	@Inject
	private JourneyService service;

	@Inject
	private RuneJourneyConfig config;

	private List<PublicAchievements.CombatTask> tasks;
	private Map<Quest, QuestInfo> questInfo;
	private boolean due = true;
	private int ticksSinceRead;

	@Value
	private static class QuestInfo
	{
		String sortName;
		int points;
		boolean members;
		boolean miniquest;
		Quest parent;
	}

	void refresh()
	{
		due = true;
	}

	void onGameTick()
	{
		if (client.getGameState() != GameState.LOGGED_IN || !service.isBaselineSet() || !config.cloudSync())
		{
			return;
		}
		if (!due && ++ticksSinceRead < REFRESH_TICKS)
		{
			return;
		}
		due = false;
		ticksSinceRead = 0;
		try
		{
			service.onAchievements(quests(), combatTasks());
		}
		catch (RuntimeException e)
		{
			log.debug("Couldn't read quests and combat tasks", e);
		}
	}

	private List<PublicAchievements.Quest> quests()
	{
		if (questInfo == null)
		{
			questInfo = readQuestInfo();
		}
		List<Quest> order = new ArrayList<>(questInfo.keySet());
		order.sort(Comparator.<Quest, String>comparing(q -> sortName(top(q)))
			.thenComparing(q -> questInfo.get(q).getParent() != null)
			.thenComparingInt(Quest::getId));

		List<PublicAchievements.Quest> out = new ArrayList<>();
		for (Quest q : order)
		{
			QuestInfo info = questInfo.get(q);
			out.add(new PublicAchievements.Quest(text(q.getName(), 100), state(q), Math.max(0, Math.min(100, info.getPoints())),
				info.isMembers(), info.isMiniquest(), info.getParent() == null ? null : text(info.getParent().getName(), 100)));
		}
		return out;
	}

	private Map<Quest, QuestInfo> readQuestInfo()
	{
		Map<Integer, Quest> byRow = new HashMap<>();
		for (Quest q : Quest.values())
		{
			byRow.put(q.getId(), q);
		}
		Map<Quest, QuestInfo> out = new EnumMap<>(Quest.class);
		for (Quest q : Quest.values())
		{
			int row = q.getId();
			try
			{
				Object sortName = field(row, DBTableID.Quest.COL_SORTNAME);
				Object parent = field(row, DBTableID.Quest.COL_PARENT_QUEST);
				out.put(q, new QuestInfo(
					sortName instanceof String ? (String) sortName : q.getName(),
					number(field(row, DBTableID.Quest.COL_QUESTPOINTS)),
					number(field(row, DBTableID.Quest.COL_MEMBERS)) != 0,
					number(field(row, DBTableID.Quest.COL_TYPE)) == QUEST_TYPE_MINIQUEST,
					parent instanceof Integer ? byRow.get(parent) : null));
			}
			catch (RuntimeException e)
			{
				log.debug("Couldn't read quest {}", q, e);
				out.put(q, new QuestInfo(q.getName(), 0, false, false, null));
			}
		}
		return out;
	}

	private Quest top(Quest q)
	{
		Quest parent = questInfo.get(q).getParent();
		return parent != null && questInfo.containsKey(parent) ? parent : q;
	}

	private String sortName(Quest q)
	{
		return questInfo.get(q).getSortName();
	}

	private String state(Quest q)
	{
		switch (q.getState(client))
		{
			case FINISHED:
				return PublicAchievements.Quest.FINISHED;
			case IN_PROGRESS:
				return PublicAchievements.Quest.IN_PROGRESS;
			default:
				return PublicAchievements.Quest.NOT_STARTED;
		}
	}

	private Object field(int row, int column)
	{
		Object[] values = client.getDBTableField(row, column, 0);
		return values == null || values.length == 0 ? null : values[0];
	}

	private static int number(Object value)
	{
		if (value instanceof Integer)
		{
			return (Integer) value;
		}
		return Boolean.TRUE.equals(value) ? 1 : 0;
	}

	private List<PublicAchievements.CombatTask> combatTasks()
	{
		if (tasks == null)
		{
			tasks = readTasks();
		}
		int[] completed = new int[RuneJourneyPlugin.CA_TASK_VARPS.length];
		for (int i = 0; i < completed.length; i++)
		{
			completed[i] = client.getVarpValue(RuneJourneyPlugin.CA_TASK_VARPS[i]);
		}
		List<PublicAchievements.CombatTask> out = new ArrayList<>(tasks.size());
		for (PublicAchievements.CombatTask t : tasks)
		{
			int varp = t.getId() / 32;
			boolean done = varp < completed.length && (completed[varp] & (1 << (t.getId() % 32))) != 0;
			out.add(new PublicAchievements.CombatTask(t.getId(), t.getName(), t.getDescription(), t.getTier(), t.getType(), t.getMonster(), done));
		}
		return out;
	}

	private List<PublicAchievements.CombatTask> readTasks()
	{
		EnumComposition types = client.getEnum(ENUM_CA_TYPES);
		EnumComposition monsters = client.getEnum(ENUM_CA_MONSTERS);
		List<PublicAchievements.CombatTask> out = new ArrayList<>();
		for (int t = 0; t < ENUM_CA_TIERS.length; t++)
		{
			for (int structId : client.getEnum(ENUM_CA_TIERS[t]).getIntVals())
			{
				StructComposition s = client.getStructComposition(structId);
				int id = s.getIntValue(PARAM_CA_ID);
				String name = text(s.getStringValue(PARAM_CA_NAME), 100);
				if (id < 0 || id > MAX_TASK_ID || name.isEmpty())
				{
					continue;
				}
				String type = text(types.getStringValue(s.getIntValue(PARAM_CA_TYPE)), 40);
				String monster = text(monsters.getStringValue(s.getIntValue(PARAM_CA_MONSTER)), 100);
				out.add(new PublicAchievements.CombatTask(
					id,
					name,
					text(s.getStringValue(PARAM_CA_DESCRIPTION), 300),
					t + 1,
					type.isEmpty() ? "Other" : type,
					monster.isEmpty() ? "None" : monster,
					false));
			}
		}
		return out;
	}

	static String text(String value, int max)
	{
		if (value == null)
		{
			return "";
		}
		String clean = Text.removeTags(value.replace("<br>", " "))
			.replaceAll("[\\p{Cntrl}<>]", " ")
			.replaceAll("\\s+", " ")
			.trim();
		return clean.length() > max ? clean.substring(0, max).trim() : clean;
	}
}
