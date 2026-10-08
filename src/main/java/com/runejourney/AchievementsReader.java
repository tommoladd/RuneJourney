package com.runejourney;

import com.runejourney.service.JourneyService;
import com.runejourney.service.PublicAchievements;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.GameState;
import net.runelite.api.Quest;
import net.runelite.api.StructComposition;
import net.runelite.api.gameval.DBTableID;
import net.runelite.client.util.Text;

/**
 * Reads every quest and combat task from the game for the account's public page: each quest's
 * state, and each task with whether it's done. Read once logged in, again when quest points or a
 * combat task changes, and every minute, which catches quests being started.
 */
@Slf4j
@Singleton
class AchievementsReader
{
	// Not named by RuneLite
	/**
	 * Each tier's combat tasks, Easy to Grandmaster, as enums of task structs.
	 */
	private static final int[] ENUM_CA_TIERS = {3981, 3982, 3983, 3984, 3985, 3986};
	/**
	 * A task struct's ID (its bit in the completed-task varps), name, description, type and monster.
	 */
	private static final int PARAM_CA_ID = 1306;
	private static final int PARAM_CA_NAME = 1308;
	private static final int PARAM_CA_DESCRIPTION = 1309;
	private static final int PARAM_CA_TYPE = 1311;
	private static final int PARAM_CA_MONSTER = 1312;
	/**
	 * The highest task ID the completed-task varps can hold.
	 */
	private static final int MAX_TASK_ID = RuneJourneyPlugin.CA_TASK_VARPS.length * 32 - 1;
	/**
	 * Task types and monsters by ID, to their names. A task for no monster in particular is "None".
	 */
	private static final int ENUM_CA_TYPES = 3969;
	private static final int ENUM_CA_MONSTERS = 3971;
	/**
	 * The quest table's type column for a miniquest (0 is a quest).
	 */
	private static final int QUEST_TYPE_MINIQUEST = 1;

	private static final int REFRESH_TICKS = 100;

	@Inject
	private Client client;

	@Inject
	private JourneyService service;

	@Inject
	private RuneJourneyConfig config;

	/**
	 * Every task, not yet done: the same for every account, so read once.
	 */
	private List<PublicAchievements.CombatTask> tasks;
	/**
	 * Each quest's details from the quest table, read once.
	 */
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

	/**
	 * Reads again on the next tick, such as when quest points or a combat task changes.
	 */
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
			// Should the game change how it stores them, the page keeps what it had
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
		// As the game lists them, by name, with sub-quests after their quest
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
			// A quest's ID is its row in the quest table
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
				// A type or monster the enums don't list reads as empty
				String type = text(types.getStringValue(s.getIntValue(PARAM_CA_TYPE)), 40);
				String monster = text(monsters.getStringValue(s.getIntValue(PARAM_CA_MONSTER)), 100);
				out.add(new PublicAchievements.CombatTask(
					id,
					name,
					text(s.getStringValue(PARAM_CA_DESCRIPTION), 300),
					// Its tier is the list it's in, Easy (1) to Grandmaster (6)
					t + 1,
					type.isEmpty() ? "Other" : type,
					monster.isEmpty() ? "None" : monster,
					false));
			}
		}
		return out;
	}

	/**
	 * Text from the game as the website takes it: without tags, line breaks or angle brackets, and
	 * no longer than it allows.
	 */
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
