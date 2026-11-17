/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.collector;

import com.cortalabs.osrs.analytics.AnalyticsConfig;
import com.cortalabs.osrs.analytics.dto.XpSnapshot;
import com.cortalabs.osrs.analytics.transport.AnalyticsClient;
import com.cortalabs.osrs.analytics.transport.EventCategory;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.StatChanged;
import net.runelite.client.eventbus.Subscribe;

/**
 * Snapshots XP for all 24 OSRS skills when it changes (1.10.0).
 *
 * <p>{@link StatChanged} marks the skills dirty; the next game tick builds a snapshot and
 * offers it to an {@link XpCoalescer}: the first snapshot of a session is sent at once,
 * later ones at most once per {@link XpCoalescer#WINDOW_MS}, an unchanged map is never
 * re-sent, and the latest pending snapshot is flushed at session end. A report can then
 * always bracket its window with two distinct client observations; the old fixed
 * heartbeat re-sent unchanged state (which the server dedups) and left short windows
 * unbracketed.
 *
 * <p>The map is seeded with every required skill at 0 and then overlaid from
 * {@link Skill#values()}, so a client build that does not yet know a skill
 * (e.g. {@code sailing}) still yields a contract-complete payload without a
 * compile-time dependency on that enum constant.
 */
@Singleton
public class XpCollector
{
	/** The 24 skills the backend requires, in contract order. */
	private static final List<String> REQUIRED_SKILLS = Arrays.asList(
		"attack", "defence", "strength", "hitpoints", "ranged", "prayer", "magic",
		"cooking", "woodcutting", "fletching", "fishing", "firemaking", "crafting",
		"smithing", "mining", "herblore", "agility", "thieving", "slayer", "farming",
		"runecraft", "hunter", "construction", "sailing");

	private final Client client;
	private final AnalyticsClient analytics;
	private final AnalyticsConfig config;
	private final XpCoalescer coalescer = new XpCoalescer();

	private boolean dirty;

	@Inject
	public XpCollector(Client client, AnalyticsClient analytics, AnalyticsConfig config)
	{
		this.client = client;
		this.analytics = analytics;
		this.config = config;
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		dirty = true;
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (!config.enabled() || !config.trackXp())
		{
			return;
		}
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		long accountHash = client.getAccountHash();
		long nowMs = System.currentTimeMillis();
		if (dirty)
		{
			String rsn = Payloads.rsn(client);
			if (rsn == null)
			{
				return; // stay dirty until the player is known
			}
			dirty = false;
			send(coalescer.offer(accountHash, nowMs, snapshot(rsn)));
		}
		send(coalescer.due(accountHash, nowMs));
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		if (state == GameState.LOGGED_IN)
		{
			dirty = true; // the session's first observation, even if no stat changes
		}
		else if (state == GameState.LOGIN_SCREEN || state == GameState.CONNECTION_LOST)
		{
			// Session boundary: the latest pending snapshot (the session's final XP),
			// then a clean slate (mirrors SignalEventCollector's counter flush).
			XpSnapshot pending = coalescer.endSession();
			if (config.enabled() && config.trackXp())
			{
				send(pending);
			}
			dirty = false;
		}
	}

	private void send(XpSnapshot payload)
	{
		if (payload != null)
		{
			analytics.enqueue(EventCategory.XP, payload);
		}
	}

	private XpSnapshot snapshot(String rsn)
	{
		Map<String, Integer> skills = new LinkedHashMap<>();
		for (String name : REQUIRED_SKILLS)
		{
			skills.put(name, 0);
		}
		for (Skill skill : Skill.values())
		{
			String key = skill.getName().toLowerCase(Locale.ROOT);
			if (skills.containsKey(key))
			{
				skills.put(key, client.getSkillExperience(skill));
			}
		}

		XpSnapshot payload = new XpSnapshot();
		Payloads.base(payload, client, rsn);
		payload.skills = skills;
		return payload;
	}

	/**
	 * Per-account XP coalescing: first snapshot now, then at most one per window; an
	 * unchanged map is never re-sent; the latest pending snapshot (with its own
	 * timestamp) is sent when the window elapses or the session ends. Pure, so the
	 * cadence is testable without a client.
	 */
	static final class XpCoalescer
	{
		static final long WINDOW_MS = 60_000L;

		private long account = AccountKeyedDeltaGuard.NO_ACCOUNT;
		private long lastEmittedMs;
		private Map<String, Integer> lastEmitted;
		private XpSnapshot pending;

		/** Offer the current snapshot; returns it when it should be sent now, else null. */
		XpSnapshot offer(long accountHash, long nowMs, XpSnapshot snapshot)
		{
			bind(accountHash);
			if (snapshot.skills.equals(lastEmitted))
			{
				pending = null; // back to what was last sent: nothing new witnessed
				return null;
			}
			if (lastEmitted == null || nowMs - lastEmittedMs >= WINDOW_MS)
			{
				pending = null;
				record(nowMs, snapshot);
				return snapshot;
			}
			pending = snapshot;
			return null;
		}

		/** The pending snapshot once its window has elapsed (called each game tick). */
		XpSnapshot due(long accountHash, long nowMs)
		{
			bind(accountHash);
			if (pending == null || nowMs - lastEmittedMs < WINDOW_MS)
			{
				return null;
			}
			XpSnapshot out = pending;
			pending = null;
			record(nowMs, out);
			return out;
		}

		/** Session end: the latest pending snapshot (or null), then a clean slate. */
		XpSnapshot endSession()
		{
			XpSnapshot out = pending;
			reset();
			return out;
		}

		private void record(long nowMs, XpSnapshot snapshot)
		{
			lastEmittedMs = nowMs;
			lastEmitted = snapshot.skills;
		}

		private void bind(long accountHash)
		{
			if (accountHash != account)
			{
				account = accountHash;
				reset();
			}
		}

		private void reset()
		{
			lastEmitted = null;
			lastEmittedMs = 0L;
			pending = null;
		}
	}
}
