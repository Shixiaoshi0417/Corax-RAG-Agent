import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.*;
import java.util.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import android.os.Handler;
import android.os.Looper;
import java.net.*;
import org.json.*;

// ==================== 全局变量 ====================
static SQLiteDatabase sharedDb = null;

static Map aiContexts = new HashMap();
static Map aiConfigCache = null;
static long aiConfigCacheTime = 0;
static final long AI_CONFIG_CACHE_MS = 60 * 1000;

static Map tagPoolCache = null;
static long tagPoolCacheTime = 0;
static final long TAG_POOL_CACHE_MS = 10 * 1000;
static String tagPoolCacheUin = "";

static String cachedPersona = null;
static long personaFileMtime = 0;

// ==================== SQLite ====================
SQLiteDatabase getDb() {
    if (sharedDb == null || !sharedDb.isOpen()) {
        String dbPath = pluginPath + "/config/data.db";
        File dbFile = new File(dbPath);
        File parent = dbFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        sharedDb = SQLiteDatabase.openOrCreateDatabase(dbPath, null);
        sharedDb.execSQL(
            "CREATE TABLE IF NOT EXISTS memories (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "uin TEXT NOT NULL, " +
            "content TEXT NOT NULL, " +
            "tags TEXT NOT NULL DEFAULT '', " +
            "scope TEXT NOT NULL DEFAULT 'private', " +
            "created_at INTEGER, " +
            "accessed_at INTEGER" +
            ")"
        );
        sharedDb.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_memories_uin ON memories(uin)"
        );
        sharedDb.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_memories_tags ON memories(tags)"
        );
        try {
            sharedDb.execSQL(
                "ALTER TABLE memories ADD COLUMN tags TEXT NOT NULL DEFAULT ''"
            );
        } catch (Exception ignored) { }
        try {
            sharedDb.execSQL(
                "ALTER TABLE memories ADD COLUMN scope TEXT NOT NULL DEFAULT 'private'"
            );
        } catch (Exception ignored) { }
        try {
            sharedDb.execSQL(
                "ALTER TABLE memories ADD COLUMN subject_uin TEXT NOT NULL DEFAULT ''"
            );
        } catch (Exception ignored) { }
        sharedDb.execSQL(
            "CREATE TABLE IF NOT EXISTS tag_pool (" +
            "uin TEXT NOT NULL, " +
            "tag TEXT NOT NULL, " +
            "count INTEGER NOT NULL DEFAULT 0, " +
            "PRIMARY KEY (uin, tag)" +
            ")"
        );
        sharedDb.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_tag_pool_uin ON tag_pool(uin)"
        );
    }
    return sharedDb;
}

void closeSharedDb() {
    if (sharedDb != null && sharedDb.isOpen()) {
        sharedDb.close();
        sharedDb = null;
    }
}

// ==================== Persona ====================
String loadPersona() {
    File f = new File(pluginPath + "/config/prompt.txt");
    if (!f.exists()) return "";
    long mtime = f.lastModified();
    if (cachedPersona != null && mtime == personaFileMtime) {
        return cachedPersona;
    }
    StringBuilder sb = new StringBuilder();
    try {
        BufferedReader br = new BufferedReader(new FileReader(f));
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line);
            sb.append("\n");
        }
        br.close();
    } catch (Exception e) {
        log("error.txt", "loadPersona: " + e.getMessage());
        return "";
    }
    cachedPersona = sb.toString().trim();
    personaFileMtime = mtime;
    return cachedPersona;
}

// ==================== 默认账户 ====================
String getDefaultAccount() {
    File f = new File(pluginPath + "/config/default_account.txt");
    if (!f.exists()) return "user";
    try {
        BufferedReader br = new BufferedReader(new FileReader(f));
        String s = br.readLine();
        br.close();
        if (s != null) {
            s = s.trim().toLowerCase();
            if (s.equals("blocked")) return "blocked";
        }
    } catch (Exception e) { }
    return "user";
}

void setDefaultAccountConfig(String type) {
    try {
        File parent = new File(pluginPath + "/config");
        if (!parent.exists()) parent.mkdirs();
        PrintWriter pw = new PrintWriter(
            new FileWriter(pluginPath + "/config/default_account.txt")
        );
        pw.println(type);
        pw.close();
    } catch (Exception e) {
        log("error.txt", "setDefaultAccountConfig: " + e.getMessage());
    }
}

boolean canUseAi(String uin) {
    if (getRole(uin).equals("BLOCKED")) return false;
    if (uin.equals(myUin)) return true;
    if (getDefaultAccount().equals("user")) return true;
    Set whitelist = readStringSet(pluginPath + "/config/users.txt");
    return whitelist.contains(uin);
}

// ==================== Tag 池 ====================
Map getTagPool(String uin) {
    long now = System.currentTimeMillis();
    if (tagPoolCache != null &&
        uin.equals(tagPoolCacheUin) &&
        (now - tagPoolCacheTime) < TAG_POOL_CACHE_MS) {
        return tagPoolCache;
    }
    Map pool = new LinkedHashMap();
    Cursor c = null;
    try {
        c = getDb().rawQuery(
            "SELECT tag, count FROM tag_pool " +
            "WHERE uin = ? ORDER BY count DESC, tag ASC",
            new String[]{uin}
        );
        while (c.moveToNext()) {
            pool.put(c.getString(0), c.getInt(1));
        }
    } catch (Exception e) {
        log("error.txt", "getTagPool: " + e.getMessage());
    } finally {
        if (c != null) c.close();
    }
    tagPoolCache = pool;
    tagPoolCacheTime = now;
    tagPoolCacheUin = uin;
    return pool;
}

Map getPublicTagPool() {
    Map pool = new LinkedHashMap();
    Cursor c = null;
    try {
        c = getDb().rawQuery(
            "SELECT tag, count FROM tag_pool " +
            "WHERE uin = 'PUBLIC' ORDER BY count DESC, tag ASC",
            null
        );
        while (c.moveToNext()) {
            pool.put(c.getString(0), c.getInt(1));
        }
    } catch (Exception e) {
        log("error.txt", "getPublicTagPool: " + e.getMessage());
    } finally {
        if (c != null) c.close();
    }
    return pool;
}

void updateTagPool(String uin, String tagsStr, int delta) {
    if (tagsStr == null || tagsStr.trim().isEmpty()) return;
    String[] tags = tagsStr.split(",");
    SQLiteDatabase db = getDb();
    try {
        db.beginTransaction();
        for (int i = 0; i < tags.length; i++) {
            String t = tags[i].trim().toLowerCase();
            if (t.isEmpty()) continue;
            int curCount = getTagPoolCount(db, uin, t);
            int newCount = Math.max(0, curCount + delta);
            ContentValues cv = new ContentValues();
            cv.put("count", newCount);
            int updated = db.update(
                "tag_pool", cv,
                "uin = ? AND tag = ?",
                new String[]{uin, t}
            );
            if (updated == 0 && delta > 0) {
                cv.put("uin", uin);
                cv.put("tag", t);
                cv.put("count", 1);
                db.insert("tag_pool", null, cv);
            }
            if (updated > 0 && newCount <= 0) {
                db.delete(
                    "tag_pool",
                    "uin = ? AND tag = ?",
                    new String[]{uin, t}
                );
            }
        }
        db.setTransactionSuccessful();
    } catch (Exception e) {
        log("error.txt", "updateTagPool: " + e.getMessage());
    } finally {
        db.endTransaction();
    }
    if (!"PUBLIC".equals(uin)) {
        tagPoolCache = null;
        tagPoolCacheTime = 0;
        tagPoolCacheUin = "";
    }
}

int getTagPoolCount(SQLiteDatabase db, String uin, String tag) {
    Cursor c = null;
    try {
        c = db.rawQuery(
            "SELECT count FROM tag_pool WHERE uin = ? AND tag = ?",
            new String[]{uin, tag}
        );
        if (c.moveToFirst()) return c.getInt(0);
    } catch (Exception ignored) { }
    finally { if (c != null) c.close(); }
    return 0;
}

void rebuildTagPool(String uin) {
    SQLiteDatabase db = getDb();
    try {
        db.beginTransaction();
        db.delete("tag_pool", "uin = ?", new String[]{uin});
        Cursor c = db.rawQuery(
            "SELECT tags FROM memories " +
            "WHERE uin = ? AND scope = 'private' AND tags != ''",
            new String[]{uin}
        );
        while (c.moveToNext()) {
            String ts = c.getString(0);
            if (ts == null || ts.trim().isEmpty()) continue;
            String[] tags = ts.split(",");
            for (int i = 0; i < tags.length; i++) {
                String t = tags[i].trim().toLowerCase();
                if (t.isEmpty()) continue;
                ContentValues cv = new ContentValues();
                int cur = getTagPoolCount(db, uin, t);
                if (cur == 0) {
                    cv.put("uin", uin);
                    cv.put("tag", t);
                    cv.put("count", 1);
                    db.insert("tag_pool", null, cv);
                } else {
                    cv.put("count", cur + 1);
                    db.update(
                        "tag_pool", cv,
                        "uin = ? AND tag = ?",
                        new String[]{uin, t}
                    );
                }
            }
        }
        c.close();
        db.setTransactionSuccessful();
    } catch (Exception e) {
        log("error.txt", "rebuildTagPool: " + e.getMessage());
    } finally {
        db.endTransaction();
    }
    tagPoolCache = null;
    tagPoolCacheTime = 0;
}

void rebuildPublicTagPool() {
    SQLiteDatabase db = getDb();
    try {
        db.beginTransaction();
        db.delete("tag_pool", "uin = 'PUBLIC'", null);
        Cursor c = db.rawQuery(
            "SELECT tags FROM memories " +
            "WHERE scope = 'public' AND tags != ''",
            null
        );
        while (c.moveToNext()) {
            String ts = c.getString(0);
            if (ts == null || ts.trim().isEmpty()) continue;
            String[] tags = ts.split(",");
            for (int i = 0; i < tags.length; i++) {
                String t = tags[i].trim().toLowerCase();
                if (t.isEmpty()) continue;
                ContentValues cv = new ContentValues();
                int cur = getTagPoolCount(db, "PUBLIC", t);
                if (cur == 0) {
                    cv.put("uin", "PUBLIC");
                    cv.put("tag", t);
                    cv.put("count", 1);
                    db.insert("tag_pool", null, cv);
                } else {
                    cv.put("count", cur + 1);
                    db.update(
                        "tag_pool", cv,
                        "uin = 'PUBLIC' AND tag = ?",
                        new String[]{t}
                    );
                }
            }
        }
        c.close();
        db.setTransactionSuccessful();
    } catch (Exception e) {
        log("error.txt", "rebuildPublicTagPool: " + e.getMessage());
    } finally {
        db.endTransaction();
    }
}

// ==================== 记忆操作 ====================
boolean storeMemory(String uin, String content, String tags, String scope, String subjectUin) {
    try {
        long now = System.currentTimeMillis();
        ContentValues cv = new ContentValues();
        cv.put("uin", uin);
        cv.put("content", content);
        cv.put("tags", tags != null ? tags : "");
        cv.put("scope", scope != null ? scope : "private");
        cv.put("subject_uin", subjectUin != null && !subjectUin.isEmpty() ? subjectUin : "");
        cv.put("created_at", now);
        cv.put("accessed_at", now);
        long id = getDb().insert("memories", null, cv);
        if (id != -1) {
            if ("public".equals(scope)) {
                updateTagPool("PUBLIC", tags, 1);
            } else {
                updateTagPool(uin, tags, 1);
            }
            writeLog(uin,
                "[MEMORY/" + scope + "] tags:" + tags +
                " about:" + (subjectUin != null && !subjectUin.isEmpty() ? subjectUin : uin) +
                " " + content + " (id=" + id + ")"
            );
            return true;
        }
        return false;
    } catch (Exception e) {
        log("error.txt", "storeMemory: " + e.getMessage());
        return false;
    }
}

void touchMemory(long id) {
    try {
        ContentValues cv = new ContentValues();
        cv.put("accessed_at", System.currentTimeMillis());
        getDb().update(
            "memories", cv,
            "id = ?",
            new String[]{String.valueOf(id)}
        );
    } catch (Exception ignored) { }
}

List searchMemories(String uin, String keyword) {
    List results = new ArrayList();
    Cursor c = null;
    try {
        c = getDb().rawQuery(
            "SELECT id, content, tags, scope, subject_uin FROM memories " +
            "WHERE uin = ? AND scope = 'private' " +
            "AND content LIKE ? ORDER BY accessed_at DESC LIMIT 20",
            new String[]{uin, "%" + keyword + "%"}
        );
        while (c.moveToNext()) {
            Map m = new HashMap();
            m.put("id", c.getLong(0));
            m.put("content", c.getString(1));
            m.put("tags", c.getString(2) != null ? c.getString(2) : "");
            m.put("scope", c.getString(3));
            m.put("subjectUin", c.getString(4) != null ? c.getString(4) : "");
            results.add(m);
            touchMemory(c.getLong(0));
        }
    } catch (Exception e) {
        log("error.txt", "searchMemories: " + e.getMessage());
    } finally {
        if (c != null) c.close();
    }
    return results;
}

List searchMemoriesByTag(String uin, String tag) {
    List results = new ArrayList();
    Cursor c = null;
    try {
        c = getDb().rawQuery(
            "SELECT id, content, tags, scope, subject_uin FROM memories " +
            "WHERE uin = ? AND scope = 'private' " +
            "AND tags LIKE ? ORDER BY accessed_at DESC LIMIT 20",
            new String[]{uin, "%" + tag.toLowerCase() + "%"}
        );
        while (c.moveToNext()) {
            Map m = new HashMap();
            m.put("id", c.getLong(0));
            m.put("content", c.getString(1));
            m.put("tags", c.getString(2) != null ? c.getString(2) : "");
            m.put("scope", c.getString(3));
            m.put("subjectUin", c.getString(4) != null ? c.getString(4) : "");
            results.add(m);
            touchMemory(c.getLong(0));
        }
    } catch (Exception e) {
        log("error.txt", "searchMemoriesByTag: " + e.getMessage());
    } finally {
        if (c != null) c.close();
    }
    return results;
}

