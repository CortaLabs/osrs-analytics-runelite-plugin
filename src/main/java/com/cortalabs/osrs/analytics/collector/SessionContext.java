/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.collector;

import java.util.UUID;
import javax.inject.Singleton;

/**
 * The one login session identity shared by every session-scoped collector (1.10.0).
 *
 * <p>Before 1.10.0 each collector minted its own random id, so {@code session_id} on
 * efficiency, activity time, region time and kill counts never matched each other or the
 * session events, and sessions could not be joined. The first collector to see a tick
 * of a new session mints the id; {@link SessionCollector} ends it at LOGIN_SCREEN /
 * CONNECTION_LOST (a world hop keeps the session).
 */
@Singleton
public class SessionContext
{
	private String sessionId;

	/** The current session's id, minted on first use. */
	public synchronized String current()
	{
		if (sessionId == null)
		{
			sessionId = UUID.randomUUID().toString();
		}
		return sessionId;
	}

	/** Session boundary: the next {@link #current()} starts a new session. */
	public synchronized void end()
	{
		sessionId = null;
	}
}
