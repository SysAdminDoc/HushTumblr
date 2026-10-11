/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.flags;

/**
 * Stands in for Tumblr's feature enum, whose class name changes with every build. The hook reads
 * only a constant's name, so a constant here named like Tumblr's flag is that flag to it.
 * {@link FeatureSwitchesTest} checks that every claimed flag has one.
 */
public enum FakeFeature {
    /** A flag no family claims. */
    UNCLAIMED_FLAG,
}
