/*
 * Forked from https://github.com/SysAdminDoc/HushPinterest at debdac4 (GPL-3.0),
 * modified for HushTumblr (Tumblr), 2026.
 *
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.settings;

import android.content.Context;
import android.content.res.Resources;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import app.hushtumblr.extension.shared.L10n;
import app.hushtumblr.extension.shared.ResourceType;
import app.hushtumblr.extension.shared.ResourceUtils;

/**
 * Tumblr's own controls next to HushTumblr's switches. HushTumblr changes none of them. Each row
 * names the path to one in Tumblr's settings, and takes each step's label from Tumblr's own
 * strings, in the language Tumblr shows, so the path reads the way its menus do. A label a build
 * doesn't have falls back to HushTumblr's copy of the English.
 *
 * <p>No rows yet: each path has to be walked in Tumblr 46.5.0.109 and its string names read from
 * the build before it's listed here, and a path nobody has checked would send people the wrong way.
 */
final class NativeControls {
    private NativeControls() {}

    static final String SEPARATOR = " > ";
    /** Longer than any menu label: a string this long under a label's name isn't the label. */
    private static final int LONGEST_LABEL = 80;

    /** One step of a path: the name of Tumblr's string for it, and HushTumblr's copy. */
    static final class Step {
        final String resource;
        final String fallback;

        Step(String resource, String fallback) {
            this.resource = resource;
            this.fallback = fallback;
        }
    }

    /** One of Tumblr's controls: its row's title, what it covers, and the steps that reach it. */
    static final class Control {
        final String title;
        final String covers;
        final List<Step> path;

        Control(String title, String covers, Step... path) {
            this.title = title;
            this.covers = covers;
            this.path = Collections.unmodifiableList(Arrays.asList(path));
        }
    }

    /** Tumblr's controls in the order the rows show them, with the declared build's string names. */
    static List<Control> controls() {
        return Collections.emptyList();
    }

    /** The row's summary: what the control covers, then the path to it. */
    static String summary(Context context, Control control) {
        return control.covers + "\n" + L10n.f("In Tumblr: %1$s", L10n.isolate(path(context, control)));
    }

    /** [control]'s path on one line, each step in Tumblr's own words where this build has them. */
    static String path(Context context, Control control) {
        List<String> labels = new ArrayList<>();
        for (Step step : control.path) labels.add(label(context, step));
        return String.join(SEPARATOR, labels);
    }

    /** Tumblr's label for [step], or HushTumblr's copy when the build has no usable string by that name. */
    static String label(Context context, Step step) {
        int id = context == null ? 0 : ResourceUtils.getIdentifier(context, ResourceType.STRING, step.resource);
        if (id == 0) return step.fallback;
        try {
            String text = context.getString(id).trim();
            if (!text.isEmpty() && text.length() <= LONGEST_LABEL && text.indexOf('\n') < 0) return text;
        } catch (Resources.NotFoundException missing) {
            // A name with no value in this configuration: the build doesn't offer that label here.
        }
        return step.fallback;
    }
}