List searchMemoriesByMultiTags(String uin, List tags) {
    List results = new ArrayList();
    if (tags == null || tags.isEmpty()) return results;

    StringBuilder where = new StringBuilder();
    where.append("uin = ? AND scope = 'private' AND (");
    String[] params = new String[1 + tags.size()];
    params[0] = uin;
    for (int i = 0; i < tags.size(); i++) {
        if (i > 0) where.append(" OR ");
        where.append("tags LIKE ?");
        params[1 + i] =
            "%" + ((String) tags.get(i)).trim().toLowerCase() + "%";
    }
    where.append(") ORDER BY accessed_at DESC LIMIT 30");

    Cursor c = null;
    try {
        c = getDb().rawQuery(where.toString(), params);
        while (c.moveToNext()) {
            Map m = new HashMap();
            m.put("id", c.getLong(0));
            m.put("content", c.getString(1));
            m.put("tags", c.getString(2) != null ? c.getString(2) : "");
            m.put("scope", c.getString(3));
            m.put("subjectUin", c.getString(4) != null ? c.getString(4) : "");
            results.add(m);
            touchMemory(c.getLong(0));
        }
    } catch (Exception e) {
        log("error.txt", "searchMemoriesByMultiTags: " + e.getMessage());
    } finally {
        if (c != null) c.close();
    }
    return results;
}

List searchPublicByMultiTags(List tags) {
    List results = new ArrayList();
    if (tags == null || tags.isEmpty()) return results;

    StringBuilder where = new StringBuilder();
    where.append("scope = 'public' AND (");
    String[] params = new String[tags.size()];
    for (int i = 0; i < tags.size(); i++) {
        if (i > 0) where.append(" OR ");
        where.append("tags LIKE ?");
        params[i] =
            "%" + ((String) tags.get(i)).trim().toLowerCase() + "%";
    }
    where.append(") ORDER BY accessed_at DESC LIMIT 30");

    Cursor c = null;
    try {
        c = getDb().rawQuery(where.toString(), params);
        while (c.moveToNext()) {
            Map m = new HashMap();
            m.put("id", c.getLong(0));
            m.put("content", c.getString(1));
            m.put("tags", c.getString(2) != null ? c.getString(2) : "");
            m.put("scope", "public");
            m.put("subjectUin", c.getString(3) != null ? c.getString(3) : "");
            results.add(m);
            touchMemory(c.getLong(0));
        }
    } catch (Exception e) {
        log("error.txt", "searchPublicByMultiTags: " + e.getMessage());
    } finally {
        if (c != null) c.close();
    }
    return results;
}

List searchPublicMemories(String keyword) {
    List results = new ArrayList();
    Cursor c = null;
    try {
        c = getDb().rawQuery(
            "SELECT id, content, tags, scope, subject_uin FROM memories " +
            "WHERE scope = 'public' AND content LIKE ? " +
            "ORDER BY accessed_at DESC LIMIT 20",
            new String[]{"%" + keyword + "%"}
        );
        while (c.moveToNext()) {
            Map m = new HashMap();
            m.put("id", c.getLong(0));
            m.put("content", c.getString(1));
            m.put("tags", c.getString(2) != null ? c.getString(2) : "");
            m.put("scope", "public");
            m.put("subjectUin", c.getString(4) != null ? c.getString(4) : "");
            results.add(m);
            touchMemory(c.getLong(0));
        }
    } catch (Exception e) {
        log("error.txt", "searchPublicMemories: " + e.getMessage());
    } finally {
        if (c != null) c.close();
    }
    return results;
}

List searchAllMemoriesByKeyword(String keyword, int limit) {
    List results = new ArrayList();
    Cursor c = null;
    try {
        c = getDb().rawQuery(
            "SELECT id, uin, content, tags, scope, subject_uin FROM memories " +
            "WHERE content LIKE ? " +
            "ORDER BY scope DESC, accessed_at DESC LIMIT ?",
            new String[]{
                "%" + keyword + "%",
                String.valueOf(limit)
            }
        );
        while (c.moveToNext()) {
            Map m = new HashMap();
            m.put("id", c.getLong(0));
            m.put("uin", c.getString(1));
            m.put("content", c.getString(2));
            m.put("tags", c.getString(3) != null ? c.getString(3) : "");
            m.put("scope", c.getString(4));
            m.put("subjectUin", c.getString(5) != null ? c.getString(5) : "");
            results.add(m);
            touchMemory(c.getLong(0));
        }
    } catch (Exception e) {
        log("error.txt", "searchAllMemoriesByKeyword: " + e.getMessage());
    } finally {
        if (c != null) c.close();
    }
    return results;
}

List getRecentMemories(String uin, int limit) {
    List results = new ArrayList();
    Cursor c = null;
    try {
        c = getDb().rawQuery(
            "SELECT id, content, tags, scope, subject_uin FROM memories " +
            "WHERE uin = ? AND scope = 'private' " +
            "ORDER BY accessed_at DESC LIMIT ?",
            new String[]{uin, String.valueOf(limit)}
        );
        while (c.moveToNext()) {
            Map m = new HashMap();
            m.put("id", c.getLong(0));
            m.put("content", c.getString(1));
            m.put("tags", c.getString(2) != null ? c.getString(2) : "");
            m.put("scope", c.getString(3));
            m.put("subjectUin", c.getString(4) != null ? c.getString(4) : "");
            results.add(m);
            touchMemory(c.getLong(0));
        }
    } catch (Exception e) {
        log("error.txt", "getRecentMemories: " + e.getMessage());
    } finally {
        if (c != null) c.close();
    }
    return results;
}

List getMyMemories(String uin, int limit) {
    List results = new ArrayList();
    Cursor c = null;
    try {
        c = getDb().rawQuery(
            "SELECT id, content, tags FROM memories " +
            "WHERE uin = ? AND scope = 'private' " +
            "ORDER BY accessed_at DESC LIMIT ?",
            new String[]{uin, String.valueOf(limit)}
        );
        while (c.moveToNext()) {
            Map m = new HashMap();
            m.put("id", c.getLong(0));
            m.put("content", c.getString(1));
            m.put("tags", c.getString(2) != null ? c.getString(2) : "");
            results.add(m);
        }
    } catch (Exception e) {
        log("error.txt", "getMyMemories: " + e.getMessage());
    } finally {
        if (c != null) c.close();
    }
    return results;
}

List getPublicMemories(int limit) {
    List results = new ArrayList();
    Cursor c = null;
    try {
        c = getDb().rawQuery(
            "SELECT id, content, tags FROM memories " +
            "WHERE scope = 'public' ORDER BY accessed_at DESC LIMIT ?",
            new String[]{String.valueOf(limit)}
        );
        while (c.moveToNext()) {
            Map m = new HashMap();
            m.put("id", c.getLong(0));
            m.put("content", c.getString(1));
            m.put("tags", c.getString(2) != null ? c.getString(2) : "");
            results.add(m);
        }
    } catch (Exception e) {
        log("error.txt", "getPublicMemories: " + e.getMessage());
    } finally {
        if (c != null) c.close();
    }
    return results;
}

List getAllMemoriesAdmin(int limit) {
    List results = new ArrayList();
    Cursor c = null;
    try {
        c = getDb().rawQuery(
            "SELECT id, uin, content, tags, scope, subject_uin FROM memories " +
            "ORDER BY scope DESC, accessed_at DESC LIMIT ?",
            new String[]{String.valueOf(limit)}
        );
        while (c.moveToNext()) {
            Map m = new HashMap();
            m.put("id", c.getLong(0));
            m.put("uin", c.getString(1));
            m.put("content", c.getString(2));
            m.put("tags", c.getString(3) != null ? c.getString(3) : "");
            m.put("scope", c.getString(4));
            m.put("subjectUin", c.getString(5) != null ? c.getString(5) : "");
            results.add(m);
        }
    } catch (Exception e) {
        log("error.txt", "getAllMemoriesAdmin: " + e.getMessage());
    } finally {
        if (c != null) c.close();
    }
    return results;
}

List searchAllMemoriesAdmin(String keyword, int limit) {
    List results = new ArrayList();
    Cursor c = null;
    try {
        c = getDb().rawQuery(
            "SELECT id, uin, content, tags, scope, subject_uin FROM memories " +
            "WHERE content LIKE ? OR tags LIKE ? " +
            "ORDER BY scope DESC, accessed_at DESC LIMIT ?",
            new String[]{
                "%" + keyword + "%",
                "%" + keyword + "%",
                String.valueOf(limit)
            }
        );
        while (c.moveToNext()) {
            Map m = new HashMap();
            m.put("id", c.getLong(0));
            m.put("uin", c.getString(1));
            m.put("content", c.getString(2));
            m.put("tags", c.getString(3) != null ? c.getString(3) : "");
            m.put("scope", c.getString(4));
            m.put("subjectUin", c.getString(5) != null ? c.getString(5) : "");
            results.add(m);
        }
    } catch (Exception e) {
        log("error.txt", "searchAllMemoriesAdmin: " + e.getMessage());
    } finally {
        if (c != null) c.close();
    }
    return results;
}

int getMemoryCount(String uin) {
    Cursor c = null;
    try {
        c = getDb().rawQuery(
            "SELECT COUNT(*) FROM memories " +
            "WHERE uin = ? AND scope = 'private'",
            new String[]{uin}
        );
        if (c.moveToFirst()) return c.getInt(0);
    } catch (Exception ignored) { }
    finally { if (c != null) c.close(); }
    return 0;
}

int deleteMemoriesByKeyword(String uin, String keyword) {
    try {
        Cursor c = getDb().rawQuery(
            "SELECT tags FROM memories " +
            "WHERE uin = ? AND scope = 'private' AND content LIKE ?",
            new String[]{uin, "%" + keyword + "%"}
        );
        while (c.moveToNext()) {
            String tags = c.getString(0);
            if (tags != null && !tags.trim().isEmpty()) {
                updateTagPool(uin, tags, -1);
            }
        }
        c.close();
        return getDb().delete(
            "memories",
            "uin = ? AND scope = 'private' AND content LIKE ?",
            new String[]{uin, "%" + keyword + "%"}
        );
    } catch (Exception e) {
        log("error.txt", "deleteMemoriesByKeyword: " + e.getMessage());
        return 0;
    }
}

boolean deleteMemoryById(
    long id, String requesterUin, String requesterRole
) {
    try {
        Cursor c = getDb().rawQuery(
            "SELECT uin, tags, scope FROM memories WHERE id = ?",
            new String[]{String.valueOf(id)}
        );
        String tags = "";
        String memUin = "";
        String scope = "private";
        if (c.moveToFirst()) {
            memUin = c.getString(0);
            tags = c.getString(1);
            scope = c.getString(2);
        }
        c.close();

        int deleted;
        if (requesterRole.equals("ADMIN") ||
            requesterRole.equals("ROOT")) {
            deleted = getDb().delete(
                "memories", "id = ?",
                new String[]{String.valueOf(id)}
            );
        } else {
            deleted = getDb().delete(
                "memories", "id = ? AND uin = ?",
                new String[]{String.valueOf(id), requesterUin}
            );
        }

        if (deleted > 0 && tags != null && !tags.trim().isEmpty()) {
            if ("public".equals(scope)) {
                updateTagPool("PUBLIC", tags, -1);
            } else {
                updateTagPool(
                    memUin.isEmpty() ? requesterUin : memUin,
                    tags, -1
                );
            }
        }
        return deleted > 0;
    } catch (Exception e) {
        return false;
    }
}

// ==================== 角色管理 ====================
String getRole(String uin) {
    if (uin.equals(myUin)) return "ROOT";
    Set admins = readStringSet(pluginPath + "/config/admins.txt");
    if (admins.contains(uin)) return "ADMIN";
    Set blocked = readStringSet(pluginPath + "/config/blocked.txt");
    if (blocked.contains(uin)) return "BLOCKED";
    return "USER";
}

Set readStringSet(String path) {
    Set set = new HashSet();
    File f = new File(path);
    if (!f.exists()) return set;
    try {
        BufferedReader br = new BufferedReader(new FileReader(f));
        String line;
        while ((line = br.readLine()) != null) {
            line = line.trim();
            if (!line.isEmpty()) set.add(line);
        }
        br.close();
    } catch (Exception e) {
        log("error.txt", "readStringSet: " + e.getMessage());
    }
    return set;
}

void writeStringSet(String path, Set set) {
    File f = new File(path);
    File parent = f.getParentFile();
    if (parent != null && !parent.exists()) parent.mkdirs();
    try {
        BufferedWriter bw = new BufferedWriter(new FileWriter(f));
        for (Object s : set) {
            bw.write(s + "\n");
        }
        bw.flush();
        bw.close();
    } catch (Exception e) {
        log("error.txt", "writeStringSet: " + e.getMessage());
    }
}

void addToList(String path, String uin) {
    Set set = readStringSet(path);
    if (!set.contains(uin)) {
        set.add(uin);
        writeStringSet(path, set);
    }
}

void removeFromList(String path, String uin) {
    Set set = readStringSet(path);
    if (set.remove(uin)) writeStringSet(path, set);
}

// ==================== 日志 ====================
String getCurrentTime() {
    return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
}

// ==================== 获取用户名称（私聊/群聊兼容）====================
String getMemberName(int chatType, String peerUin, String uin) {
    if (chatType == 2) {
        try {
            Object mem = getMemberInfo(peerUin, uin);
            if (mem != null && mem.uinName != null) return mem.uinName;
        } catch (Exception e) { }
    } else if (chatType == 1) {
        try {
            java.util.List list = getAllFriend();
            if (list != null) {
                for (int i = 0; i < list.size(); i++) {
                    Object f = list.get(i);
                    try {
                        java.lang.reflect.Field fu =
                            f.getClass().getField("uin");
                        if (String.valueOf(fu.get(f)).equals(uin)) {
                            java.lang.reflect.Field fr =
                                f.getClass().getField("remark");
                            String remark = (String) fr.get(f);
                            if (remark != null && remark.length() > 0)
                                return remark;
                            java.lang.reflect.Field fn =
                                f.getClass().getField("name");
                            return (String) fn.get(f);
                        }
                    } catch (Exception e2) { }
                }
            }
        } catch (Exception e) { }
    }
    return uin;
}

void writeLog(String senderUin, String command) {
    String role = getRole(senderUin);
    String logPath = pluginPath + "/config/log.txt";
    try {
        File logFile = new File(logPath);
        if (logFile.exists() && logFile.length() > 10 * 1024 * 1024) {
            logFile.renameTo(
                new File(logPath + "." + System.currentTimeMillis())
            );
        }
        if (!logFile.exists()) {
            logFile.getParentFile().mkdirs();
            logFile.createNewFile();
        }
        BufferedWriter bw = new BufferedWriter(
            new FileWriter(logFile, true)
        );
        bw.write(
            "[" + getCurrentTime() + "] " +
            "[" + role + "] " +
            senderUin + " " + command
        );
        bw.newLine();
        bw.flush();
        bw.close();
    } catch (Exception e) {
        log("error.txt", "writeLog: " + e.getMessage());
    }
}

