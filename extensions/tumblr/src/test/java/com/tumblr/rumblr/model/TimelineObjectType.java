/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.tumblr.rumblr.model;

/**
 * Stands in for Tumblr's item kinds. The filter reads a kind by its constant's name, so a constant
 * here named like one of Tumblr's is that kind to it.
 */
public enum TimelineObjectType {
    UNKNOWN,
    POST,
    TITLE,
    GEMINI_AD,
    FACEBOOK_BIDDAABLE,
    NIMBUS_AD,
    DISPLAY_IO_INTERSCROLLER_S2S_ADS,
    VUNGLE_AD,
    CLIENT_SIDE_AD,
    CLIENT_SIDE_MEDIATION,
    DISPLAY_IO_INTERSCROLLER_AD,
    GOOGLE_NATIVE,
    BLAZED_POST,
}
