package com.couple.xiaowo.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 本地记录：下过的单、冰箱里囤着的菜。
 * 纯 Java（不碰 Android API），所以可以直接在电脑上跑单测。
 */
public final class History {

    public static final class Entry {
        public long ts;
        public int total;
        public String note = "";
        public boolean delivered = true;
        public final List<Protocol.Line> lines = new ArrayList<Protocol.Line>();

        public String toJson() {
            List<String> items = new ArrayList<String>();
            for (Protocol.Line l : lines) {
                items.add(Json.object(Json.fields(
                        "name", Json.quote(l.name),
                        "qty", String.valueOf(l.qty),
                        "price", String.valueOf(l.price),
                        "cat", String.valueOf(l.cat),
                        "emoji", Json.quote(l.emoji),
                        "spec", Json.quote(l.spec))));
            }
            return Json.object(Json.fields(
                    "ts", String.valueOf(ts),
                    "total", String.valueOf(total),
                    "note", Json.quote(note),
                    "delivered", String.valueOf(delivered),
                    "items", Json.array(items)));
        }

        public static Entry fromJson(Object o) {
            Map<String, Object> m = Json.asObject(o);
            Entry e = new Entry();
            e.ts = Json.num(m, "ts", 0);
            e.total = (int) Json.num(m, "total", 0);
            e.note = Json.str(m, "note", "");
            e.delivered = Boolean.parseBoolean(Json.str(m, "delivered", "true"));
            for (Object it : Json.list(m, "items")) {
                Map<String, Object> im = Json.asObject(it);
                e.lines.add(new Protocol.Line(
                        Json.str(im, "name", ""),
                        (int) Json.num(im, "qty", 1),
                        (int) Json.num(im, "price", 0),
                        (int) Json.num(im, "cat", 0),
                        Json.str(im, "emoji", ""),
                        Json.str(im, "spec", "")));
            }
            return e;
        }

        public int count() {
            int n = 0;
            for (Protocol.Line l : lines) {
                n += l.qty;
            }
            return n;
        }

        public String summary() {
            StringBuilder sb = new StringBuilder();
            for (Protocol.Line l : lines) {
                if (sb.length() > 0) {
                    sb.append('、');
                }
                sb.append(l.displayName());
                if (l.qty > 1) {
                    sb.append(" ×").append(l.qty);
                }
            }
            return sb.toString();
        }
    }

    private History() {
    }

    public static List<Entry> parse(String json) {
        List<Entry> out = new ArrayList<Entry>();
        if (json == null || json.length() == 0) {
            return out;
        }
        try {
            Object o = Json.parse(json);
            if (o instanceof List) {
                for (Object item : (List<?>) o) {
                    out.add(Entry.fromJson(item));
                }
            }
        } catch (Exception ignore) {
        }
        return out;
    }

    public static String toJson(List<Entry> list) {
        List<String> parts = new ArrayList<String>();
        for (Entry e : list) {
            parts.add(e.toJson());
        }
        return Json.array(parts);
    }

    public static List<String> fridgeFrom(String json) {
        List<String> out = new ArrayList<String>();
        if (json == null || json.length() == 0) {
            return out;
        }
        try {
            Object o = Json.parse(json);
            if (o instanceof List) {
                for (Object item : (List<?>) o) {
                    String s = String.valueOf(item);
                    if (s.length() > 0) {
                        out.add(s);
                    }
                }
            }
        } catch (Exception ignore) {
        }
        return out;
    }

    public static String fridgeToJson(List<String> names) {
        List<String> parts = new ArrayList<String>();
        for (String n : names) {
            parts.add(Json.quote(n));
        }
        return Json.array(parts);
    }
}