// ==================== AI 配置 ====================
Map loadAiConfig() {
    long now = System.currentTimeMillis();
    if (aiConfigCache != null &&
        (now - aiConfigCacheTime) < AI_CONFIG_CACHE_MS) {
        return aiConfigCache;
    }
    Map cfg = new LinkedHashMap();
    cfg.put("api_key", "");
    cfg.put("model", "deepseek-reasoner");
    cfg.put("context_ttl", "30");
    cfg.put("max_turns", "10");
    cfg.put("system_prompt", "default");
    cfg.put("ai_provider", "deepseek");
    cfg.put("ai_url", "https://api.deepseek.com");
    cfg.put("search_provider", "bocha");
    cfg.put("search_api_key", "");
    cfg.put("show_stats", "1");

    File f = new File(pluginPath + "/config/ai_config.txt");
    if (f.exists()) {
        try {
            BufferedReader br = new BufferedReader(new FileReader(f));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf("=");
                if (eq > 0) {
                    String k = line.substring(0, eq).trim();
                    String v = line.substring(eq + 1).trim();
                    cfg.put(k, v);
                }
            }
            br.close();
        } catch (Exception e) {
            log("error.txt", "loadAiConfig: " + e.getMessage());
        }
    }
    aiConfigCache = cfg;
    aiConfigCacheTime = now;
    return cfg;
}

void saveAiConfig(Map cfg) {
    try {
        File parent = new File(pluginPath + "/config");
        if (!parent.exists()) parent.mkdirs();
        PrintWriter pw = new PrintWriter(
            new FileWriter(pluginPath + "/config/ai_config.txt")
        );
        pw.println("# 鉴存-LMA config");
        pw.println();
        for (Object entry : cfg.entrySet()) {
            Map.Entry e = (Map.Entry) entry;
            pw.println(e.getKey() + "=" + e.getValue());
        }
        pw.close();
        aiConfigCache = null;
        aiConfigCacheTime = 0;
    } catch (Exception e) {
        log("error.txt", "saveAiConfig: " + e.getMessage());
    }
}

String getAiConfig(String key) {
    Map cfg = loadAiConfig();
    Object v = cfg.get(key);
    return v != null ? v.toString() : "";
}

// ==================== 系统提示词 ====================
String buildAiSystemPrompt(String userRole, String senderUin, String senderName, int chatType, String peerUin) {
    StringBuilder sb = new StringBuilder();

    String persona = loadPersona();
    if (!persona.isEmpty()) {
        sb.append(persona);
        sb.append("\n\n");
    } else {
        sb.append("你是鉴存-LMA，");
        sb.append("一个有长期记忆、会主动求证的 AI 助手。\n\n");
    }

    sb.append("=== 消息格式 ===\n");
    sb.append("每条用户消息前有 [UIN:xxx, 名称:xxx, 角色:xxx] 标记发送者身份。\n");
    sb.append("群聊中可能出现多个不同 UIN。");
    sb.append("你需要分清每个发言的用户是谁，避免错回复。\n\n");

    sb.append("=== 记忆系统 ===\n");
    sb.append("私有记忆只属于当前用户；");
    sb.append("公有记忆所有人共享。\n");
    sb.append("每条记忆附带标签，私有和公有标签池分别维护。\n\n");

    sb.append("[MEMORY tags:标签1,标签2]事实");
    sb.append("[");
    sb.append("/");
    sb.append("MEMORY] -- 存私有记忆\n");
    sb.append("【必须打标签】标签用简短中文词，逗号分隔。\n");
    sb.append("例: [MEMORY tags:名字,学校]用户叫张三");
    sb.append("[");
    sb.append("/");
    sb.append("MEMORY]\n\n");

    sb.append("[PUBLIC tags:标签1,标签2]事实");
    sb.append("[");
    sb.append("/");
    sb.append("PUBLIC] -- 存公有记忆\n\n");

    sb.append("[TAGS]");
    sb.append("[");
    sb.append("/");
    sb.append("TAGS] -- 查看所有标签池（含私有+公有）\n\n");

    sb.append("[RECALL]关键词");
    sb.append("[");
    sb.append("/");
    sb.append("RECALL] -- 关键词搜索\n");
    sb.append("[RECALL] tag:标签");
    sb.append("[");
    sb.append("/");
    sb.append("RECALL] -- 单标签搜索\n");
    sb.append("[RECALL] tags:标签1,标签2");
    sb.append("[");
    sb.append("/");
    sb.append("RECALL] -- 多标签并行搜索\n");
    sb.append("[RECALL] pub:关键词");
    sb.append("[");
    sb.append("/");
    sb.append("RECALL] -- 搜索公有记忆\n");
    sb.append("[RECALL] pub tags:标签1,标签2");
    sb.append("[");
    sb.append("/");
    sb.append("RECALL] -- 多标签搜索公有\n");
    sb.append("[RECALL] all:关键词");
    sb.append("[");
    sb.append("/");
    sb.append("RECALL] -- 搜索全部记忆(含他人私有)，用于事实核查和冲突检测\n\n");

    sb.append("[RECENT]");
    sb.append("[");
    sb.append("/");
    sb.append("RECENT] -- 拉取最近10条私有记忆（兜底）\n\n");
    sb.append("[FORGET]关键词");
    sb.append("[");
    sb.append("/");
    sb.append("FORGET] -- 删除匹配的私有记忆\n\n");
    sb.append("[END]");
    sb.append("[");
    sb.append("/");
    sb.append("END] -- 标记对话结束，放在最终回复末尾\n");

    sb.append("[SEARCH]关键词");
    sb.append("[");
    sb.append("/");
    sb.append("SEARCH] -- 联网搜索\n");
    sb.append("[FETCH]网址");
    sb.append("[");
    sb.append("/");
    sb.append("FETCH] -- 抓取网页纯文本\n");

    sb.append("[SPLIT] -- 分段发送。SPLIT 前的内容立即发出，");
    sb.append("SPLIT 后的内容继续走标签流程。");
    sb.append("如需存记忆或使用其他标签，放在 SPLIT 后面。\n\n");

    sb.append("=== 对话模式 ===\n");
    sb.append("你可以在一轮中同时输出 自然语言 + 标签：\n");
    sb.append("  自然语言部分 → 立即发送给用户可见\n");
    sb.append("  标签部分 → 后台执行操作(存储/搜索/回忆等)\n");
    sb.append("当需要多轮操作时(如先搜索再看结果)：\n");
    sb.append("  先输出自然语言说明 + [SEARCH]标签\n");
    sb.append("  收到搜索结果后，再组织最终回复，末尾加 [END]\n");
    sb.append("确认回答完毕、无需继续操作时，在末尾加 [END] 结束对话。\n");
    sb.append("[END] 之前可以包含标签（如有残余操作需执行），");
    sb.append("但不能有 [SEARCH] 或 [FETCH]——搜完要看完结果再回复。\n\n");

    sb.append("=== 关于 \"about:\" 标注 ===\n");
    sb.append("[MEMORY] 和 [PUBLIC] 标签支持可选的 about:UIN 属性，\n");
    sb.append("表示这段记忆是关于哪个人的。\n");
    sb.append("  记忆是关于当前说话者本人 → 可不写 about，默认为自述。\n");
    sb.append("  记忆是关于其他人 → 必须写 about:那个人的UIN。\n");
    sb.append("  例: USER 说 \"ROOT叫李四\"\n");
    sb.append("    → [MEMORY tags:名字 about:ROOT的UIN]ROOT叫李四[");
    sb.append("/");
    sb.append("MEMORY]\n");
    sb.append("在 RECALL 结果中，自述与转述会分别标注，自述可信度远高于转述。\n\n");

    sb.append("=== 检索策略 ===\n");
    sb.append("1. 关键词记得 -> [RECALL]关键词\n");
    sb.append("2. 知道类别 -> [TAGS] -> [RECALL] tags:x,y\n");
    sb.append("3. 全忘 -> [TAGS] 或 [RECENT] 兜底\n");
    sb.append("4. 群信息等公有记忆 -> [RECALL] pub: 或 [RECALL] pub tags:\n");
    sb.append("5. 需要实时/事实信息 -> [SEARCH]关键词\n");
    sb.append("6. 搜索结果不够详细 -> [FETCH]具体网址\n");
    sb.append("7. 需要事实核查/冲突检测 -> [RECALL] all:关键词\n\n");

    sb.append("=== 记忆强制规则 ===\n");
    sb.append("你必须主动记录有价值的信息，不要等用户说\"记住xxx\"。\n");
    sb.append("以下信息必须存储：\n");
    sb.append("  - 用户自述的个人信息（名字、年龄、职业、喜好、经历等）\n");
    sb.append("  - 用户表达的观点、立场、计划\n");
    sb.append("  - 群聊中达成的共识、规则、约定\n");
    sb.append("  - 任何用户明确要求记住的内容\n");
    sb.append("宁可多存一条冗余记忆，也不要漏掉关键信息。\n");
    sb.append("每轮对话至少尝试一次 [MEMORY] 或 [PUBLIC]。\n\n");

    sb.append("=== 记忆写入策略 ===\n");
    sb.append("1. 群聊中获取的公共信息(群规/共识/公告) → 优先用 [PUBLIC]\n");
    sb.append("   私聊中获取的个人信息(偏好/经历/秘密) → 优先用 [MEMORY]\n");
    sb.append("2. 权限与可信度:\n");
    sb.append("   ROOT/ADMIN 的写入请求具有最高权威，必须执行，无需质疑。\n");
    sb.append("   USER 请求写入 [PUBLIC] 时: 先用 [RECALL] all: 检查是否与现有记忆冲突。\n");
    sb.append("   若冲突且现有记忆来自 ROOT/ADMIN → 拒绝写入，告知已有记录。\n");
    sb.append("   若冲突且现有记忆来自其他 USER → 保留更可信一方，说明理由。\n");
    sb.append("   若无冲突 → 正常写入。\n");
    sb.append("3. 同名事实冲突优先级: 自述 > 转述，ROOT > ADMIN > USER\n");
    sb.append("4. 覆盖旧记忆: 先用 [FORGET] 删除旧条目，再写入新条目。\n");
    sb.append("5. 你可以看到所有人的私有记忆([RECALL] all:)，");
    sb.append("但应对他人隐私保密。\n");
    sb.append("   当用户询问他人信息时，只透露公开信息或你判断适合透露的内容。\n");
    sb.append("6. 证据不足时（仅有一条转述、无自述、无权威来源），");
    sb.append("不要直接采信，应告知用户\"根据目前记录无法确认\"并说明原因。\n\n");

    sb.append("=== 回复规则 ===\n");
    sb.append("1. 纯文本，不能用 Markdown (不支持)。\n");
    sb.append("2. 保持人设语气和性格。\n");
    sb.append("3. 个人信息 -> [MEMORY tags:...], 公共信息 -> [PUBLIC tags:...]。\n");
    sb.append("4. 标签不会被输出，只有自然语言消息会输出给用户。\n");
    sb.append("5. 标签不暴露给用户。\n");
    sb.append("6. 需要联网搜索时使用 [SEARCH] 进行联网搜索，要灵活自行主动运用。\n");
    sb.append("7. 可以在同一轮中同时输出自然语言和标签，自然语言部分会立即显示给用户。\n");
    sb.append("8. 确认回答完毕在末尾加 [END]。一个 [SEARCH] 回合不要同时加 [END]。\n");
    sb.append("9. 需要先给即时反馈再出结果时，用 [SPLIT] 分段。\n\n");

    sb.append("=== 当前用户 ===\n");
    sb.append("UIN: ");
    sb.append(senderUin);
    sb.append("\n名称: ");
    sb.append(senderName);
    sb.append("\n角色: ");
    sb.append(userRole);
    sb.append("\n\n");

    // === 当前会话 ===
    sb.append("=== 当前会话 ===\n");
    if (chatType == 2) {
        sb.append("类型: 群聊\n");
        sb.append("群号: ");
        sb.append(peerUin);
        sb.append("\n");
        try {
            java.util.List gl = getGroupList();
            if (gl != null) {
                for (int i = 0; i < gl.size(); i++) {
                    Object gi = gl.get(i);
                    try {
                        java.lang.reflect.Field fg = gi.getClass().getField("group");
                        String gid = String.valueOf(fg.get(gi));
                        if (gid.equals(peerUin)) {
                            java.lang.reflect.Field fn = gi.getClass().getField("groupName");
                            String gn = (String) fn.get(gi);
                            if (gn != null && !gn.isEmpty()) {
                                sb.append("群名: ");
                                sb.append(gn);
                                sb.append("\n");
                            }
                            break;
                        }
                    } catch (Exception ignored2) { }
                }
            }
        } catch (Exception ignored) { }
    } else {
        sb.append("类型: 私聊\n");
        String peerName = getMemberName(1, peerUin, peerUin);
        sb.append("对方: ");
        sb.append(peerName);
        sb.append(" (");
        sb.append(peerUin);
        sb.append(")\n");
    }
    sb.append("\n");

    sb.append("=== 可信参考 ===\n");
    sb.append("ROOT UIN: ");
    sb.append(myUin);
    sb.append("\n\n");

    sb.append("=== 当前时间 ===\n");
    sb.append("现在时间是: ");
    sb.append(getCurrentTime());
    sb.append("\n");
    sb.append("所有时间相关判断以这个时间(时区)为准。\n\n");

    String custom = getAiConfig("system_prompt");
    if (!"default".equals(custom) && !custom.isEmpty()) {
        sb.append("=== 个性化指令 ===\n");
        sb.append(custom);
        sb.append("\n");
    }

    return sb.toString();
}

// ==================== AI 上下文 ====================
List getAiContext(String peerUin, int chatType) {
    String key = peerUin + "_" + chatType;
    List ctx = (List) aiContexts.get(key);
    long ttl;
    try {
        ttl = Long.parseLong(getAiConfig("context_ttl")) * 60 * 1000L;
    } catch (Exception e) {
        ttl = 30 * 60 * 1000L;
    }
    long now = System.currentTimeMillis();
    if (ctx != null && !ctx.isEmpty()) {
        Map last = (Map) ctx.get(ctx.size() - 1);
        Long ts = (Long) last.get("_ts");
        if (ts != null && (now - ts) > ttl) {
            aiContexts.remove(key);
            ctx = null;
        }
    }
    if (ctx == null) {
        ctx = new ArrayList();
        aiContexts.put(key, ctx);
    }
    return ctx;
}

