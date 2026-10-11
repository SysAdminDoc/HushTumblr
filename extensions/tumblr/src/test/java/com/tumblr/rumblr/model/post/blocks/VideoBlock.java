/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.tumblr.rumblr.model.post.blocks;

/**
 * Stands in for Tumblr's VideoBlock. The post filters know a video block by this class name, on the
 * block's own class or one it extends, as Tumblr's Tumblr video and YouTube blocks do.
 */
public class VideoBlock {
    /** Stands in for Tumblr's YouTubeVideoBlock, a video block by its superclass. */
    public static final class YouTube extends VideoBlock {}
}
