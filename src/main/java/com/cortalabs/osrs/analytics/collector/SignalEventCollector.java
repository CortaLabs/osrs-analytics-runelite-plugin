/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.collector;

import com.cortalabs.osrs.analytics.AnalyticsConfig;
import com.cortalabs.osrs.analytics.dto.SignalEvent;
import com.cortalabs.osrs.analytics.transport.AnalyticsClient;
import com.cortalabs.osrs.analytics.transport.EventCategory;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/**
 * Discrete milestone-signal collector: turns witnessed game chat lines into
 * append-only {@link SignalEvent} rows — the emotional beats a grind-timeline UI
 * lives on ("you got the pet at 1,412 KC on Tue 9:04pm").
 *
 * <p><b>Witnessed-only, never synthesized (I2).</b> Every signal comes from a real
 * matched chat line via {@link #parseSignal}; a line that does not fully match a
 * pattern emits <b>nothing</b> (absence over guess). The parse is a pure static
 * method so this rule is testable with plain strings, exactly like
 * {@link CollectionLogCollector#parseCollectionLogItemName}.
 *
 * <p><b>The regex table</b> (each pattern cited to its source):
 * <ul>
 *   <li>{@code level_up} — the OSRS "Congratulations, you just advanced your
 *       &lt;skill&gt; level." line; the new level is captured only when the same
 *       message carries "You are now level N", else honestly absent.</li>
 *   <li>{@code pet} — the OSRS pet-drop lines ("...funny feeling like you're being
 *       followed", "...weird sneaking into your backpack"). The full-inventory miss
 *       ("...would have been followed") is deliberately excluded — no pet was
 *       received, so signalling one would fabricate an acquisition.</li>
 *   <li>{@code clue_completion} — the OSRS clue-casket "You have completed N
 *       &lt;tier&gt; Treasure Trails" line (subject = tier, value = count).</li>
 *   <li><b>Counters</b> — the game's "Your &lt;subject&gt; ... count is: N" family
 *       (mirroring RuneLite Chat-Commands' KC corpus), typed by what is counted:
 *       {@code boss_kc} (kill counts), {@code lap_count} (agility courses),
 *       {@code harvest_count} (e.g. Zalcano, Hespori) and {@code completion_count}
 *       (chests, raids, "completed"/"subdued" activities). Subject = the counted
 *       thing, value = the game's lifetime counter.</li>
 *   <li>{@code diary_completion} — the OSRS achievement-diary completion line
 *       ("...completed all of the &lt;tier&gt; tasks in the &lt;region&gt; area").
 *       Best-effort and deliberately tolerant of "all the"/"all of the"; a wrong
 *       assumption degrades to no match (absence), never to a fabricated row.</li>
 * </ul>
 *
 * <p><b>{@code quest_completion} is intentionally not chat-matched.</b> OSRS marks a
 * quest complete through a completion <i>widget</i>, not a reliable game message, and
 * quest-completion provenance is owned by the quest-v2 lane. The wire enum value
 * exists for parity, but fabricating a fragile chat regex here would violate the
 * witnessed-only invariant, so this collector emits none.
 *
 * <p><b>Counter coalescing.</b> A counter's meaning is its latest value per subject
 * (plus each session's first value, so the server can derive session deltas). Kill
 * counts are emitted per line — each boss kill is a moment. Lap/harvest/completion
 * counters are coalesced by {@link CounterCoalescer}: the first value per subject per
 * session is sent immediately, later values at most once per {@link
 * CounterCoalescer#WINDOW_MS} per subject, and the latest pending value is flushed after
 * the window and at session end. Without this a rooftop session sent one signal per lap
 * and could exhaust the per-session budget that level-ups and pets share.
 *
 * <p><b>Dedup + budget (I3).</b> A re-rendered chat line can fire the same
 * {@link ChatMessage} twice on one game tick; {@link SignalSessionState} suppresses
 * an identical same-tick repeat while still allowing a genuine later-tick repeat, and
 * caps emitted signals per session so a pathological chat flood cannot flood the
 * queue.
 *
 * <p><b>Account-switch clear (I4).</b> The session state is bound to the current
 * {@code accountHash}; a change clears the dedup set and the cap counter before the
 * next admit, so account A's signals can never be attributed to or throttled against
 * account B — the same fabrication guard the sibling collectors carry.
 */
@Slf4j
@Singleton
public class SignalEventCollector
{
	/**
	 * Per-session emitted-signal ceiling (I3). Set well above any human session's real
	 * signal count (level-ups + clues + per-kill KC lines + pets) so it only ever trips
	 * on a pathological chat flood; the {@link AnalyticsClient} queue is the hard backstop.
	 */
	static final int MAX_SIGNALS_PER_SESSION = 1024;

	// --- Regex corpus (witnessed-only; a partial match yields no signal) ---

