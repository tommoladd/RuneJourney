package com.runejourney;

import com.runejourney.cloud.CloudStatus;
import com.runejourney.cloud.SyncManager;
import com.runejourney.model.ClogItem;
import com.runejourney.service.JourneyService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.MenuAction;
import net.runelite.api.StructComposition;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;

/**
 * "RuneJourney" in the collection log's menu while cloud sync is connected. It opens the log's
 * search, which makes the game draw every obtained item, and records them with every page of the
 * log: for the public page, and the plugin's item goals. WikiSync and TempleOSRS sync the same way.
 */
@Slf4j
@Singleton
class CollectionLogSync
{
	// Not named by RuneLite
	/**
	 * Draws the collection log, and its menu; RuneJourney is added to the menu after it runs.
	 */
	private static final int SCRIPT_SETUP = 7797;
	/**
	 * Runs once for each obtained item as the log draws: args[1] is the item ID, args[2] how many.
	 */
	private static final int SCRIPT_DRAW_ITEM = 4100;
	/**
	 * Sets the log up afresh, which closes its search again.
	 */
	private static final int SCRIPT_SEARCH_CLOSE = 2240;
	/**
	 * The log's tabs; each tab's name and enum of pages; each page's name and enum of items.
	 */
	private static final int ENUM_TABS = 2102;
	private static final int PARAM_TAB_NAME = 682;
	private static final int PARAM_TAB_PAGES = 683;
	private static final int PARAM_PAGE_NAME = 689;
	private static final int PARAM_PAGE_ITEMS = 690;
	/**
	 * Items the log lists under one ID but draws under another.
	 */
	private static final int ENUM_REPLACEMENTS = 3721;

	/**
	 * Done once no item has been drawn for this many ticks, or after the most.
	 */
	private static final int QUIET_TICKS = 2;
	private static final int MAX_TICKS = 10;
	/**
	 * The game's "click on the menu's frame" script: closes the log's menu.
	 */
	private static final int SCRIPT_MENU_CLOSE = 7813;
	private static final String MENU_TEXT = "RuneJourney";
	/**
	 * The menu's own colours.
	 */
	private static final int MENU_COLOUR = 0xff981f;
	private static final int MENU_COLOUR_HOVERED = 0xffffff;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ItemManager itemManager;

	@Inject
	private JourneyService service;

	@Inject
	private SyncManager sync;

	@Inject
	private RuneJourneyConfig config;

	private final Map<Integer, Integer> captured = new HashMap<>();
	private boolean capturing;
	private int ticksCapturing;
	private int ticksSinceItem;

