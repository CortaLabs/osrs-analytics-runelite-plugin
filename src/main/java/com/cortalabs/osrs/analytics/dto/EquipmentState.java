/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.dto;

import com.google.gson.annotations.SerializedName;
import java.util.List;
import java.util.Map;

/**
 * Worn equipment and inventory snapshot. Mirrors {@code EquipmentState}.
 *
 * <p>{@link #equipment} maps a lowercase slot name to an item id (the backend
 * requires integer ids). {@link #inventory} is a list of {@link ItemEntry}
 * ({@code item_id}, {@code quantity}). {@link #equipmentQuantities} (1.9.0) maps the
 * same slot names to the worn stack size — ammo and thrown weapons are stacks, and
 * holdings reasoning needs their counts.
 */
public class EquipmentState extends PluginPayload
{
	public Map<String, Integer> equipment;

	@SerializedName("equipment_quantities")
	public Map<String, Integer> equipmentQuantities;

	public List<ItemEntry> inventory;
}
