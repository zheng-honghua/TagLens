package com.taglens.scanner;

import android.graphics.RectF;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class ScanResult {
    final int width, height;
    final String family;
    final List<Tag> tags;
    final double milliseconds;
    final boolean photo;

    ScanResult(int width, int height, String family, List<Tag> tags, long start, boolean photo) {
        this.width = width;
        this.height = height;
        this.family = family;
        this.tags = tags;
        this.milliseconds = (System.nanoTime() - start) / 1_000_000.0;
        this.photo = photo;
        tags.sort(Comparator.comparingInt((Tag tag) -> tag.id).thenComparingDouble(tag -> tag.y)
                .thenComparingDouble(tag -> tag.x));
    }

    String json() throws JSONException {
        JSONArray entries = new JSONArray();
        for (Tag tag : tags) {
            JSONArray corners = new JSONArray();
            for (int i = 0; i < 8; i += 2) {
                corners.put(new JSONObject().put("x", tag.corners[i]).put("y", tag.corners[i + 1]));
            }
            entries.put(new JSONObject().put("id", tag.id).put("family", family)
                .put("center", new JSONObject().put("x", tag.x).put("y", tag.y))
                .put("corners", corners).put("angle", tag.angle())
                .put("hamming", tag.hamming).put("decisionMargin", tag.margin));
        }
        return new JSONObject().put("app", "TagLens Android").put("family", family)
            .put("width", width).put("height", height).put("ms", milliseconds)
            .put("count", tags.size()).put("tags", entries).toString(2);
    }

    static List<Tag> decode(float[] values) {
        List<Tag> tags = new ArrayList<>(values.length / 13);
        for (int i = 0; i + 12 < values.length; i += 13) tags.add(new Tag(values, i));
        return tags;
    }

    static final class Tag {
        final int id, hamming;
        final float margin;
        float x, y;
        final float[] corners = new float[8];

        Tag(float[] data, int i) {
            id = (int)data[i];
            x = data[i + 1];
            y = data[i + 2];
            System.arraycopy(data, i + 3, corners, 0, 8);
            margin = data[i + 11];
            hamming = (int)data[i + 12];
        }

        double angle() {
            double dx = (corners[4] + corners[6]) / 2.0 - x;
            double dy = y - (corners[5] + corners[7]) / 2.0;
            return (Math.round(Math.toDegrees(Math.atan2(dx, dy)) * 10) + 3600) % 3600 / 10.0;
        }

        RectF bounds() {
            float left = corners[0], right = corners[0], top = corners[1], bottom = corners[1];
            for (int i = 2; i < 8; i += 2) {
                left = Math.min(left, corners[i]);
                right = Math.max(right, corners[i]);
                top = Math.min(top, corners[i + 1]);
                bottom = Math.max(bottom, corners[i + 1]);
            }
            return new RectF(left, top, right, bottom);
        }

        void transform(android.graphics.Matrix matrix) {
            float[] center = {x, y};
            matrix.mapPoints(center);
            x = center[0];
            y = center[1];
            matrix.mapPoints(corners);
        }

        void offset(float dx, float dy) {
            x += dx;
            y += dy;
            for (int i = 0; i < 8; i += 2) {
                corners[i] += dx;
                corners[i + 1] += dy;
            }
        }
    }
}
