/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.example.fixture;

/** What after.apk has that before.apk doesn't: one more endpoint, for the diff. */
interface Added {
    @Delete("v3/fixture/old/") Object remove();
}