	/**
	 * OSRS level-up: "Congratulations, you've just advanced your Cooking level. You are now
	 * level 43." (the game's text, as matched by RuneLite's ScreenshotPlugin). The pre-1.8
	 * pattern omitted "'ve" and never matched a real level-up.
	 */
	private static final Pattern LEVEL_UP =
		Pattern.compile("Congratulations, you(?:(?:'|’)ve)? just advanced your ([A-Za-z]+) level\\.");
	/** The new level, present only in the modern combined message: "You are now level 43." */
	private static final Pattern LEVEL_UP_VALUE = Pattern.compile("You are now level ([0-9]+)");
	/** OSRS pet drop; excludes the full-inventory miss ("...would have been followed"). */
	private static final Pattern PET = Pattern.compile(
		"You have a funny feeling like you(?:'|’)re being followed"
			+ "|You feel something weird sneaking into your backpack");
	/** OSRS clue casket: "You have completed 42 medium Treasure Trails." */
	private static final Pattern CLUE =
		Pattern.compile("You have completed ([0-9,]+) ([A-Za-z]+) Treasure Trails?");
	/**
	 * Counter lines (tags already stripped), after RuneLite Chat-Commands' KC corpus:
	 * "Your Zulrah kill count is: 500", "Your Rellekka Rooftop lap count is: 3281",
	 * "Your Barrows chest count is: 10", "Your completed Theatre of Blood count is: 5",
	 * "Your subdued Wintertodt count is: 50". Group 1 = prefix, 2 = subject,
	 * 3 = counted kind (absent for bare "count"), 4 = value.
	 */
	private static final Pattern COUNTER = Pattern.compile(
		"^Your (subdued |completed )?(.+?) (?:(kill|harvest|lap|completion|success) )?count is: ?([0-9,]+)");
	/** "Your completion count for Tombs of Amascut is: 3" (subject before "is"). */
	private static final Pattern COMPLETION_COUNT_FOR =
		Pattern.compile("^Your completion count for (.+?) is: ?([0-9,]+)");
	/** OSRS achievement diary: "...completed all of the medium tasks in the Ardougne area." */
	private static final Pattern DIARY =
		Pattern.compile("completed all (?:of )?the ([A-Za-z]+) tasks in (?:the )?(.+?)(?:\\.| [Aa]rea|$)");

	private final Client client;
	private final AnalyticsClient analytics;
	private final AnalyticsConfig config;
	private final SignalSessionState session = new SignalSessionState();
	private final CounterCoalescer counters = new CounterCoalescer();

	@Inject
	public SignalEventCollector(Client client, AnalyticsClient analytics, AnalyticsConfig config)
	{
		this.client = client;
		this.analytics = analytics;
		this.config = config;
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (!config.enabled() || !config.trackSignalEvents())
		{
			return;
		}
		ChatMessageType type = event.getType();
		if (type != ChatMessageType.GAMEMESSAGE && type != ChatMessageType.SPAM)
		{
			return;
		}
		String raw = event.getMessage();
		ParsedSignal parsed = parseSignal(raw);
		if (parsed == null)
		{
			// Witnessed-only: no pattern matched -> no signal, ever.
			return;
		}
		String rsn = Payloads.rsn(client);
		if (rsn == null)
		{
			return;
		}
		// Built now: rsn, world and timestamp are those of the witnessed line, even when a
		// coalesced counter is flushed minutes later (or after the player has logged out).
		SignalEvent payload = build(rsn, parsed);
		if (isCoalescedCounter(parsed.signalType))
		{
			for (SignalEvent due : counters.offer(client.getAccountHash(), System.currentTimeMillis(), payload))
			{
				analytics.enqueue(EventCategory.SIGNAL_EVENT, due);
			}
			return;
		}
		// Dedup a same-tick re-render and enforce the per-session budget, keyed by account.
		if (!session.admit(client.getAccountHash(), client.getTickCount(), Text.removeTags(raw)))
		{
			return;
		}
		analytics.enqueue(EventCategory.SIGNAL_EVENT, payload);
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (!config.enabled() || !config.trackSignalEvents() || !counters.hasPending())
		{
			return;
		}
		for (SignalEvent due : counters.due(client.getAccountHash(), System.currentTimeMillis()))
		{
			analytics.enqueue(EventCategory.SIGNAL_EVENT, due);
		}
	}

	private SignalEvent build(String rsn, ParsedSignal parsed)
	{
		SignalEvent payload = new SignalEvent();
		Payloads.base(payload, client, rsn);
		payload.signalType = parsed.signalType;
		payload.subject = parsed.subject;
		payload.value = parsed.value;
		payload.detail = SignalEvent.clampDetail(parsed.detail);
		return payload;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		if (state == GameState.LOGIN_SCREEN || state == GameState.CONNECTION_LOST)
		{
			// Session boundary: flush coalesced counters (the session's final values), then
			// reset the per-session dedup + budget (mirrors the sibling collectors' flush
			// trigger; a world hop keeps the session, so HOPPING does not).
			for (SignalEvent pending : counters.endSession())
			{
				if (config.enabled() && config.trackSignalEvents())
				{
					analytics.enqueue(EventCategory.SIGNAL_EVENT, pending);
				}
			}
			session.endSession();
		}
	}

