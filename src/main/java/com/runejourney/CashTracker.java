package com.runejourney;

import com.runejourney.service.JourneyService;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.ItemManager;

@Singleton
class CashTracker
{
	private final ItemManager itemManager;
	private final JourneyService service;

	@Inject
	CashTracker(ItemManager itemManager, JourneyService service)
	{
		this.itemManager = itemManager;
		this.service = service;
	}

	void onContainer(int containerId, ItemContainer container)
	{
		boolean bank = containerId == InventoryID.BANK;
		if (!bank && containerId != InventoryID.INV)
		{
			return;
		}
		// Older net worth goals now follow the bank's GE value, as RuneLite's bank shows it
		boolean valued = bank && service.wantsBankValue();
		long cash = 0;
		long value = 0;
		for (Item item : container.getItems())
		{
			int id = item.getId();
			int qty = item.getQuantity();
			if (id <= 0 || qty <= 0)
			{
				continue;
			}
			if (id == ItemID.COINS)
			{
				cash += qty;
			}
			else if (id == ItemID.PLATINUM)
			{
				cash += qty * 1000L;
			}
			else if (valued)
			{
				value += (long) itemManager.getItemPrice(id) * qty;
			}
		}
		service.onCash(bank, cash);
		if (valued)
		{
			service.onBankValue(cash + value);
		}
	}
}
