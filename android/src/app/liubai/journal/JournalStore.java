package app.liubai.journal;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

final class JournalStore {
    private static final int BACKUP_FORMAT_VERSION = 1;
    private static final String[] VALID_MOODS = {"不錯", "平靜", "疲憊", "低落"};

    static final class ImportResult {
        int added;
        int updated;
        int skipped;
        int rejected;

        int changed() { return added + updated; }
    }

    static final class Entry {
        String id;
        String date;
        String title;
        String body;
        String mood;
        String createdAt;
        String updatedAt;

        JSONObject toJson() {
            JSONObject value = new JSONObject();
            try {
                value.put("id", id);
                value.put("date", date);
                value.put("title", title);
                value.put("body", body);
                value.put("mood", mood);
                value.put("createdAt", createdAt);
                value.put("updatedAt", updatedAt);
            } catch (Exception ignored) { }
            return value;
        }

        static Entry fromJson(JSONObject value) {
            Entry entry = new Entry();
            entry.id = value.optString("id", UUID.randomUUID().toString());
            entry.date = value.optString("date", LocalDate.now().toString());
            entry.title = value.optString("title", "");
            entry.body = value.optString("body", "");
            entry.mood = value.optString("mood", "平靜");
            entry.createdAt = value.optString("createdAt", OffsetDateTime.now().toString());
            entry.updatedAt = value.optString("updatedAt", entry.createdAt);
            return entry;
        }
    }

    private final File directory;
    private final File file;
    private final ArrayList<Entry> entries = new ArrayList<>();

    JournalStore(Context context) {
        directory = new File(context.getFilesDir(), "LiubaiJournal");
        file = new File(directory, "entries.json");
        load();
    }

    synchronized List<Entry> all() {
        ArrayList<Entry> copy = new ArrayList<>(entries);
        Collections.sort(copy, (left, right) -> {
            int byDate = right.date.compareTo(left.date);
            return byDate != 0 ? byDate : right.updatedAt.compareTo(left.updatedAt);
        });
        return copy;
    }

    synchronized Entry find(String id) {
        for (Entry entry : entries) if (entry.id.equals(id)) return entry;
        return null;
    }

    synchronized Entry create() {
        Entry entry = new Entry();
        entry.id = UUID.randomUUID().toString();
        entry.date = LocalDate.now().toString();
        entry.title = "";
        entry.body = "";
        entry.mood = "平靜";
        entry.createdAt = OffsetDateTime.now().toString();
        entry.updatedAt = entry.createdAt;
        entries.add(entry);
        save();
        return entry;
    }

    synchronized void update(Entry entry) {
        entry.updatedAt = OffsetDateTime.now().toString();
        save();
    }

    synchronized void delete(String id) {
        entries.removeIf(entry -> entry.id.equals(id));
        save();
    }

    synchronized int count() { return entries.size(); }

    synchronized String exportJson() throws Exception {
        JSONArray array = new JSONArray();
        for (Entry entry : entries) array.put(entry.toJson());
        JSONObject root = new JSONObject();
        root.put("app", "留白日記");
        root.put("formatVersion", BACKUP_FORMAT_VERSION);
        root.put("exportedAt", OffsetDateTime.now().toString());
        root.put("entries", array);
        return root.toString(2);
    }

    synchronized ImportResult importJson(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        int version = root.has("formatVersion")
                ? root.optInt("formatVersion", -1)
                : root.optInt("version", BACKUP_FORMAT_VERSION);
        if (version != BACKUP_FORMAT_VERSION) {
            throw new IllegalArgumentException("不支援的備份格式版本");
        }
        JSONArray importedEntries = root.optJSONArray("entries");
        if (importedEntries == null) {
            throw new IllegalArgumentException("備份檔缺少日記資料");
        }

        ImportResult result = new ImportResult();
        for (int i = 0; i < importedEntries.length(); i++) {
            JSONObject value = importedEntries.optJSONObject(i);
            if (!isValidBackupEntry(value)) {
                result.rejected++;
                continue;
            }
            Entry incoming = Entry.fromJson(value);
            incoming.mood = validMood(incoming.mood) ? incoming.mood : "平靜";
            Entry existing = findInternal(incoming.id);
            if (existing == null) {
                entries.add(incoming);
                result.added++;
            } else if (isNewer(incoming.updatedAt, existing.updatedAt)) {
                existing.date = incoming.date;
                existing.title = incoming.title;
                existing.body = incoming.body;
                existing.mood = incoming.mood;
                existing.createdAt = incoming.createdAt;
                existing.updatedAt = incoming.updatedAt;
                result.updated++;
            } else {
                result.skipped++;
            }
        }
        if (result.changed() > 0) save();
        return result;
    }

    private Entry findInternal(String id) {
        for (Entry entry : entries) if (entry.id.equals(id)) return entry;
        return null;
    }

    private boolean isValidBackupEntry(JSONObject value) {
        if (value == null) return false;
        String id = value.optString("id", "").trim();
        String date = value.optString("date", "").trim();
        String createdAt = value.optString("createdAt", "").trim();
        String updatedAt = value.optString("updatedAt", "").trim();
        if (id.isEmpty() || id.length() > 200 || date.isEmpty()
                || createdAt.isEmpty() || updatedAt.isEmpty()) return false;
        try {
            LocalDate.parse(date);
            OffsetDateTime.parse(createdAt);
            OffsetDateTime.parse(updatedAt);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isNewer(String incoming, String current) {
        try {
            return OffsetDateTime.parse(incoming).toInstant()
                    .isAfter(OffsetDateTime.parse(current).toInstant());
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean validMood(String mood) {
        for (String candidate : VALID_MOODS) if (candidate.equals(mood)) return true;
        return false;
    }

    private void load() {
        if (!file.exists()) return;
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int total = 0;
            while (total < bytes.length) {
                int read = input.read(bytes, total, bytes.length - total);
                if (read < 0) break;
                total += read;
            }
            JSONObject root = new JSONObject(new String(bytes, 0, total, StandardCharsets.UTF_8));
            JSONArray array = root.optJSONArray("entries");
            if (array == null) return;
            for (int i = 0; i < array.length(); i++) {
                JSONObject value = array.optJSONObject(i);
                if (value != null) entries.add(Entry.fromJson(value));
            }
        } catch (Exception ignored) {
            File backup = new File(directory, "entries-unreadable-" + System.currentTimeMillis() + ".json");
            file.renameTo(backup);
        }
    }

    private void save() {
        try {
            if (!directory.exists() && !directory.mkdirs()) return;
            JSONArray array = new JSONArray();
            for (Entry entry : entries) array.put(entry.toJson());
            JSONObject root = new JSONObject();
            root.put("version", BACKUP_FORMAT_VERSION);
            root.put("entries", array);
            byte[] bytes = root.toString(2).getBytes(StandardCharsets.UTF_8);
            File temporary = new File(directory, "entries.tmp");
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(bytes);
                output.getFD().sync();
            }
            if (file.exists() && !file.delete()) return;
            temporary.renameTo(file);
        } catch (Exception ignored) { }
    }
}