	/**
	 * Parse one chat line into a discrete signal, or {@code null} when the line is not a
	 * recognised signal. Tags are stripped first so the Chat-Commands KC corpus (which is
	 * written against {@code <col=...>} markup) matches the plain text. Pure and
	 * side-effect free so the witnessed-only rule is directly testable — a line that only
	 * half-matches returns {@code null}, never a fabricated or defaulted signal.
	 */
	static ParsedSignal parseSignal(String rawMessage)
	{
		if (rawMessage == null)
		{
			return null;
		}
		String message = Text.removeTags(rawMessage).trim();
		if (message.isEmpty())
		{
			return null;
		}

		Matcher m = LEVEL_UP.matcher(message);
		if (m.find())
		{
			Matcher lv = LEVEL_UP_VALUE.matcher(message);
			Integer level = lv.find() ? parseIntOrNull(lv.group(1)) : null;
			return new ParsedSignal(SignalEvent.SignalType.LEVEL_UP, m.group(1).trim(), level, message);
		}

		if (PET.matcher(message).find())
		{
			return new ParsedSignal(SignalEvent.SignalType.PET, null, null, message);
		}

		m = CLUE.matcher(message);
		if (m.find())
		{
			return new ParsedSignal(
				SignalEvent.SignalType.CLUE_COMPLETION, m.group(2).trim(), parseIntOrNull(m.group(1)), message);
		}

		m = COMPLETION_COUNT_FOR.matcher(message);
		if (m.find())
		{
			return new ParsedSignal(
				SignalEvent.SignalType.COMPLETION_COUNT, m.group(1).trim(), parseIntOrNull(m.group(2)), message);
		}

		m = COUNTER.matcher(message);
		if (m.find())
		{
			return new ParsedSignal(
				counterType(m.group(1), m.group(3)), m.group(2).trim(), parseIntOrNull(m.group(4)), message);
		}

		m = DIARY.matcher(message);
		if (m.find())
		{
			String region = m.group(2).trim();
			if (!region.isEmpty())
			{
				return new ParsedSignal(SignalEvent.SignalType.DIARY_COMPLETION, region, null, message);
			}
		}

		// quest_completion: no honest chat line exists (widget-driven; owned by quest-v2).
		return null;
	}

	/** Classify a counter line by what it counts; only an explicit "kill" is a kill count. */
	static SignalEvent.SignalType counterType(String prefix, String kind)
	{
		if (prefix == null && "kill".equals(kind))
		{
			return SignalEvent.SignalType.BOSS_KC;
		}
		if (prefix == null && "lap".equals(kind))
		{
			return SignalEvent.SignalType.LAP_COUNT;
		}
		if (prefix == null && "harvest".equals(kind))
		{
			return SignalEvent.SignalType.HARVEST_COUNT;
		}
		// completion / success / chest ("Your Barrows chest count") / completed / subdued
		return SignalEvent.SignalType.COMPLETION_COUNT;
	}

	static boolean isCoalescedCounter(SignalEvent.SignalType type)
	{
		return type == SignalEvent.SignalType.LAP_COUNT
			|| type == SignalEvent.SignalType.HARVEST_COUNT
			|| type == SignalEvent.SignalType.COMPLETION_COUNT;
	}

	/** Parse an integer that may carry thousands separators, or {@code null} when out of range. */
	private static Integer parseIntOrNull(String digits)
	{
		try
		{
			return Integer.parseInt(digits.replace(",", ""));
		}
		catch (NumberFormatException ex)
		{
			// A number we witnessed but cannot represent: absent value, never a guess.
			return null;
		}
	}

	/** One parsed signal's classified fields; carrier for {@link #parseSignal}. */
	static final class ParsedSignal
	{
		final SignalEvent.SignalType signalType;
		final String subject;
		final Integer value;
		final String detail;

		ParsedSignal(SignalEvent.SignalType signalType, String subject, Integer value, String detail)
		{
			this.signalType = signalType;
			this.subject = subject;
			this.value = value;
			this.detail = detail;
		}
	}

	/**
	 * Per-session dedup + budget guard, bound to one account. Client-free so both honesty
	 * guarantees — same-tick re-render dedup and account-switch clear — are testable with
	 * plain data, exactly like {@link AccountKeyedDeltaGuard}.
	 *
	 * <p>Not thread-safe: called only from the client thread.
	 */
	static final class SignalSessionState
	{
		private final int cap;
		private final Set<String> seenThisTick = new HashSet<>();
		private long account = AccountKeyedDeltaGuard.NO_ACCOUNT;
		private int lastTick = Integer.MIN_VALUE;
		private int emitted;

