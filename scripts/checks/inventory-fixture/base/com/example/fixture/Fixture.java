/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.example.fixture;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.URL;
import java.net.URLConnection;

/**
 * A tiny app for the AppInventory contract: one of each thing an inventory section counts, so a
 * real dex gives known lines. build-inventory-fixtures.ps1 compiles it into before.apk, and with
 * added/ into after.apk.
 */
final class Fixture {
    /** A host the hosts section finds. */
    static final String HOST = "https://api.example.com/fixture";

    private Fixture() {}
}

@Retention(RetentionPolicy.RUNTIME) @interface Get { String value(); }
@Retention(RetentionPolicy.RUNTIME) @interface Post { String value(); }
@Retention(RetentionPolicy.RUNTIME) @interface Put { String value(); }
@Retention(RetentionPolicy.RUNTIME) @interface Delete { String value(); }

/** Retrofit's annotation reader, as R8 leaves it: a type test, then the verb's name. */
final class Reader {
    private Reader() {}

    static String verb(Object annotation) {
        if (annotation instanceof Get) return "GET";
        if (annotation instanceof Post) return "POST";
        if (annotation instanceof Put) return "PUT";
        if (annotation instanceof Delete) return "DELETE";
        return null;
    }
}

interface Service {
    @Post("v3/fixture/event/") Object event(Object body);

    @Get("v3/fixture/feed/") Object feed();
}

/** The startup task enum: its constants are TAG_ names. */
enum Task { TAG_FIXTURE_ONE, TAG_FIXTURE_TWO }

final class Startup {
    private Startup() {}

    static Object first() { return Task.TAG_FIXTURE_ONE; }
}

final class Net {
    private Net() {}

    static URLConnection open(URL url) throws IOException { return url.openConnection(); }
}

final class Ids {
    private Ids() {}

    static String androidId(android.content.ContentResolver resolver) {
        return android.provider.Settings.Secure.getString(resolver, "android_id");
    }
}
