/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 *
 * TG fork: model for a device-only ("local") chat folder. Local folders are
 * never synced to Telegram servers and are not subject to server folder/pin
 * limits: membership is an explicit list of dialog ids stored in a separate
 * per-account SQLite database (see LocalFoldersStorage).
 */

package org.telegram.messenger;

import java.util.ArrayList;

public class LocalFolder {

    public int id;
    public String name;
    public int order;
    // Explicit membership only: dialog ids the user put into this folder.
    public final ArrayList<Long> dialogIds = new ArrayList<>();

    public LocalFolder() {
    }

    public LocalFolder(int id, String name, int order) {
        this.id = id;
        this.name = name;
        this.order = order;
    }
}