		SignalSessionState()
		{
			this(MAX_SIGNALS_PER_SESSION);
		}

		/** Test seam: a small cap so the budget guard is provable without 1k iterations. */
		SignalSessionState(int cap)
		{
			this.cap = cap;
		}

		/**
		 * Decide whether a witnessed signal should be emitted. Returns {@code false} for an
		 * identical line already seen on this same tick (a re-render double-fire) and for any
		 * signal past the per-session cap; returns {@code true} — and counts it — otherwise.
		 * An account change clears all state first, so no cross-account bleed or throttling.
		 */
		boolean admit(long accountHash, int tick, String message)
		{
			if (accountHash != account)
			{
				account = accountHash;
				reset();
			}
			if (tick != lastTick)
			{
				lastTick = tick;
				seenThisTick.clear();
			}
			if (!seenThisTick.add(message))
			{
				return false; // same line, same tick: a re-render, not a new milestone
			}
			if (emitted >= cap)
			{
				if (emitted == cap)
				{
					log.warn("SignalEvent per-session cap ({}) reached; further signals suppressed", cap);
					emitted++; // latch so the warning is logged exactly once
				}
				return false;
			}
			emitted++;
			return true;
		}

		/** Reset the per-session dedup + budget at a session boundary. */
		void endSession()
		{
			reset();
		}

		private void reset()
		{
			seenThisTick.clear();
			lastTick = Integer.MIN_VALUE;
			emitted = 0;
		}
	}

	/**
	 * Per-session counter coalescing, bound to one account (see class javadoc). Client-free
	 * and clock-injected so its guarantees are testable with plain data: the first value
	 * per subject is emitted at once, later values at most once per window, nothing is
	 * lost (the latest pending value is always flushed — after the window or at session
	 * end), and an unchanged value is never re-sent.
	 *
	 * <p>Not thread-safe: called only from the client thread.
	 */
	static final class CounterCoalescer
	{
		static final long WINDOW_MS = 5 * 60_000L;
		/** Distinct counted subjects per session; far above any human session. */
		static final int MAX_SUBJECTS = 128;

		private final Map<String, Long> lastEmittedMs = new LinkedHashMap<>();
		private final Map<String, Integer> lastEmittedValue = new LinkedHashMap<>();
		private final Map<String, SignalEvent> pending = new LinkedHashMap<>();
		private long account = AccountKeyedDeltaGuard.NO_ACCOUNT;

		/** Offer one parsed counter; returns the signals to emit now (0 or 1). */
		List<SignalEvent> offer(long accountHash, long nowMs, SignalEvent parsed)
		{
			bind(accountHash);
			List<SignalEvent> out = new ArrayList<>(1);
			String key = parsed.signalType + "|" + parsed.subject;
			Long last = lastEmittedMs.get(key);
			if (last == null)
			{
				if (lastEmittedMs.size() >= MAX_SUBJECTS)
				{
					return out; // pathological flood; the hard backstop, never a fabrication
				}
				record(key, nowMs, parsed);
				out.add(parsed);
				return out;
			}
			if (parsed.value != null && parsed.value.equals(lastEmittedValue.get(key)))
			{
				pending.remove(key);
				return out; // unchanged counter: nothing new witnessed
			}
			if (nowMs - last >= WINDOW_MS)
			{
				pending.remove(key);
				record(key, nowMs, parsed);
				out.add(parsed);
				return out;
			}
			pending.put(key, parsed);
			return out;
		}

		boolean hasPending()
		{
			return !pending.isEmpty();
		}

		/** Pending values whose window has elapsed (called each game tick). */
		List<SignalEvent> due(long accountHash, long nowMs)
		{
			bind(accountHash);
			List<SignalEvent> out = new ArrayList<>();
			pending.entrySet().removeIf(entry ->
			{
				Long last = lastEmittedMs.get(entry.getKey());
				if (last != null && nowMs - last >= WINDOW_MS)
				{
					record(entry.getKey(), nowMs, entry.getValue());
					out.add(entry.getValue());
					return true;
				}
				return false;
			});
			return out;
		}

		/** Session end: the latest pending value per subject, then a clean slate. */
		List<SignalEvent> endSession()
		{
			List<SignalEvent> out = new ArrayList<>(pending.values());
			reset();
			return out;
		}

		private void record(String key, long nowMs, SignalEvent parsed)
		{
			lastEmittedMs.put(key, nowMs);
			lastEmittedValue.put(key, parsed.value);
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
			lastEmittedMs.clear();
			lastEmittedValue.clear();
			pending.clear();
		}
	}
}