	void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() == SCRIPT_SETUP && allowed())
		{
			// After the game has drawn the menu
			clientThread.invokeLater(this::addMenuOption);
		}
	}

	void onScriptPreFired(ScriptPreFired event)
	{
		if (!capturing || event.getScriptId() != SCRIPT_DRAW_ITEM || event.getScriptEvent() == null)
		{
			return;
		}
		Object[] args = event.getScriptEvent().getArguments();
		if (args == null || args.length < 3 || !(args[1] instanceof Integer) || !(args[2] instanceof Integer))
		{
			return;
		}
		int itemId = (Integer) args[1];
		int quantity = (Integer) args[2];
		if (itemId > 0 && quantity > 0)
		{
			captured.merge(itemId, quantity, Math::max);
			ticksSinceItem = 0;
		}
	}

	void onGameTick()
	{
		if (!capturing)
		{
			return;
		}
		ticksCapturing++;
		ticksSinceItem++;
		if ((!captured.isEmpty() && ticksSinceItem >= QUIET_TICKS) || ticksCapturing >= MAX_TICKS)
		{
			capturing = false;
			finish();
		}
	}

	void reset()
	{
		capturing = false;
		captured.clear();
	}

	/**
	 * Only while the account is saved to the cloud.
	 */
	private boolean allowed()
	{
		CloudStatus status = sync.status();
		return config.cloudSync() && status.getConnection() == CloudStatus.Connection.CONNECTED && status.isLinked();
	}

	private void start()
	{
		if (capturing || client.getWidget(InterfaceID.Collection.SEARCH_TOGGLE) == null)
		{
			return;
		}
		captured.clear();
		capturing = true;
		ticksCapturing = 0;
		ticksSinceItem = 0;
		service.say("Syncing your collection log with RuneJourney...");
		// Opening the search draws every obtained item; then it's closed again
		client.menuAction(-1, InterfaceID.Collection.SEARCH_TOGGLE, MenuAction.CC_OP, 1, -1, "Search", null);
		client.runScript(SCRIPT_SEARCH_CLOSE);
	}

	/**
	 * Every page of the log from the game, with what was drawn as obtained.
	 */
	private void finish()
	{
		if (captured.isEmpty())
		{
			service.say("Couldn't read your collection log. Try the RuneJourney button again.");
			return;
		}
		Map<Integer, Integer> replacements = new HashMap<>();
		EnumComposition replaced = client.getEnum(ENUM_REPLACEMENTS);
		for (int i = 0; i < replaced.getKeys().length; i++)
		{
			replacements.put(replaced.getKeys()[i], replaced.getIntVals()[i]);
		}

		Map<String, List<String>> tabs = new LinkedHashMap<>();
		Map<String, List<ClogItem>> pages = new LinkedHashMap<>();
		Map<Integer, Boolean> unique = new HashMap<>();
		for (int tabId : client.getEnum(ENUM_TABS).getIntVals())
		{
			StructComposition tab = client.getStructComposition(tabId);
			List<String> pageNames = new ArrayList<>();
			for (int pageId : client.getEnum(tab.getIntValue(PARAM_TAB_PAGES)).getIntVals())
			{
				StructComposition page = client.getStructComposition(pageId);
				String pageName = page.getStringValue(PARAM_PAGE_NAME);
				List<ClogItem> items = new ArrayList<>();
				for (int listed : client.getEnum(page.getIntValue(PARAM_PAGE_ITEMS)).getIntVals())
				{
					int id = replacements.getOrDefault(listed, listed);
					int quantity = captured.getOrDefault(id, 0);
					items.add(new ClogItem(id, itemManager.getItemComposition(id).getName(), quantity > 0, quantity));
					unique.merge(id, quantity > 0, Boolean::logicalOr);
				}
				pageNames.add(pageName);
				pages.put(pageName, items);
			}
			tabs.put(tab.getStringValue(PARAM_TAB_NAME), pageNames);
		}
		if (pages.isEmpty())
		{
			service.say("Couldn't read your collection log. Try the RuneJourney button again.");
			return;
		}
		service.onCollectionLog(tabs, pages);
		long obtained = unique.values().stream().filter(b -> b).count();
		service.say(String.format("Collection log saved: %,d of %,d items. It'll show on your public page after the next sync.", obtained, unique.size()));
		sync.syncNow();
	}

	/**
	 * Adds "RuneJourney" to the log's menu (under "View Log" and "Overview"), styled like them. The
	 * game redraws the menu when the log opens, so it's only added if it isn't there already.
	 */
	private void addMenuOption()
	{
		Widget frame = client.getWidget(InterfaceID.Collection.BURGER_MENU_FRAME);
		Widget[] children = frame == null ? null : frame.getDynamicChildren();
		if (children == null)
		{
			return;
		}
		Widget first = null;
		Widget last = null;
		Widget ours = null;
		for (Widget w : children)
		{
			if (w == null || w.getType() != WidgetType.TEXT)
			{
				continue;
			}
			if (MENU_TEXT.equals(w.getText()))
			{
				ours = w;
			}
			else
			{
				first = first == null || w.getOriginalY() < first.getOriginalY() ? w : first;
				last = last == null || w.getOriginalY() > last.getOriginalY() ? w : last;
			}
		}
		if (last == null)
		{
			return;
		}

		int y = last.getOriginalY() + last.getOriginalHeight();
		if (ours == null)
		{
			Widget option = frame.createChild(-1, WidgetType.TEXT);
			option.setText(MENU_TEXT);
			option.setTextColor(MENU_COLOUR);
			option.setHasListener(true);
			option.setOnMouseOverListener((JavaScriptCallback) e -> option.setTextColor(MENU_COLOUR_HOVERED));
			option.setOnMouseLeaveListener((JavaScriptCallback) e -> option.setTextColor(MENU_COLOUR));
			option.setAction(0, "Sync with RuneJourney");
			option.setOnOpListener((JavaScriptCallback) e ->
			{
				client.runScript(SCRIPT_MENU_CLOSE, InterfaceID.Collection.BURGER_BTN_MENU,
					InterfaceID.Collection.BURGER_MENU_FRAME, InterfaceID.Collection.BURGER_MENU_OVERLAY);
				start();
			});
			ours = option;
		}
		ours.setFontId(last.getFontId())
			.setTextShadowed(last.getTextShadowed())
			.setXTextAlignment(last.getXTextAlignment())
			.setYTextAlignment(last.getYTextAlignment())
			.setXPositionMode(last.getXPositionMode())
			.setYPositionMode(last.getYPositionMode())
			.setPos(last.getOriginalX(), y)
			.setSize(last.getOriginalWidth(), last.getOriginalHeight());

		// As much room below the last option as above the first; the frame's border stretches
		frame.setOriginalHeight(y + last.getOriginalHeight() + first.getOriginalY());
		frame.revalidate();
		for (Widget w : frame.getDynamicChildren())
		{
			if (w != null)
			{
				w.revalidate();
			}
		}
	}
}
