package io.github.haydenkz.meshcorehelper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A bounded, newest-first live log for the current radio connection. */
public final class RadioLogs {
    public static final int LIMIT = 200;
    private final ArrayDeque<RadioLog> entries = new ArrayDeque<>();
    public List<RadioLog> add(RadioLog entry) {
        entries.addFirst(entry);
        while (entries.size() > LIMIT) entries.removeLast();
        return snapshot();
    }
    public List<RadioLog> snapshot() { return Collections.unmodifiableList(new ArrayList<>(entries)); }
    public void clear() { entries.clear(); }
}
