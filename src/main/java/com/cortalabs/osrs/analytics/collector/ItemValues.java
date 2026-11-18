package com.cortalabs.osrs.analytics.collector;

import net.runelite.client.game.ItemManager;

/**
 * One valuation rule for every container the plugin prices (bank, equipment,
 * inventory), so the server can compare their values on one basis.
 */
final class ItemValues
{
	/** Wire name of the rule below; mirrored by the server's valuation basis. */
	static final String METHOD = "ge_then_ha_v1";

	private ItemValues()
	{
	}

	/**
	 * Per-unit value: GE price when the item has one (coins/platinum are 1/1000
	 * inside {@code getItemPrice}), otherwise the high-alchemy price so
	 * untradeables are not reported as worthless. Never negative. Client thread.
	 */
	static long unitValue(ItemManager itemManager, int itemId)
	{
		long gePrice = itemManager.getItemPrice(itemId);
		if (gePrice > 0)
		{
			return gePrice;
		}
		return Math.max(0, itemManager.getItemComposition(itemId).getHaPrice());
	}
}