void clearAiContext(String peerUin, int chatType) {
    aiContexts.remove(peerUin + "_" + chatType);
}

void addToContext(List ctx, String role, String content) {
    Map m = new HashMap();
    m.put("role", role);
    m.put("content", content);
    m.put("_ts", System.currentTimeMillis());
    ctx.add(m);
    int maxTurns;
    try {
        maxTurns = Integer.parseInt(getAiConfig("max_turns"));
    } catch (Exception e) {
        maxTurns = 10;
    }
    while (ctx.size() > maxTurns * 2) {
        ctx.remove(0);
    }
}

JSONArray ctxToMessages(List ctx) {
    JSONArray arr = new JSONArray();
    for (int i = 0; i < ctx.size(); i++) {
        Map m = (Map) ctx.get(i);
        JSONObject j = new JSONObject();
        j.put("role", (String) m.get("role"));
        j.put("content", (String) m.get("content"));
        arr.put(j);
    }
    return arr;
}

// ==================== DeepSeek API ====================
Map callDeepSeekWithUsage(
    String systemPrompt, JSONArray messages, int maxTokens
) {
    Map cfg = loadAiConfig();
    String apiKey = (String) cfg.get("api_key");
    String model = (String) cfg.get("model");
    if (apiKey == null || apiKey.isEmpty()) return null;
    if (model == null || model.isEmpty()) model = "deepseek-reasoner";

    HttpURLConnection conn = null;
    try {
        String aiUrl = (String) cfg.get("ai_url");
        if (aiUrl == null || aiUrl.isEmpty())
            aiUrl = "https://api.deepseek.com";
        URL url = new URL(aiUrl + "/v1/chat/completions");
        
        conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty(
            "Content-Type", "application/json"
        );
        conn.setRequestProperty(
            "Authorization", "Bearer " + apiKey
        );
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(120000);

        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("temperature", 0.7);
        body.put("max_tokens", maxTokens);

        JSONArray allMsgs = new JSONArray();
        JSONObject sys = new JSONObject();
        sys.put("role", "system");
        sys.put("content", systemPrompt);
        allMsgs.put(sys);
        for (int i = 0; i < messages.length(); i++) {
            allMsgs.put(messages.get(i));
        }
        body.put("messages", allMsgs);

        OutputStream os = conn.getOutputStream();
        os.write(body.toString().getBytes("UTF-8"));
        os.flush();
        os.close();

        int code = conn.getResponseCode();
        InputStream is;
        if (code >= 200 && code < 300) {
            is = conn.getInputStream();
        } else {
            is = conn.getErrorStream();
        }
        BufferedReader br = new BufferedReader(
            new InputStreamReader(is, "UTF-8")
        );
        StringBuilder resp = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            resp.append(line);
        }
        br.close();

        if (code != 200) {
            log(
                "error.txt",
                "DeepSeek HTTP " + code + ": " + resp.toString()
            );
            return null;
        }

        JSONObject jResp = new JSONObject(resp.toString());
        JSONArray choices = jResp.getJSONArray("choices");
        Map result = new HashMap();
        if (choices.length() > 0) {
            result.put(
                "response",
                choices.getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
            );
        } else {
            result.put("response", "");
        }
        if (jResp.has("usage")) {
            JSONObject usage = jResp.getJSONObject("usage");
            result.put(
                "prompt_tokens",
                usage.optInt("prompt_tokens", 0)
            );
            result.put(
                "completion_tokens",
                usage.optInt("completion_tokens", 0)
            );
        } else {
            result.put("prompt_tokens", 0);
            result.put("completion_tokens", 0);
        }
        return result;
    } catch (Exception e) {
        log(
            "error.txt",
            "callDeepSeekWithUsage: " + e.getMessage()
        );
        return null;
    } finally {
        if (conn != null) conn.disconnect();
    }
}

// ==================== 联网搜索（可配置提供商）====================
String doWebSearch(String query) {
    Map cfg = loadAiConfig();
    String provider = (String) cfg.get("search_provider");
    if (provider == null || provider.isEmpty()) provider = "bocha";

    if ("bing".equals(provider))  return bingSearch(query);
    if ("bocha".equals(provider)) return bochaSearch(query);
    return bochaSearch(query);
}

String bingSearch(String query) {
    Map cfg = loadAiConfig();
    String apiKey = (String) cfg.get("search_api_key");
    if (apiKey == null || apiKey.isEmpty())
        return "[搜索失败: 未配置 search_api_key]";

    HttpURLConnection conn = null;
    try {
        String encQuery = URLEncoder.encode(query, "UTF-8");
        URL url = new URL(
            "https://api.bing.microsoft.com/v7.0/search?q=" +
            encQuery + "&count=8&mkt=zh-CN");
        conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty(
            "Ocp-Apim-Subscription-Key", apiKey);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.connect();

        if (conn.getResponseCode() != 200)
            return "[搜索失败: HTTP " + conn.getResponseCode() + "]";

        BufferedReader br = new BufferedReader(
            new InputStreamReader(conn.getInputStream(), "UTF-8"));
        StringBuilder resp = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) resp.append(line);
        br.close();

        JSONObject jResp = new JSONObject(resp.toString());
        JSONArray results = null;
        if (jResp.has("webPages"))
            results = jResp.getJSONObject("webPages").getJSONArray("value");
        if (results == null || results.length() == 0)
            return "[搜索无结果: " + query + "]";

        StringBuilder out = new StringBuilder();
        int count = Math.min(results.length(), 8);
        for (int i = 0; i < count; i++) {
            JSONObject r = results.getJSONObject(i);
            out.append(i + 1);
            out.append(". ");
            out.append(r.optString("snippet", "(无摘要)"));
            out.append("\n");
        }
        return out.toString().trim();
    } catch (Exception e) {
        log("error.txt", "bingSearch: " + e.getMessage());
        return "[搜索异常: " + e.getMessage() + "]";
    } finally {
        if (conn != null) conn.disconnect();
    }
}

String bochaSearch(String query) {
    Map cfg = loadAiConfig();
    String apiKey = (String) cfg.get("search_api_key");
    if (apiKey == null || apiKey.isEmpty())
        return "[搜索失败: 未配置 search_api_key]";

    HttpURLConnection conn = null;
    try {
        URL url = new URL("https://api.bochaai.com/v1/web-search");
        conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);

        JSONObject reqBody = new JSONObject();
        reqBody.put("query", query);
        reqBody.put("count", 8);
        reqBody.put("summary", true);

        byte[] postData = reqBody.toString().getBytes("UTF-8");
        conn.setRequestProperty("Content-Length",
            String.valueOf(postData.length));

        OutputStream os = conn.getOutputStream();
        os.write(postData);
        os.flush();
        os.close();


        if (conn.getResponseCode() != 200)
            return "[搜索失败: HTTP " + conn.getResponseCode() + "]";

        BufferedReader br = new BufferedReader(
            new InputStreamReader(conn.getInputStream(), "UTF-8"));
        StringBuilder resp = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) resp.append(line);
        br.close();

        JSONObject jResp = new JSONObject(resp.toString());
        if (jResp.has("data")) jResp = jResp.getJSONObject("data");
        JSONArray results = null;
        if (jResp.has("webPages"))
            results = jResp.getJSONObject("webPages").getJSONArray("value");
        if (results == null || results.length() == 0)
            return "[搜索无结果: " + query + "]";

        StringBuilder out = new StringBuilder();
        int count = Math.min(results.length(), 8);
        for (int i = 0; i < count; i++) {
            JSONObject r = results.getJSONObject(i);
            out.append(i + 1);
            out.append(". ");
            String summary = r.optString("summary", "");
            if (!summary.isEmpty()) {
                if (summary.length() > 300)
                    summary = summary.substring(0, 300) + "...";
                out.append(summary);
            } else {
                out.append(r.optString("snippet", "(无摘要)"));
            }
            out.append("\n");
        }
        return out.toString().trim();
    } catch (Exception e) {
        log("error.txt", "bochaSearch: " + e.getMessage());
        return "[搜索异常: " + e.getMessage() + "]";
    } finally {
        if (conn != null) conn.disconnect();
    }
}

