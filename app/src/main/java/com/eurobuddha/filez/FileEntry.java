package com.eurobuddha.filez;

import org.json.JSONObject;

/** One row of a node FILE list response. */
public class FileEntry {
    public final String name;
    public final boolean isdir;
    public final long size;
    public final long modified;

    public FileEntry(JSONObject o) {
        name = o.optString("name", "");
        isdir = o.optBoolean("isdir", false);
        size = o.optLong("size", 0);
        modified = o.optLong("modified", 0);
    }
}