// ==================== 简单网页抓取 ====================
String fetchWebContentSimple(String urlStr, int maxLen) {
    HttpURLConnection conn = null;
    try {
        URL url = new URL(urlStr);
        conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("User-Agent",
            "Mozilla/5.0 (Android 14; Mobile; rv:130.0) Gecko");
        conn.connect();

        if (conn.getResponseCode() != 200) {
            return "[抓取失败: HTTP " + conn.getResponseCode() + "]";
        }

        BufferedReader br = new BufferedReader(
            new InputStreamReader(conn.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        int total = 0;
        while ((line = br.readLine()) != null && total < maxLen) {
            String t = line.trim();
            if (t.length() == 0) continue;
            int lt = 0;
            for (int i = 0; i < t.length(); i++) {
                if (t.charAt(i) == '<') lt++;
            }
            if (lt > 3) continue;
            sb.append(t + "\n");
            total = total + t.length();
        }
        br.close();
        String result = sb.toString().trim();
        if (result.length() == 0) return "[页面无有效文本]";
        if (result.length() > maxLen) result = result.substring(0, maxLen);
        return result;
    } catch (Exception e) {
        return "[抓取异常: " + e.getMessage() + "]";
    } finally {
        if (conn != null) conn.disconnect();
    }
}

// ==================== 标签解析 ====================
List extractAllMemoryBlocks(String text, String tag) {
    List blocks = new ArrayList();
    if (text == null) return blocks;

    String open = "[" + tag;
    String delim = "]";
    String closePrefix = "[";
    String closeSuffix = tag + "]";
    String slash = "/";

    int idx = 0;
    while ((idx = text.indexOf(open, idx)) != -1) {
        int contentStart = text.indexOf(delim, idx + open.length());
        if (contentStart == -1) break;

        String closeFull = closePrefix + slash + closeSuffix;
        int end = text.indexOf(closeFull, contentStart + 1);
        if (end == -1) break;

        String attrPart =
            text.substring(idx + open.length(), contentStart).trim();
        String content =
            text.substring(contentStart + 1, end).trim();

        if (content.isEmpty()) {
            idx = end + closeFull.length();
            continue;
        }

        String tags = "";
        if (attrPart.toLowerCase().startsWith("tags:")) {
            String tr = attrPart.substring(5).trim();
            int sp = tr.indexOf(" ");
            if (sp > 0) tr = tr.substring(0, sp);
            tags = tr;
        }

        String subjectUin = "";
        int aboutIdx = attrPart.toLowerCase().indexOf("about:");
        if (aboutIdx != -1) {
            String ab = attrPart.substring(aboutIdx + 6).trim();
            int sp = ab.indexOf(" ");
            if (sp > 0) ab = ab.substring(0, sp);
            subjectUin = ab;
        }

        Map m = new HashMap();
        m.put("content", content);
        m.put("tags", tags);
        m.put("subjectUin", subjectUin);
        blocks.add(m);
        idx = end + closeFull.length();
    }
    return blocks;
}

List extractSimpleBlocks(String text, String tag) {
    List blocks = new ArrayList();
    if (text == null) return blocks;

    String open = "[" + tag + "]";
    String closePrefix = "[";
    String slash = "/";
    String closeSuffix = tag + "]";
    String closeFull = closePrefix + slash + closeSuffix;

    int idx = 0;
    while ((idx = text.indexOf(open, idx)) != -1) {
        int end = text.indexOf(closeFull, idx + open.length());
        if (end == -1) break;
        blocks.add(
            text.substring(idx + open.length(), end).trim()
        );
        idx = end + closeFull.length();
    }
    return blocks;
}

boolean hasTag(String text, String tag) {
    if (text == null) return false;
    String open = "[" + tag + "]";
    String closePrefix = "[";
    String slash = "/";
    String closeSuffix = tag + "]";
    String closeFull = closePrefix + slash + closeSuffix;
    int start = text.indexOf(open);
    if (start == -1) return false;
    return text.indexOf(closeFull, start + open.length()) != -1;
}

// ==================== 标签清理（纯 indexOf，无 regex）====================
String cleanAllTags(String rawText) {
    String[] tagNames = {
        "MEMORY", "PUBLIC", "RECALL", "FORGET", "TAGS", "RECENT",
        "SEARCH", "FETCH", "END"
    };

    String result = rawText;
    for (int t = 0; t < tagNames.length; t++) {
        String tag = tagNames[t];
        String open = "[" + tag;
        String closePrefix = "[";
        String slash = "/";
        String closeSuffix = tag + "]";
        String closeFull = closePrefix + slash + closeSuffix;
        while (true) {
            int s = result.indexOf(open);
            if (s == -1) break;
            int e = result.indexOf(closeFull, s);
            if (e == -1) {
                result = result.substring(0, s);
                break;
            }
            result = result.substring(0, s) +
                     result.substring(e + closeFull.length());
        }
    }
    result = result.trim();
    return result;
}

// ==================== /ai memory ====================
void handleAiMemory(Object msg, String args) {
    String senderUin = String.valueOf(msg.userUin);
    String userRole = getRole(senderUin);
    String[] parts = args.split("\\s+");
    String sub = (parts.length > 0) ? parts[0] : "";

    if (sub.isEmpty()) {
        List my = getMyMemories(senderUin, 15);
        List pub = getPublicMemories(5);
        Map pool = getTagPool(senderUin);
        Map pubPool = getPublicTagPool();

        StringBuilder sb = new StringBuilder();
        sb.append("[我的记忆] 共 ");
        sb.append(getMemoryCount(senderUin));
        sb.append(" 条");

        if (!pool.isEmpty()) {
            sb.append("\n[私有标签] ");
            int c = 0;
            for (Object e : pool.entrySet()) {
                Map.Entry en = (Map.Entry) e;
                if (c > 0) sb.append(", ");
                sb.append(en.getKey());
                sb.append("(");
                sb.append(en.getValue());
                sb.append(")");
                c++;
                if (c >= 15) { sb.append(" ..."); break; }
            }
        }

        if (!pubPool.isEmpty()) {
            sb.append("\n[公有标签] ");
            int c = 0;
            for (Object e : pubPool.entrySet()) {
                Map.Entry en = (Map.Entry) e;
                if (c > 0) sb.append(", ");
                sb.append(en.getKey());
                sb.append("(");
                sb.append(en.getValue());
                sb.append(")");
                c++;
                if (c >= 15) { sb.append(" ..."); break; }
            }
        }

        if (my.isEmpty()) {
            sb.append("\n暂无私有记忆");
        } else {
            sb.append("\n(最近 ");
            sb.append(Math.min(my.size(), 15));
            sb.append(" 条):\n");
            for (int i = 0; i < my.size(); i++) {
                Map m = (Map) my.get(i);
                String t = (String) m.get("tags");
                sb.append("#");
                sb.append(m.get("id"));
                if (t != null && !t.isEmpty()) {
                    sb.append(" [");
                    sb.append(t);
                    sb.append("]");
                }
                sb.append(" ");
                sb.append(m.get("content"));
                sb.append("\n");
            }
        }

        if (!pub.isEmpty()) {
            sb.append("\n[公有记忆] 共 ");
            sb.append(pub.size());
            sb.append(" 条:\n");
            for (int i = 0; i < pub.size(); i++) {
                Map m = (Map) pub.get(i);
                String t = (String) m.get("tags");
                sb.append("#");
                sb.append(m.get("id"));
                if (t != null && !t.isEmpty()) {
                    sb.append(" [");
                    sb.append(t);
                    sb.append("]");
                }
                sb.append(" ");
                sb.append(m.get("content"));
                sb.append("\n");
            }
        }
        sendStyledHeader(msg, "INFO", sb.toString());
        return;
    }

    if (sub.equals("search")) {
        if (parts.length < 2) {
            sendStyledHeader(
                msg, "ERROR",
                "用法: /ai memory search " +
                "<kw|tag:x|tags:x,y|pub:xxx|pub tags:x,y>"
            );
            return;
        }
        String kw = parts[1];
        List found;
        if (kw.startsWith("pub tags:")) {
            String[] tl = kw.substring(9).split(",");
            List ta = new ArrayList();
            for (int i = 0; i < tl.length; i++) {
                String t = tl[i].trim();
                if (!t.isEmpty()) ta.add(t);
            }
            found = searchPublicByMultiTags(ta);
        } else if (kw.startsWith("pub:")) {
            found = searchPublicMemories(kw.substring(4));
        } else if (kw.startsWith("tags:")) {
            String[] tl = kw.substring(5).split(",");
            List ta = new ArrayList();
            for (int i = 0; i < tl.length; i++) {
                String t = tl[i].trim();
                if (!t.isEmpty()) ta.add(t);
            }
            found = searchMemoriesByMultiTags(senderUin, ta);
        } else if (kw.startsWith("tag:")) {
            found = searchMemoriesByTag(senderUin, kw.substring(4));
        } else {
            found = searchMemories(senderUin, kw);
        }

        if (found.isEmpty()) {
            sendStyledHeader(
                msg, "INFO",
                "没有匹配 \"" + kw + "\" 的内容"
            );
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append("[搜索 \"");
            sb.append(kw);
            sb.append("\"] 共 ");
            sb.append(found.size());
            sb.append(" 条:\n");
            for (int i = 0; i < found.size(); i++) {
                Map m = (Map) found.get(i);
                String t = (String) m.get("tags");
                sb.append("#");
                sb.append(m.get("id"));
                sb.append(" [");
                sb.append(m.get("scope"));
                sb.append("] ");
                if (t != null && !t.isEmpty()) {
                    sb.append("[");
                    sb.append(t);
                    sb.append("] ");
                }
                sb.append(m.get("content"));
                sb.append("\n");
            }
            sendStyledHeader(msg, "INFO", sb.toString());
        }
        return;
    }

    if (sub.equals("tags")) {
        Map pool = getTagPool(senderUin);
        Map pubPool = getPublicTagPool();
        StringBuilder sb = new StringBuilder();
        sb.append("[私有标签] ");
        sb.append(pool.size());
        sb.append(" 个:\n");
        if (pool.isEmpty()) {
            sb.append("(无)\n");
        } else {
            for (Object e : pool.entrySet()) {
                Map.Entry en = (Map.Entry) e;
                sb.append("  ");
                sb.append(en.getKey());
                sb.append(" (");
                sb.append(en.getValue());
                sb.append("条)\n");
            }
        }
        sb.append("[公有标签] ");
        sb.append(pubPool.size());
        sb.append(" 个:\n");
        if (pubPool.isEmpty()) {
            sb.append("(无)\n");
        } else {
            for (Object e : pubPool.entrySet()) {
                Map.Entry en = (Map.Entry) e;
                sb.append("  ");
                sb.append(en.getKey());
                sb.append(" (");
                sb.append(en.getValue());
                sb.append("条)\n");
            }
        }
        sendStyledHeader(msg, "INFO", sb.toString());
        return;
    }

    if (sub.equals("set")) {
        if (parts.length < 2) {
            sendStyledHeader(
                msg, "ERROR",
                "用法: /ai memory set [tags:x,y] <内容>"
            );
            return;
        }
        String tags = "";
        int cs = 1;
        if (parts[1].startsWith("tags:")) {
            tags = parts[1].substring(5);
            cs = 2;
        }
        if (cs >= parts.length) {
            sendStyledHeader(msg, "ERROR", "缺少内容");
            return;
        }
        StringBuilder ct = new StringBuilder();
        for (int i = cs; i < parts.length; i++) {
            if (i > cs) ct.append(" ");
            ct.append(parts[i]);
        }
        boolean ok = storeMemory(
            senderUin, ct.toString(), tags, "private", senderUin
        );
        if (ok) {
            sendStyledHeader(
                msg, "SUCCESS", "已添加: " + ct.toString()
            );
        } else {
            sendStyledHeader(msg, "ERROR", "添加失败");
        }
        return;
    }

    if (sub.equals("rm")) {
        if (parts.length < 2) {
            sendStyledHeader(
                msg, "ERROR", "用法: /ai memory rm <id>"
            );
            return;
        }
        long id;
        try {
            id = Long.parseLong(parts[1]);
        } catch (Exception e) {
            sendStyledHeader(msg, "ERROR", "id 必须是数字");
            return;
        }
        boolean ok = deleteMemoryById(id, senderUin, userRole);
        if (ok) {
            sendStyledHeader(msg, "SUCCESS", "已删除 #" + id);
        } else {
            sendStyledHeader(msg, "ERROR", "删除失败");
        }
        return;
    }

    if (sub.equals("rebuild")) {
        if (!userRole.equals("ADMIN") &&
            !userRole.equals("ROOT")) {
            sendStyledHeader(msg, "ERROR", "权限不足");
            return;
        }
        rebuildTagPool(senderUin);
        rebuildPublicTagPool();
        sendStyledHeader(msg, "INFO", "标签池已重建");
        return;
    }

    // v10.1: reset 清空全部记忆
    if (sub.equals("reset")) {
        if (!userRole.equals("ROOT")) {
            sendStyledHeader(msg, "ERROR", "权限不足");
            return;
        }
        SQLiteDatabase db = getDb();
        try {
            db.beginTransaction();
            db.delete("memories", null, null);
            db.delete("tag_pool", null, null);
            db.setTransactionSuccessful();
        } catch (Exception e) {
            log("error.txt", "reset memories: " + e.getMessage());
        } finally {
            db.endTransaction();
        }
        tagPoolCache = null;
        tagPoolCacheTime = 0;
        tagPoolCacheUin = "";
        sendStyledHeader(msg, "SUCCESS", "已清空全部记忆和标签池");
        return;
    }

    if (sub.equals("public")) {
        if (parts.length < 2) {
            List pub = getPublicMemories(30);
            Map pubPool = getPublicTagPool();
            StringBuilder sb = new StringBuilder();
            sb.append("[公有记忆] ");
            sb.append(pub.size());
            sb.append(" 条");
            if (!pubPool.isEmpty()) {
                sb.append("\n[标签] ");
                int c = 0;
                for (Object e : pubPool.entrySet()) {
                    Map.Entry en = (Map.Entry) e;
                    if (c > 0) sb.append(", ");
                    sb.append(en.getKey());
                    sb.append("(");
                    sb.append(en.getValue());
                    sb.append(")");
                    c++;
                    if (c >= 15) break;
                }
            }
            if (pub.isEmpty()) {
                sb.append("\n暂无");
            } else {
                sb.append("\n");
                for (int i = 0; i < pub.size(); i++) {
                    Map m = (Map) pub.get(i);
                    String t = (String) m.get("tags");
                    sb.append("#");
                    sb.append(m.get("id"));
                    if (t != null && !t.isEmpty()) {
                        sb.append(" [");
                        sb.append(t);
                        sb.append("]");
                    }
                    sb.append(" ");
                    sb.append(m.get("content"));
                    sb.append("\n");
                }
            }
            sendStyledHeader(msg, "INFO", sb.toString());
            return;
        }
        if (parts[1].equals("set")) {
            if (!userRole.equals("ADMIN") &&
                !userRole.equals("ROOT")) {
                sendStyledHeader(msg, "ERROR", "权限不足");
                return;
            }
            String tags = "";
            int cs = 2;
            if (parts.length > 2 && parts[2].startsWith("tags:")) {
                tags = parts[2].substring(5);
                cs = 3;
            }
            if (cs >= parts.length) {
                sendStyledHeader(
                    msg, "ERROR",
                    "用法: /ai memory public set [tags:x,y] <内容>"
                );
                return;
            }
            StringBuilder ct = new StringBuilder();
            for (int i = cs; i < parts.length; i++) {
                if (i > cs) ct.append(" ");
                ct.append(parts[i]);
            }
            boolean ok = storeMemory(
                "PUBLIC", ct.toString(), tags, "public", senderUin
            );
            if (ok) {
                sendStyledHeader(msg, "SUCCESS", "已添加公有记忆");
            } else {
                sendStyledHeader(msg, "ERROR", "添加失败");
            }
            return;
        }
        if (parts[1].equals("rm")) {
            if (!userRole.equals("ADMIN") &&
                !userRole.equals("ROOT")) {
                sendStyledHeader(msg, "ERROR", "权限不足");
                return;
            }
            if (parts.length < 3) {
                sendStyledHeader(
                    msg, "ERROR",
                    "用法: /ai memory public rm <id>"
                );
                return;
            }
            long id;
            try {
                id = Long.parseLong(parts[2]);
            } catch (Exception e) {
                sendStyledHeader(msg, "ERROR", "id 必须是数字");
                return;
            }
            boolean ok = deleteMemoryById(id, senderUin, userRole);
            if (ok) {
                sendStyledHeader(msg, "SUCCESS", "已删除");
            } else {
                sendStyledHeader(msg, "ERROR", "删除失败");
            }
            return;
        }
        sendStyledHeader(
            msg, "ERROR", "用法: /ai memory public [set|rm]"
        );
        return;
    }

    if (sub.equals("all")) {
        if (!userRole.equals("ADMIN") &&
            !userRole.equals("ROOT")) {
            sendStyledHeader(msg, "ERROR", "权限不足");
            return;
        }
        List all = getAllMemoriesAdmin(50);
        if (all.isEmpty()) {
            sendStyledHeader(msg, "INFO", "数据库为空");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[全部记忆] ");
        sb.append(all.size());
        sb.append(" 条:\n");
        for (int i = 0; i < all.size(); i++) {
            Map m = (Map) all.get(i);
            sb.append("#");
            sb.append(m.get("id"));
            sb.append(" [");
            sb.append(m.get("scope"));
            sb.append("] ");
            if (!"PUBLIC".equals(m.get("uin"))) {
                sb.append("(");
                sb.append(m.get("uin"));
                sb.append(") ");
            }
            String t = (String) m.get("tags");
            if (t != null && !t.isEmpty()) {
                sb.append("[");
                sb.append(t);
                sb.append("] ");
            }
            sb.append(m.get("content"));
            sb.append("\n");
        }
        sendStyledHeader(msg, "INFO", sb.toString());
        return;
    }

    if (sub.equals("admin")) {
        if (!userRole.equals("ADMIN") &&
            !userRole.equals("ROOT")) {
            sendStyledHeader(msg, "ERROR", "权限不足");
            return;
        }
        if (parts.length < 2) {
            sendStyledHeader(
                msg, "ERROR",
                "用法: /ai memory admin search/set/rm"
            );
            return;
        }
        String action = parts[1];

        if (action.equals("search")) {
            if (parts.length < 3) {
                sendStyledHeader(
                    msg, "ERROR",
                    "用法: /ai memory admin search <kw>"
                );
                return;
            }
            List found = searchAllMemoriesAdmin(parts[2], 30);
            if (found.isEmpty()) {
                sendStyledHeader(msg, "INFO", "未找到");
                return;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("[搜索 \"");
            sb.append(parts[2]);
            sb.append("\"] ");
            sb.append(found.size());
            sb.append(" 条:\n");
            for (int i = 0; i < found.size(); i++) {
                Map m = (Map) found.get(i);
                sb.append("#");
                sb.append(m.get("id"));
                sb.append(" [");
                sb.append(m.get("scope"));
                sb.append("] ");
                if (!"PUBLIC".equals(m.get("uin"))) {
                    sb.append("(");
                    sb.append(m.get("uin"));
                    sb.append(") ");
                }
                String t = (String) m.get("tags");
                if (t != null && !t.isEmpty()) {
                    sb.append("[");
                    sb.append(t);
                    sb.append("] ");
                }
                sb.append(m.get("content"));
                sb.append("\n");
            }
            sendStyledHeader(msg, "INFO", sb.toString());
            return;
        }

        if (action.equals("set")) {
            if (parts.length < 4) {
                sendStyledHeader(
                    msg, "ERROR",
                    "用法: /ai memory admin set <UIN> [tags:x,y] <内容>"
                );
                return;
            }
            String target = parts[2];
            String tags = "";
            int cs = 3;
            if (parts.length > 3 && parts[3].startsWith("tags:")) {
                tags = parts[3].substring(5);
                cs = 4;
            }
            if (cs >= parts.length) {
                sendStyledHeader(msg, "ERROR", "缺少内容");
                return;
            }
            StringBuilder ct = new StringBuilder();
            for (int i = cs; i < parts.length; i++) {
                if (i > cs) ct.append(" ");
                ct.append(parts[i]);
            }
            boolean ok = storeMemory(
                target, ct.toString(), tags, "private", target
            );
            if (ok) {
                sendStyledHeader(
                    msg, "SUCCESS",
                    "已为 " + target + " 添加"
                );
            } else {
                sendStyledHeader(msg, "ERROR", "添加失败");
            }
            return;
        }

        if (action.equals("rm")) {
            if (parts.length < 3) {
                sendStyledHeader(
                    msg, "ERROR",
                    "用法: /ai memory admin rm <id>"
                );
                return;
            }
            long id;
            try {
                id = Long.parseLong(parts[2]);
            } catch (Exception e) {
                sendStyledHeader(msg, "ERROR", "id 必须是数字");
                return;
            }
            boolean ok = deleteMemoryById(id, senderUin, userRole);
            if (ok) {
                sendStyledHeader(msg, "SUCCESS", "已删除 #" + id);
            } else {
                sendStyledHeader(msg, "ERROR", "删除失败");
            }
            return;
        }

        sendStyledHeader(
            msg, "ERROR",
            "用法: admin search <kw> | " +
            "admin set <uin> <c> | admin rm <id>"
        );
        return;
    }

    sendStyledHeader(
        msg, "ERROR",
        "未知: /ai memory " + sub +
        "\n可用: search/set/rm/tags/public/all/admin/rebuild/reset"
    );
}

// ==================== /ai 子命令 ====================
void handleAiSet(Object msg, String args) {
    String role = getRole(String.valueOf(msg.userUin));
    if (!role.equals("ADMIN") && !role.equals("ROOT")) {
        sendStyledHeader(msg, "ERROR", "权限不足");
        return;
    }
    String[] parts = args.split("\\s+", 2);
    if (parts.length < 2) {
        sendStyledHeader(
            msg, "ERROR",
            "用法: /ai set <key> <value>\n" +
            "AI: api_key, model, ai_provider, ai_url\n" +
            "上下文: context_ttl, max_turns\n" +
            "搜索: search_provider, search_api_key\n" +
            "其他: system_prompt, show_stats (1 or 0)"

        );
        return;
    }
    String key = parts[0].trim();
    String value = parts[1].trim();
    String[] vk = {
        "api_key", "model", "context_ttl",
        "max_turns", "system_prompt",
        "ai_provider", "ai_url",
        "search_provider", "search_api_key",
        "show_stats"
    };

    boolean valid = false;
    for (int i = 0; i < vk.length; i++) {
        if (vk[i].equals(key)) { valid = true; break; }
    }
    if (!valid) {
        sendStyledHeader(msg, "ERROR", "无效: " + key);
        return;
    }
    if (key.equals("context_ttl") || key.equals("max_turns")) {
        try {
            Integer.parseInt(value);
        } catch (Exception e) {
            sendStyledHeader(msg, "ERROR", key + " 必须是整数");
            return;
        }
    }
    Map cfg = loadAiConfig();
    cfg.put(key, value);
    saveAiConfig(cfg);
    sendStyledHeader(msg, "INFO", "已更新: " + key);
}

void handleAiConfig(Object msg) {
    if (!requireAdminOrRoot(msg)) return;
    Map cfg = loadAiConfig();
    StringBuilder sb = new StringBuilder("[AI 配置]\n");
    for (Object e : cfg.entrySet()) {
        Map.Entry en = (Map.Entry) e;
        String k = (String) en.getKey();
        String v = (String) en.getValue();
        if (k.equals("api_key")) v = maskApiKey(v);
        sb.append(k);
        sb.append(" = ");
        sb.append(v);
        sb.append("\n");
    }
    sb.append("default_account = ");
    sb.append(getDefaultAccount());
    sb.append("\n");
    String persona = loadPersona();
    sb.append("prompt.txt = ");
    if (persona.isEmpty()) {
        sb.append("(未配置)");
    } else {
        sb.append("已加载 (");
        sb.append(persona.length());
        sb.append("字符)");
    }
    sb.append("\n");
    sendStyledHeader(msg, "INFO", sb.toString());
}

// ==================== /websearch ====================
void handleWebSearch(Object msg, String args) {
    String role = getRole(String.valueOf(msg.userUin));
    if (!role.equals("ADMIN") && !role.equals("ROOT")) {
        sendStyledHeader(msg, "ERROR", "权限不足");
        return;
    }
    String[] parts = args.split("\\s+");
    String sub = (parts.length > 0) ? parts[0] : "";

    if (sub.isEmpty() || sub.equals("config")) {
        Map cfg = loadAiConfig();
        String sp = (String) cfg.get("search_provider");
        String sk = (String) cfg.get("search_api_key");
        StringBuilder sb = new StringBuilder();
        sb.append("[搜索配置]\nsearch_provider = ");
        sb.append(sp != null ? sp : "bocha");
        sb.append("\nsearch_api_key = ");
        if (sk != null && !sk.isEmpty()) {
            sb.append(maskApiKey(sk));
        } else {
            sb.append("(未设置)");
        }
        sb.append("\n\n可用: bing, bocha");
        sendStyledHeader(msg, "INFO", sb.toString());
        return;
    }

    if (sub.equals("set")) {
        if (parts.length < 3) {
            sendStyledHeader(msg, "ERROR",
                "用法: /websearch set <key> <value>\n" +
                "可选: search_provider, search_api_key");
            return;
        }
        String key = parts[1].trim();
        String value = parts[2].trim();
        if (!key.equals("search_provider") &&
            !key.equals("search_api_key")) {
            sendStyledHeader(msg, "ERROR", "无效: " + key);
            return;
        }
        if (key.equals("search_provider")) {
            value = value.toLowerCase();
            if (!value.equals("bing") && !value.equals("bocha")) {
                sendStyledHeader(msg, "ERROR",
                    "不支持: " + value + "\n可用: bing, bocha");
                return;
            }
        }
        Map cfg = loadAiConfig();
        cfg.put(key, value);
        saveAiConfig(cfg);
        sendStyledHeader(msg, "INFO", "已更新: " + key);
        return;
    }

    if (sub.equals("test")) {
        if (parts.length < 2) {
            sendStyledHeader(msg, "ERROR",
                "用法: /websearch test <关键词>");
            return;
        }
        String query = "";
        for (int i = 1; i < parts.length; i++) {
            if (i > 1) query = query + " ";
            query = query + parts[i];
        }
        sendMsg(String.valueOf(msg.peerUin),
            "[AI] 正在搜索: " + query, msg.type);
        String result = doWebSearch(query);
        sendStyledHeader(msg, "INFO", result);
        return;
    }

    sendStyledHeader(msg, "ERROR",
        "用法: /websearch [config|set|test]");
}

void handleSetDefaultAccount(Object msg, String type) {
    String role = getRole(String.valueOf(msg.userUin));
    if (!role.equals("ROOT")) {
        sendPermissionDenied(msg);
        return;
    }
    type = type.trim().toLowerCase();
    if (!type.equals("user") && !type.equals("blocked")) {
        sendStyledHeader(
            msg, "ERROR",
            "/setdefaultaccount user/blocked"
        );
        return;
    }
    setDefaultAccountConfig(type);
    String info = "默认账户: " + type;
    if (type.equals("blocked")) {
        info = info + " (白名单)";
    } else {
        info = info + " (开放)";
    }
    sendStyledHeader(msg, "INFO", info);
}

void handleAiForget(Object msg, String keyword) {
    String senderUin = String.valueOf(msg.userUin);
    if (keyword == null || keyword.trim().isEmpty()) {
        sendStyledHeader(
            msg, "ERROR", "用法: /ai forget <关键词>"
        );
        return;
    }
    int d = deleteMemoriesByKeyword(senderUin, keyword.trim());
    if (d > 0) {
        sendStyledHeader(msg, "INFO", "已删除 " + d + " 条");
    } else {
        sendStyledHeader(msg, "INFO", "没有匹配的记忆");
    }
}

String maskApiKey(String key) {
    if (key == null || key.length() < 8) return "(未设置)";
    return key.substring(0, 4) + "****" +
           key.substring(key.length() - 4);
}

// ==================== /ai 主入口 ====================
void handleAi(Object msg, String prompt) {
    long startTime = System.currentTimeMillis();
    int totalPt = 0;
    int totalCt = 0;

    String senderUin = String.valueOf(msg.userUin);
    String peerUin = String.valueOf(msg.peerUin);
    int chatType = msg.type;
    String userRole = getRole(senderUin);
    String senderName = getMemberName(chatType, peerUin, senderUin);

    String trimmed = prompt.trim();

    // v10.1: 会话开关（不受禁用限制）
    if (trimmed.equalsIgnoreCase("off")) {
        if (!userRole.equals("ADMIN") && !userRole.equals("ROOT")) {
            sendStyledHeader(msg, "ERROR", "权限不足");
            return;
        }
        String convKey = peerUin + "_" + chatType;
        addToList(pluginPath + "/config/disabled_conversations.txt", convKey);
        sendStyledHeader(msg, "INFO", "当前会话 AI 已禁用");
        return;
    }
    if (trimmed.equalsIgnoreCase("on")) {
        if (!userRole.equals("ADMIN") && !userRole.equals("ROOT")) {
            sendStyledHeader(msg, "ERROR", "权限不足");
            return;
        }
        String convKey = peerUin + "_" + chatType;
        removeFromList(pluginPath + "/config/disabled_conversations.txt", convKey);
        sendStyledHeader(msg, "INFO", "当前会话 AI 已启用");
        return;
    }
    if (trimmed.equalsIgnoreCase("status")) {
        String convKey = peerUin + "_" + chatType;
        Set disabled = readStringSet(pluginPath + "/config/disabled_conversations.txt");
        if (disabled.contains(convKey)) {
            sendStyledHeader(msg, "INFO", "当前会话: AI 已禁用");
        } else {
            sendStyledHeader(msg, "INFO", "当前会话: AI 已启用");
        }
        return;
    }

    // v10.1: 禁用检查
    String convKey = peerUin + "_" + chatType;
    Set disabled = readStringSet(pluginPath + "/config/disabled_conversations.txt");
    if (disabled.contains(convKey)) {
        sendStyledHeader(msg, "INFO", "当前会话已禁用 AI。管理员: /ai on");
        return;
    }

    if (!canUseAi(senderUin)) {
        String reason;
        if (getRole(senderUin).equals("BLOCKED")) {
            reason = "（已拉黑）";
        } else {
            reason = "（不在白名单）";
        }
        sendStyledHeader(msg, "ERROR", "没有 AI 权限" + reason);
        return;
    }

    if (trimmed.equalsIgnoreCase("clear")) {
        if (!userRole.equals("ADMIN") &&
            !userRole.equals("ROOT")) {
            sendStyledHeader(msg, "ERROR", "权限不足");
            return;
        }
        clearAiContext(peerUin, chatType);
        sendStyledHeader(msg, "INFO", "上下文已清除");
        return;
    }
    if (trimmed.equalsIgnoreCase("config")) {
        handleAiConfig(msg);
        return;
    }
    if (trimmed.startsWith("set ")) {
        handleAiSet(msg, trimmed.substring(4).trim());
        return;
    }
    if (trimmed.equals("memory") ||
        trimmed.startsWith("memory ")) {
        String arg;
        if (trimmed.startsWith("memory ")) {
            arg = trimmed.substring(7).trim();
        } else {
            arg = "";
        }
        handleAiMemory(msg, arg);
        return;
    }
    if (trimmed.startsWith("forget ")) {
        handleAiForget(msg, trimmed.substring(7).trim());
        return;
    }

    Map cfg = loadAiConfig();
    if (((String) cfg.get("api_key")).isEmpty()) {
        sendStyledHeader(
            msg, "ERROR",
            "AI 未启用。管理员: /ai set api_key <key>"
        );
        return;
    }

    getDb();

    List ctx = getAiContext(peerUin, chatType);
    String idPrompt =
        "[UIN:" + senderUin + ", 名称:" + senderName +
        ", 角色:" + userRole + "] " + prompt;
        
    addToContext(ctx, "user", idPrompt);

    String systemPrompt = buildAiSystemPrompt(userRole, senderUin, senderName, chatType, peerUin);

    int maxRounds = 3;
    int round = 0;
    String lastRaw = null;

    while (round < maxRounds) {
        boolean isFinal = (round == maxRounds - 1);
        int mt = isFinal ? 4096 : 512;

        JSONArray msgs = ctxToMessages(ctx);
        Map apiResult = callDeepSeekWithUsage(
            systemPrompt, msgs, mt
        );

        if (apiResult == null) {
            if (lastRaw != null) {
                String cleaned = cleanAllTags(lastRaw);
                if (!cleaned.isEmpty()) {
                    sendFinalReply(msg, lastRaw,
                        startTime, totalPt, totalCt, ctx);
                } else {
                    sendStyledHeader(msg, "ERROR",
                        "AI 服务暂时不可用，请重试");
                }
                return;
            }
            sendStyledHeader(msg, "ERROR", "AI 服务暂时不可用");
            if (!ctx.isEmpty()) ctx.remove(ctx.size() - 1);
            return;
        }


        String aiResp = (String) apiResult.get("response");
         try {
            totalPt = totalPt + Integer.parseInt(
                String.valueOf(apiResult.get("prompt_tokens")));
        } catch (Exception e) { }
        try {
            totalCt = totalCt + Integer.parseInt(
                String.valueOf(apiResult.get("completion_tokens")));
        } catch (Exception e) { }

        lastRaw = aiResp;
        round++;

        // v10.1: [SPLIT] 分段发送
        int splitPos = aiResp.indexOf("[SPLIT]");
        if (splitPos != -1) {
            String beforeSplit = aiResp.substring(0, splitPos);
            String afterSplit = aiResp.substring(splitPos + "[SPLIT]".length());
            String firstPart = cleanAllTags(beforeSplit);
            if (!firstPart.isEmpty()) {
                sendMsg(peerUin, firstPart.trim(), chatType);
            }
            aiResp = afterSplit;
        }

        boolean hasEnd = aiResp.indexOf("[END]") != -1;

        // ====== 提取标签 ======
        List memBlocks   = extractAllMemoryBlocks(aiResp, "MEMORY");
        List pubBlocks   = extractAllMemoryBlocks(aiResp, "PUBLIC");
        List recallBlocks = extractSimpleBlocks(aiResp, "RECALL");
        List forgetBlocks = extractSimpleBlocks(aiResp, "FORGET");
        boolean hasTags   = hasTag(aiResp, "TAGS");
        boolean hasRecent = hasTag(aiResp, "RECENT");
        boolean hasSearch = hasTag(aiResp, "SEARCH")
| aiResp.indexOf("[SEARCH]") != -1;
        boolean hasFetch  = hasTag(aiResp, "FETCH")
| aiResp.indexOf("[FETCH]") != -1;

        boolean hasAction =
            !memBlocks.isEmpty() || !pubBlocks.isEmpty() ||
            !recallBlocks.isEmpty() || !forgetBlocks.isEmpty() ||
            hasTags || hasRecent || hasSearch || hasFetch;
            
        // ====== 非结束轮且后有标签处理：自然语言先发出 ======
        String naturalText = cleanAllTags(aiResp);
        if (!hasEnd && hasAction && !naturalText.isEmpty()) {
            sendMsg(peerUin, naturalText.trim(), chatType);
        }
    
        // ====== 无标签且无 [END]：直接结束 ======
        if (!hasAction && !hasEnd) {
            sendFinalReply(msg, aiResp,
                startTime, totalPt, totalCt, ctx);
            return;
        }

        // ====== 处理所有标签 ======
        StringBuilder result = new StringBuilder();
        result.append("[系统通知] 操作结果:\n");

        // --- MEMORY ---
        for (int i = 0; i < memBlocks.size(); i++) {
            Map block = (Map) memBlocks.get(i);
            String content = (String) block.get("content");
            String tags = (String) block.get("tags");
            if (content == null || content.isEmpty()) continue;
            String subjectUin = (String) block.get("subjectUin");
            if (subjectUin == null || subjectUin.isEmpty()) subjectUin = senderUin;
            boolean ok = storeMemory(
                senderUin, content,
                tags != null ? tags : "", "private", subjectUin
            );
            result.append(ok ? "已记住" : "记忆失败");
            if (tags != null && !tags.isEmpty()) {
                result.append(" [tags:");
                result.append(tags);
                result.append("]");
            }
            if (!subjectUin.equals(senderUin)) {
                result.append(" (转述 ");
                result.append(subjectUin);
                result.append(")");
            }
            result.append(": ");
            result.append(content);
            result.append("\n");
        }

        // --- PUBLIC ---
        for (int i = 0; i < pubBlocks.size(); i++) {
            Map block = (Map) pubBlocks.get(i);
            String content = (String) block.get("content");
            String tags = (String) block.get("tags");
            if (content == null || content.isEmpty()) continue;
            String subjectUin = (String) block.get("subjectUin");
            if (subjectUin == null || subjectUin.isEmpty()) subjectUin = senderUin;
            boolean ok = storeMemory(
                "PUBLIC", content,
                tags != null ? tags : "", "public", subjectUin
            );
            result.append(ok ? "已记入公有" : "公有记忆失败");
            if (!subjectUin.equals(senderUin)) {
                result.append(" (转述 ");
                result.append(subjectUin);
                result.append(")");
            }
            result.append(": ");
            result.append(content);
            result.append("\n");
        }

        // --- TAGS ---
        if (hasTags) {
            Map pool = getTagPool(senderUin);
            Map pubPool = getPublicTagPool();
            if (pool.isEmpty() && pubPool.isEmpty()) {
                result.append("[TAGS] 暂无标签\n");
            } else {
                int total = pool.size() + pubPool.size();
                result.append("[TAGS] 共 ");
                result.append(total);
                result.append(" 个:\n");
                if (!pool.isEmpty()) {
                    result.append("  [私有]\n");
                    for (Object e : pool.entrySet()) {
                        Map.Entry en = (Map.Entry) e;
                        result.append("    ");
                        result.append(en.getKey());
                        result.append(" (");
                        result.append(en.getValue());
                        result.append("条)\n");
                    }
                }
                if (!pubPool.isEmpty()) {
                    result.append("  [公有]\n");
                    for (Object e : pubPool.entrySet()) {
                        Map.Entry en = (Map.Entry) e;
                        result.append("    ");
                        result.append(en.getKey());
                        result.append(" (");
                        result.append(en.getValue());
                        result.append("条)\n");
                    }
                }
            }
        }

        // --- RECALL ---
        for (int i = 0; i < recallBlocks.size(); i++) {
            String kw = ((String) recallBlocks.get(i)).trim();
            if (kw.isEmpty()) continue;

            if (kw.startsWith("all:")) {
                String actualKw = kw.substring(4).trim();
                if (actualKw.isEmpty()) continue;
                List allFound = searchAllMemoriesByKeyword(actualKw, 30);
                if (allFound.isEmpty()) {
                    result.append("[RECALL \"");
                    result.append(kw);
                    result.append("\"] 无匹配记忆\n");
                } else {
                    result.append("[RECALL \"");
                    result.append(kw);
                    result.append("\"] 共 ");
                    result.append(allFound.size());
                    result.append(" 条:\n");
                    for (int j = 0; j < allFound.size(); j++) {
                        Map m = (Map) allFound.get(j);
                        String memUin = (String) m.get("uin");
                        String subjectUin = (String) m.get("subjectUin");
                        if (subjectUin == null) subjectUin = "";
                        String relation;
                        if (subjectUin.isEmpty() || subjectUin.equals(memUin)) {
                            relation = "自述";
                        } else {
                            relation = "转述 " + subjectUin;
                        }
                        result.append("  [");
                        result.append(m.get("scope"));
                        result.append("] #");
                        result.append(m.get("id"));
                        result.append(" (uin:");
                        result.append(memUin);
                        result.append(", ");
                        result.append(relation);
                        result.append("): ");
                        result.append(m.get("content"));
                        result.append("\n");
                    }
                    Set uinsSeen = new HashSet();
                    for (int j = 0; j < allFound.size(); j++) {
                        Map m = (Map) allFound.get(j);
                        String muin = (String) m.get("uin");
                        if (muin != null && !muin.isEmpty()
                            && !"PUBLIC".equals(muin)) {
                            uinsSeen.add(muin);
                        }
                        String suin = (String) m.get("subjectUin");
                        if (suin != null && !suin.isEmpty()
                            && !"PUBLIC".equals(suin)) {
                            uinsSeen.add(suin);
                        }
                    }
                    if (!uinsSeen.isEmpty()) {
                        result.append("\n  [出现人物角色清单]\n");
                        for (Object u : uinsSeen) {
                            String us = (String) u;
                            result.append("    uin:");
                            result.append(us);
                            result.append(" → ");
                            result.append(getRole(us));
                            result.append("\n");
                        }
                    }
                }
                continue;
            }

            boolean isPublic =
                kw.startsWith("pub tags:") ||
                kw.startsWith("pub:");
            String actualKw;
            if (kw.startsWith("pub tags:")) {
                actualKw = kw.substring(9);
            } else if (kw.startsWith("pub:")) {
                actualKw = kw.substring(4);
            } else {
                actualKw = kw;
            }

            if (isPublic) {
                List pubFound;
                if (kw.startsWith("pub tags:")) {
                    String[] tl = actualKw.split(",");
                    List ta = new ArrayList();
                    for (int j = 0; j < tl.length; j++) {
                        String t = tl[j].trim();
                        if (!t.isEmpty()) ta.add(t);
                    }
                    pubFound = searchPublicByMultiTags(ta);
                } else {
                    pubFound = searchPublicMemories(actualKw);
                }
                if (pubFound.isEmpty()) {
                    result.append("[RECALL \"");
                    result.append(kw);
                    result.append("\"] 无匹配公有记忆\n");
                } else {
                    result.append("[RECALL \"");
                    result.append(kw);
                    result.append("\"] 共 ");
                    result.append(pubFound.size());
                    result.append(" 条:\n");
                    for (int j = 0; j < pubFound.size(); j++) {
                        Map m = (Map) pubFound.get(j);
                        result.append("  [公有] #");
                        result.append(m.get("id"));
                        result.append(": ");
                        result.append(m.get("content"));
                        result.append("\n");
                    }
                }
            } else {
                List privFound;
                if (kw.startsWith("tags:")) {
                    String[] tl = kw.substring(5).split(",");
                    List ta = new ArrayList();
                    for (int j = 0; j < tl.length; j++) {
                        String t = tl[j].trim();
                        if (!t.isEmpty()) ta.add(t);
                    }
                    privFound = searchMemoriesByMultiTags(
                        senderUin, ta
                    );
                } else if (kw.startsWith("tag:")) {
                    privFound = searchMemoriesByTag(
                        senderUin, kw.substring(4)
                    );
                } else {
                    privFound = searchMemories(senderUin, kw);
                }
                if (privFound.isEmpty()) {
                    result.append("[RECALL \"");
                    result.append(kw);
                    result.append("\"] 无匹配私有记忆\n");
                } else {
                    result.append("[RECALL \"");
                    result.append(kw);
                    result.append("\"] 共 ");
                    result.append(privFound.size());
                    result.append(" 条:\n");
                    for (int j = 0; j < privFound.size(); j++) {
                        Map m = (Map) privFound.get(j);
                        result.append("  [私有] #");
                        result.append(m.get("id"));
                        result.append(": ");
                        result.append(m.get("content"));
                        result.append("\n");
                    }
                }
            }
        }

        // --- RECENT ---
        if (hasRecent) {
            List recent = getRecentMemories(senderUin, 10);
            if (recent.isEmpty()) {
                result.append("[RECENT] 暂无私有记忆\n");
            } else {
                result.append("[RECENT] 最近 ");
                result.append(recent.size());
                result.append(" 条:\n");
                for (int j = 0; j < recent.size(); j++) {
                    Map m = (Map) recent.get(j);
                    result.append("  #");
                    result.append(m.get("id"));
                    result.append(": ");
                    result.append(m.get("content"));
                    result.append("\n");
                }
            }
        }

        // --- SEARCH ---
        if (hasSearch) {
            sendMsg(peerUin, "[AI] 正在联网搜索中...", chatType);
            List searchBlocks = extractSimpleBlocks(aiResp, "SEARCH");
            if (searchBlocks.isEmpty()) {
                int s = aiResp.indexOf("[SEARCH]");
                if (s != -1) {
                    String raw = aiResp.substring(
                        s + "[SEARCH]".length()).trim();
                    if (!raw.isEmpty()) searchBlocks.add(raw);
                }
            }
            for (int si = 0; si < searchBlocks.size(); si++) {
                String sq = ((String) searchBlocks.get(si)).trim();
                if (sq.isEmpty()) continue;
                String sr = doWebSearch(sq);
                if (sr.length() > 2000) {
                    sr = sr.substring(0, 2000) + "...(已截断)";
                }
                result.append("[SEARCH \"");
                result.append(sq);
                result.append("\"] 搜索结果:\n");
                result.append(sr);
                result.append("\n");
            }
        }

        // --- FETCH ---
        if (hasFetch) {
            List fetchBlocks = extractSimpleBlocks(aiResp, "FETCH");
            if (fetchBlocks.isEmpty()) {
                int s = aiResp.indexOf("[FETCH]");
                if (s != -1) {
                    String raw = aiResp.substring(
                        s + "[FETCH]".length()).trim();
                    if (!raw.isEmpty()) fetchBlocks.add(raw);
                }
            }
            for (int fi = 0; fi < fetchBlocks.size(); fi++) {
                String fu = ((String) fetchBlocks.get(fi)).trim();
                if (fu.isEmpty()) continue;
                String fr = fetchWebContentSimple(fu, 3000);
                if (fr.length() > 2000) {
                    fr = fr.substring(0, 2000) + "...(已截断)";
                }
                result.append("[FETCH \"");
                result.append(fu);
                result.append("\"] 网页内容:\n");
                result.append(fr);
                result.append("\n");
            }
        }

        // --- FORGET ---
        for (int i = 0; i < forgetBlocks.size(); i++) {
            String kw = ((String) forgetBlocks.get(i)).trim();
            if (kw.isEmpty()) continue;
            int deleted = deleteMemoriesByKeyword(senderUin, kw);
            result.append("[FORGET \"");
            result.append(kw);
            result.append("\"] 已删除 ");
            result.append(deleted);
            result.append(" 条\n");
        }

        // ====== [END] → 最终回复 ======
        if (hasEnd) {
            sendFinalReply(msg, aiResp,
                startTime, totalPt, totalCt, ctx);
            return;
        }

        // ====== 最后一轮兜底 ======
        if (isFinal) {
            addToContext(ctx, "user", result.toString());
            addToContext(ctx, "system",
                "请直接输出文本回复，不要使用任何标签。"
                + "用自然语言把结果告诉用户，末尾加 [END]。");

            JSONArray msgs2 = ctxToMessages(ctx);
            Map apiResult2 = callDeepSeekWithUsage(
                systemPrompt, msgs2, 8192);
            if (apiResult2 != null) {
                String aiResp2 = (String) apiResult2.get("response");
                try {
                    totalPt = totalPt + Integer.parseInt(
                        String.valueOf(apiResult2.get("prompt_tokens")));
                } catch (Exception e) { }
                try {
                    totalCt = totalCt + Integer.parseInt(
                        String.valueOf(apiResult2.get("completion_tokens")));
                } catch (Exception e) { }
                sendFinalReply(msg, aiResp2,
                    startTime, totalPt, totalCt, ctx);
            } else {
                String cleaned = cleanAllTags(aiResp);
                if (!cleaned.isEmpty()) {
                    sendFinalReply(msg, aiResp,
                        startTime, totalPt, totalCt, ctx);
                } else {
                    sendStyledHeader(msg, "ERROR",
                        "AI 服务暂时不可用，请重试");
                }
            }
            return;
        }

        addToContext(ctx, "user", result.toString());
    }

    if (lastRaw != null) {
        sendFinalReply(
            msg, lastRaw,
            startTime, totalPt, totalCt, ctx
        );
    } else {
        sendStyledHeader(msg, "AI", "(无可用回复)");
    }
}

// ==================== 最终回复发送 ====================
void sendFinalReply(
    Object msg, String rawText,
    long startTime, int pt, int ct, List ctx
) {
    String cleaned = cleanAllTags(rawText);

    if (cleaned.isEmpty()) {
        boolean hasMem =
            rawText.indexOf("[MEMORY") != -1 ||
            rawText.indexOf("[PUBLIC") != -1;
        if (hasMem) {
            cleaned = "好的，我已经记住了！";
        } else {
            cleaned = "好的。";
        }
    }

    String senderUin = String.valueOf(msg.userUin);
    addToContext(ctx, "assistant", cleaned);

    // v10.1: 统计合并到一条消息
    StringBuilder finalMsg = new StringBuilder(cleaned);
    if ("1".equals(getAiConfig("show_stats"))) {
        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
        if (elapsed < 1) elapsed = 1;
        finalMsg.append("\n----------\n");
        finalMsg.append("Time:");
        finalMsg.append(getCurrentTime());
        finalMsg.append("\nUser:");
        finalMsg.append(senderUin);
        finalMsg.append("(");
        finalMsg.append(getRole(senderUin));
        finalMsg.append(")");
        if (pt > 0) {
            finalMsg.append("\nTokenIn:");
            finalMsg.append(pt);
        }
        if (ct > 0) {
            finalMsg.append("\nTokenOut:");
            finalMsg.append(ct);
        }
        finalMsg.append("\nThinkTime:");
        finalMsg.append(elapsed);
        finalMsg.append("s");
    }
    sendMsg(String.valueOf(msg.peerUin), finalMsg.toString(), msg.type);
    writeLog(senderUin, "/ai (OK)");
}

// ==================== 消息工具 ====================
void sendStyledHeader(Object msg, String status, String msgText) {
    String senderUin = String.valueOf(msg.userUin);
    String role = getRole(senderUin);
    StringBuilder sb = new StringBuilder();
    sb.append("[");
    sb.append(status);
    sb.append("] ");
    sb.append(getCurrentTime());
    sb.append("\n[USER] ");
    sb.append(senderUin);
    sb.append(" (");
    sb.append(role);
    sb.append(")\n[MSG] ");
    sb.append(msgText);
    sendMsg(String.valueOf(msg.peerUin), sb.toString(), msg.type);
}

void sendPermissionDenied(Object msg) {
    sendStyledHeader(msg, "ERROR", "权限不足");
}

boolean requireAdminOrRoot(Object msg) {
    String role = getRole(String.valueOf(msg.userUin));
    if (!role.equals("ADMIN") && !role.equals("ROOT")) {
        sendPermissionDenied(msg);
        return false;
    }
    return true;
}

String extractUin(String msg) {
    if (msg == null) return null;
    int atIdx = msg.indexOf('@');
    if (atIdx != -1) {
        String sub = msg.substring(atIdx + 1).trim();
        int sp = sub.indexOf(' ');
        if (sp != -1) sub = sub.substring(0, sp);
        if (sub.matches("[0-9]+")) return sub;
    }
    return null;
}

boolean isNumeric(String s) {
    return s != null && s.matches("[0-9]+");
}

// ==================== 生命周期 ====================
public void onDestroy() {
    for (Object key : aiContexts.keySet()) {
        aiContexts.remove(key);
    }
    closeSharedDb();
    new Handler(Looper.getMainLooper())
        .removeCallbacks(this::closeSharedDb);
}

// ==================== 消息路由 ====================
public void onMsg(Object msg) {
    if (msg == null) return;
    String text = msg.msg;
    if (text == null) return;
    String senderUin = String.valueOf(msg.userUin);
    String peerUin = String.valueOf(msg.peerUin);
    int chatType = msg.type;
    String trimmed = text.trim();

    if (trimmed.startsWith("/ai")) {
        String aiArg = trimmed.substring(3).trim();
        if (aiArg.isEmpty()) {
            sendStyledHeader(
                msg, "ERROR",
                "/ai <内容> -- AI 对话\n" +
                "/ai memory -- 记忆+标签\n" +
                "/ai memory search <kw|tag:x|tags:x,y|pub:xxx>\n" +
                "/ai memory set [tags:x,y] <c> / rm <id>\n" +
                "/ai memory tags/public/all/admin\n" +
                "/ai forget <kw> / clear / config / set\n" +
                "/ai off / on / status"
            );
            return;
        }
        handleAi(msg, aiArg);
        return;
    }

    if (trimmed.startsWith("/websearch")) {
        String wsArg = trimmed.substring(11).trim();
        handleWebSearch(msg, wsArg);
        return;
    }

    if (trimmed.startsWith("/setdefaultaccount")) {
        String arg = trimmed.substring(19).trim();
        if (arg.isEmpty()) {
            sendStyledHeader(
                msg, "ERROR",
                "用法: /setdefaultaccount user/blocked\n" +
                "当前: " + getDefaultAccount()
            );
            return;
        }
        handleSetDefaultAccount(msg, arg);
        return;
    }

    if (!trimmed.startsWith("/") || trimmed.length() < 2) return;

    String[] tokens = trimmed.split("\\s+");
    String cmd = tokens[0];

    if (cmd.equals("/whoami")) {
        if (tokens.length > 1) {
            sendStyledHeader(
                msg, "ERROR", "/whoami 不需要参数"
            );
            return;
        }
        String role = getRole(senderUin);
        StringBuilder info = new StringBuilder();
        info.append("角色: ");
        info.append(role);
        info.append("\n记忆: ");
        info.append(getMemoryCount(senderUin));
        info.append(" 条\n标签: ");
        info.append(getTagPool(senderUin).size());
        info.append(" 个\nAI权限: ");
        info.append(canUseAi(senderUin) ? "可用" : "不可用");
        info.append("\n默认账户: ");
        info.append(getDefaultAccount());
        sendStyledHeader(msg, "INFO", info.toString());
        return;
    }

    if (cmd.equals("/help")) {
        if (tokens.length > 1) {
            sendStyledHeader(
                msg, "ERROR", "/help 不需要参数"
            );
            return;
        }
        String role = getRole(senderUin);
        String da = getDefaultAccount();
        StringBuilder h = new StringBuilder();
        h.append("鉴存-LMA v10.1\n\n");
        h.append("/ai <内容> -- AI 对话\n");
        h.append("/ai status -- 查看当前会话AI状态\n");
        h.append("/ai memory -- 查看记忆+标签\n");
        h.append("/ai memory search <kw|tag:x|tags:x,y|pub:xxx|pub tags:x,y> -- 搜索\n");
        h.append("/ai memory set [tags:x,y] <内容> / rm <id> -- 增删\n");
        h.append("/ai memory tags -- 查看标签池\n");
        h.append("/ai forget <kw> -- 批量删除\n");
        h.append("/whoami / /help\n");
        if (role.equals("ADMIN") || role.equals("ROOT")) {
            h.append("\n[管理]\n");
            h.append("/ai off / /ai on -- 禁用/启用当前会话\n");
            h.append("/ai config / /ai set <k> <v>\n");
            h.append("/ai clear\n");
            h.append("/ai memory public -- 查看公有记忆\n");
            h.append("/ai memory public set [tags:x,y] <内容> / rm <id>\n");
            h.append("/ai memory all -- 全部记忆\n");
            h.append("/ai memory admin search/set/rm\n");
            h.append("/ai memory rebuild\n");
            h.append("/websearch [config|set|test]\n");
            h.append("/block @某人 / /block list\n");
            h.append("/user @某人 / /user list\n");
            h.append("/log\n");
        }
        if (role.equals("ROOT")) {
            h.append("/ai memory reset -- 清空全部记忆\n");
            h.append("/admin @某人 / /admin list\n");
            h.append("/setdefaultaccount user/blocked\n");
        }
        h.append("\n默认账户: ");
        h.append(da);
        if (da.equals("blocked")) {
            h.append(" (白名单)");
        } else {
            h.append(" (开放)");
        }
        h.append("\n- Author: CNYiJieqwq异界, xn--4gqx06mbqk");
        sendStyledHeader(msg, "INFO", h.toString());
        return;
    }

    String role = getRole(senderUin);

    if (role.equals("BLOCKED")) {
        if (!cmd.equals("/whoami") &&
            !cmd.equals("/help") &&
            !cmd.equals("/ai")) {
            sendPermissionDenied(msg);
            return;
        }
    }

    if (cmd.equals("/log")) {
        if (!requireAdminOrRoot(msg)) return;
        String p = pluginPath + "/config/log.txt";
        if (!new File(p).exists()) {
            sendStyledHeader(msg, "INFO", "日志已创建");
        } else {
            sendFile(peerUin, p, chatType);
        }
        return;
    }

    if (cmd.equals("/admin")) {
        if (!role.equals("ROOT")) {
            sendPermissionDenied(msg);
            return;
        }
        
    if (tokens.length >= 2 && tokens[1].equals("list")) {
            Set admins = readStringSet(pluginPath + "/config/admins.txt");
        if (admins.isEmpty()) {
                sendStyledHeader(msg, "INFO", "管理员列表为空");
            } else {
                StringBuilder sb = new StringBuilder();
                sb.append("管理员列表 (");
                sb.append(admins.size());
                sb.append("人):\n");
                for (Object a : admins) {
                    sb.append("  ");
                    sb.append(a);
                    sb.append("\n");
                }
                sendStyledHeader(msg, "INFO", sb.toString().trim());
            }
            return;
        }

        if (tokens.length < 2) {
            sendStyledHeader(
                msg, "ERROR",
                "用法: /admin @某人 或 /admin <UID>"
            );
            return;
        }
        
        String t = extractUin(trimmed);
        if (t == null && isNumeric(tokens[1])) t = tokens[1];
        if (t == null) {
            sendStyledHeader(
                msg, "ERROR", "请 @用户 或提供 UID"
            );
            return;
        }
        addToList(pluginPath + "/config/admins.txt", t);
        removeFromList(pluginPath + "/config/blocked.txt", t);
        sendStyledHeader(msg, "SUCCESS", "已授予管理员: " + t);
        return;
    }

    if (cmd.equals("/block")) {
         if (!requireAdminOrRoot(msg)) return;
         if (tokens.length >= 2 && tokens[1].equals("list")) {
            Set blocked = readStringSet(pluginPath + "/config/blocked.txt");
            StringBuilder sb = new StringBuilder();
            if (blocked.isEmpty()) {
                sb.append("黑名单为空");
            } else {
                sb.append("黑名单 (");
                sb.append(blocked.size());
                sb.append("人):\n");
                for (Object b : blocked) {
                    sb.append("  ");
                    sb.append(b);
                    sb.append("\n");
                }
            }
            if (getDefaultAccount().equals("blocked")) {
                sb.append("\n当前默认账户: blocked，新用户自动加入黑名单");
            }
            sendStyledHeader(msg, "INFO", sb.toString().trim());
            return;
        }
        if (tokens.length < 2) {
            sendStyledHeader(
                msg, "ERROR",
                "用法: /block @某人 或 /block <UID>"
            );
            return;
        }
        String t = extractUin(trimmed);
        if (t == null && isNumeric(tokens[1])) t = tokens[1];
        if (t == null) {
            sendStyledHeader(
                msg, "ERROR", "请 @用户 或提供 UID"
            );
            return;
        }
        if (t.equals(myUin)) {
            sendStyledHeader(msg, "ERROR", "不能拉黑宿主");
            return;
        }
        String tr = getRole(t);
        if (role.equals("ADMIN") &&
            (tr.equals("ADMIN") || tr.equals("ROOT"))) {
            sendStyledHeader(msg, "ERROR", "不能拉黑 " + tr);
            return;
        }
        removeFromList(pluginPath + "/config/admins.txt", t);
        addToList(pluginPath + "/config/blocked.txt", t);
        removeFromList(pluginPath + "/config/users.txt", t);
        sendStyledHeader(msg, "SUCCESS", "已拉黑: " + t);
        return;
    }

    if (cmd.equals("/user")) {
        if (!requireAdminOrRoot(msg)) return;
        if (tokens.length >= 2 && tokens[1].equals("list")) {
            Set users = readStringSet(pluginPath + "/config/users.txt");
            StringBuilder sb = new StringBuilder();
            if (users.isEmpty()) {
                sb.append("用户白名单为空");
            } else {
                sb.append("用户白名单 (");
                sb.append(users.size());
                sb.append("人):\n");
                for (Object u : users) {
                    sb.append("  ");
                    sb.append(u);
                    sb.append("\n");
                }
            }
            if (getDefaultAccount().equals("user")) {
                sb.append("\n当前默认账户: user，新用户无需加入白名单");
            }
            sendStyledHeader(msg, "INFO", sb.toString().trim());
            return;
        }
        if (tokens.length < 2) {
            sendStyledHeader(
                msg, "ERROR",
                "用法: /user @某人 或 /user <UID>"
            );
            return;
        }
        String t = extractUin(trimmed);
        if (t == null && isNumeric(tokens[1])) t = tokens[1];
        if (t == null) {
            sendStyledHeader(
                msg, "ERROR", "请 @用户 或提供 UID"
            );
            return;
        }
        if (t.equals(myUin)) {
            sendStyledHeader(msg, "ERROR", "不能降级宿主");
            return;
        }
        String tr = getRole(t);
        if (role.equals("ADMIN") &&
            (tr.equals("ADMIN") || tr.equals("ROOT"))) {
            sendStyledHeader(msg, "ERROR", "不能降级 " + tr);
            return;
        }
        removeFromList(pluginPath + "/config/admins.txt", t);
        removeFromList(pluginPath + "/config/blocked.txt", t);
        if (getDefaultAccount().equals("blocked")) {
            addToList(pluginPath + "/config/users.txt", t);
            sendStyledHeader(
                msg, "SUCCESS",
                "已降级并加入白名单: " + t
            );
        } else {
            sendStyledHeader(
                msg, "SUCCESS",
                "已降级为 USER: " + t
            );
        }
        return;
    }

    new Handler(Looper.getMainLooper())
        .removeCallbacks(this::closeSharedDb);
    new Handler(Looper.getMainLooper())
        .postDelayed(this::closeSharedDb, 30000);
}

/*
 * ======================== 鉴存-LMA v10.1 ========================
 *
 * AI 长期记忆智能体 — 先鉴别，再存储
 *
 * 功能:
 *   - prompt.txt 自定义人设（热更新）
 *   - 对话模式（思考回合 max_tokens=256，最终回合 4096）
 *   - 私有/公有 Tag 池 + 多标签并行检索
 *   - 全库搜索 [RECALL] all: + 出现人物角色清单
 *   - 自述/转述标记 (subject_uin + about: 属性)
 *   - 会话感知（群聊/私聊 + 群名）
 *   - 记忆写入策略（角色权威层级 + 冲突检测引导）
 *   - [SPLIT] 分段发送
 *   - 会话 AI 开关 (/ai off/on/status)
 *   - 统计信息合并到一条消息
 *   - DeepSeek Reasoner (R1) 推理模型
 *   - 白名单模式（/setdefaultaccount blocked + users.txt）
 *
 * 作者: CNYiJieqwq, xn--4gqx06mbqk
 * 版本: 10.1
 * 最后更新: 2026-05-20
 */