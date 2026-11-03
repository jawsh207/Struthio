package app.struthio.nostr.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.struthio.nostr.core.Event
import app.struthio.nostr.core.Hex
import app.struthio.nostr.core.Kind
import app.struthio.nostr.core.Zap
import app.struthio.nostr.core.parseTags
import app.struthio.nostr.core.parseZapReceipt
import app.struthio.nostr.core.tagsToJson
import app.struthio.nostr.core.threadRef
import app.struthio.nostr.core.value
import app.struthio.nostr.core.values
import org.json.JSONArray

data class Stats(
    val comments: Int = 0,
    val boosts: Int = 0,
    val reactions: Int = 0,
    val zapMsats: Long = 0,
    val mineReacted: Boolean = false,
    val mineBoosted: Boolean = false,
    val mineZapped: Boolean = false,
)

data class Dm(val id: String, val room: String, val sender: String, val createdAt: Long, val content: String)

data class Room(val key: String, val members: List<String>, val last: Dm)

data class Alert(val event: Event, val actor: String, val msats: Long, val comment: String)

/** Local cache on the platform's SQLite: the first thing every screen shows. */
class Store(context: Context) : SQLiteOpenHelper(context, "struthio.db", null, 1) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE events(
                rowid INTEGER PRIMARY KEY, id TEXT UNIQUE NOT NULL, pubkey TEXT NOT NULL,
                created_at INTEGER NOT NULL, kind INTEGER NOT NULL, tags TEXT NOT NULL,
                content TEXT NOT NULL, sig TEXT NOT NULL, d TEXT, root TEXT, parent TEXT, relay TEXT)""",
        )
        db.execSQL("CREATE INDEX events_author ON events(pubkey, kind, created_at DESC)")
        db.execSQL("CREATE INDEX events_kind ON events(kind, created_at DESC)")
        db.execSQL("CREATE INDEX events_root ON events(root)")
        db.execSQL("CREATE TABLE refs(id TEXT NOT NULL, name TEXT NOT NULL, value TEXT NOT NULL)")
        db.execSQL("CREATE INDEX refs_value ON refs(value, name)")
        db.execSQL("CREATE INDEX refs_id ON refs(id)")
        db.execSQL("CREATE VIRTUAL TABLE notes_fts USING fts4(content=\"events\", content)")
        db.execSQL(
            """CREATE TABLE zaps(
                receipt TEXT PRIMARY KEY, target TEXT, recipient TEXT NOT NULL, sender TEXT NOT NULL,
                msats INTEGER NOT NULL, provider TEXT NOT NULL, comment TEXT NOT NULL, created_at INTEGER NOT NULL)""",
        )
        db.execSQL("CREATE INDEX zaps_target ON zaps(target)")
        db.execSQL("CREATE TABLE providers(pubkey TEXT PRIMARY KEY, nostr_pubkey TEXT, checked_at INTEGER NOT NULL)")
        db.execSQL(
            """CREATE TABLE dms(
                id TEXT PRIMARY KEY, room TEXT NOT NULL, sender TEXT NOT NULL,
                created_at INTEGER NOT NULL, content TEXT NOT NULL)""",
        )
        db.execSQL("CREATE INDEX dms_room ON dms(room, created_at DESC)")
        db.execSQL("CREATE TABLE wraps(id TEXT PRIMARY KEY)")
        db.execSQL("CREATE TABLE follows(pubkey TEXT PRIMARY KEY)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** Stores a batch and returns what is new. */
    fun insert(batch: List<Pair<Event, String>>): List<Event> {
        val added = ArrayList<Event>()
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((event, relay) in batch) if (insertOne(db, event, relay)) added += event
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return added
    }

    private fun insertOne(db: SQLiteDatabase, e: Event, relay: String): Boolean {
        if (Kind.isEphemeral(e.kind) || e.kind == Kind.GIFT_WRAP) return false
        if (Kind.isReplaceable(e.kind) || Kind.isAddressable(e.kind)) {
            val d = if (Kind.isAddressable(e.kind)) e.tags.value("d") ?: "" else null
            val args = if (d == null) arrayOf(e.pubkey, e.kind.toString()) else arrayOf(e.pubkey, e.kind.toString(), d)
            val where = "pubkey=? AND kind=?" + if (d == null) "" else " AND d=?"
            db.rawQuery("SELECT created_at, id FROM events WHERE $where", args).use {
                if (it.moveToFirst()) {
                    val time = it.getLong(0)
                    if (time > e.createdAt || (time == e.createdAt && it.getString(1) <= e.id)) return false
                }
            }
            db.delete("refs", "id IN (SELECT id FROM events WHERE $where)", args)
            db.delete("events", where, args)
        }
        val ref = threadRef(e)
        val values = ContentValues(12).apply {
            put("id", e.id)
            put("pubkey", e.pubkey)
            put("created_at", e.createdAt)
            put("kind", e.kind)
            put("tags", tagsToJson(e.tags))
            put("content", e.content)
            put("sig", e.sig)
            if (Kind.isAddressable(e.kind)) put("d", e.tags.value("d") ?: "")
            put("root", ref.root)
            put("parent", ref.parent)
            put("relay", relay)
        }
        val rowid = db.insertWithOnConflict("events", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        if (rowid < 0) return false

        fun addRef(name: String, value: String) {
            db.execSQL("INSERT INTO refs(id, name, value) VALUES(?,?,?)", arrayOf(e.id, name, value))
        }
        when (e.kind) {
            Kind.NOTE, Kind.COMMENT -> {
                setOfNotNull(ref.root, ref.parent).forEach { addRef("e", it) }
                e.tags.values("q").forEach { addRef("q", it) }
            }
            Kind.REPOST, Kind.GENERIC_REPOST, Kind.REACTION -> e.tags.values("e").lastOrNull()?.let { addRef("e", it) }
        }
        if (e.kind in NOTIFYING) e.tags.values("p").distinct().forEach { addRef("p", it) }
        if (e.kind == Kind.NOTE) {
            db.execSQL("INSERT INTO notes_fts(docid, content) VALUES(?,?)", arrayOf(rowid, e.content))
        }
        if (e.kind == Kind.ZAP_RECEIPT) parseZapReceipt(e)?.let { insertZap(db, it) }
        if (e.kind == Kind.DELETION) applyDeletion(db, e)
        return true
    }

    private fun insertZap(db: SQLiteDatabase, zap: Zap) {
        val values = ContentValues(8).apply {
            put("receipt", zap.receipt)
            put("target", zap.target)
            put("recipient", zap.recipient)
            put("sender", zap.sender)
            put("msats", zap.msats)
            put("provider", zap.provider)
            put("comment", zap.comment)
            put("created_at", zap.createdAt)
        }
        db.insertWithOnConflict("zaps", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    private fun applyDeletion(db: SQLiteDatabase, deletion: Event) {
        for (id in deletion.tags.values("e")) {
            db.rawQuery("SELECT rowid, pubkey, content, kind FROM events WHERE id=?", arrayOf(id)).use {
                if (it.moveToFirst() && it.getString(1) == deletion.pubkey) {
                    if (it.getInt(3) == Kind.NOTE) {
                        db.execSQL("DELETE FROM notes_fts WHERE docid=?", arrayOf(it.getLong(0)))
                    }
                    db.delete("events", "id=?", arrayOf(id))
                    db.delete("refs", "id=?", arrayOf(id))
                }
            }
        }
    }

    private fun Cursor.event() = Event(
        getString(0), getString(1), getLong(2), getInt(3), parseTags(JSONArray(getString(4))), getString(5), getString(6),
    )

    private inline fun events(sql: String, args: Array<String> = emptyArray(), block: (Event) -> Unit = {}): List<Event> {
        val out = ArrayList<Event>()
        readableDatabase.rawQuery(sql, args).use {
            while (it.moveToNext()) {
                val event = it.event()
                out += event
                block(event)
            }
        }
        return out
    }

    fun event(id: String): Event? = events("SELECT $COLUMNS FROM events WHERE id=?", arrayOf(id)).firstOrNull()

    fun relayOf(id: String): String? =
        readableDatabase.rawQuery("SELECT relay FROM events WHERE id=?", arrayOf(id)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    fun latest(pubkey: String, kind: Int): Event? = events(
        "SELECT $COLUMNS FROM events WHERE pubkey=? AND kind=? ORDER BY created_at DESC LIMIT 1",
        arrayOf(pubkey, kind.toString()),
    ).firstOrNull()

    fun latestOfKind(pubkeys: Collection<String>, kind: Int): List<Event> {
        if (pubkeys.isEmpty()) return emptyList()
        return pubkeys.chunked(500).flatMap { chunk ->
            val marks = chunk.joinToString(",") { "?" }
            events("SELECT $COLUMNS FROM events WHERE kind=? AND pubkey IN ($marks)", arrayOf(kind.toString()) + chunk)
        }
    }

    fun followingFeed(limit: Int, until: Long): List<Event> = events(
        """SELECT $COLUMNS FROM events WHERE kind IN (1,6) AND root IS NULL AND created_at < ?
           AND pubkey IN (SELECT pubkey FROM follows) ORDER BY created_at DESC LIMIT ?""",
        arrayOf(until.toString(), limit.toString()),
    )

    fun authorNotes(pubkey: String, limit: Int, until: Long): List<Event> = events(
        "SELECT $COLUMNS FROM events WHERE pubkey=? AND kind IN (1,6) AND created_at < ? ORDER BY created_at DESC LIMIT ?",
        arrayOf(pubkey, until.toString(), limit.toString()),
    )

    fun thread(root: String): List<Event> = events(
        "SELECT $COLUMNS FROM events WHERE (id=? OR root=?) AND kind IN (1,1111) ORDER BY created_at",
        arrayOf(root, root),
    )

    fun searchNotes(query: String, limit: Int): List<Event> {
        val terms = query.split(Regex("\\s+")).filter { it.isNotBlank() }
            .joinToString(" ") { "\"" + it.replace("\"", "") + "*\"" }
        if (terms.isEmpty()) return emptyList()
        return events(
            """SELECT $COLUMNS FROM events WHERE rowid IN (SELECT docid FROM notes_fts WHERE notes_fts MATCH ?)
               ORDER BY created_at DESC LIMIT ?""",
            arrayOf(terms, limit.toString()),
        )
    }

    fun setFollows(pubkeys: Collection<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("follows", null, null)
            pubkeys.forEach { db.execSQL("INSERT OR IGNORE INTO follows(pubkey) VALUES(?)", arrayOf(it)) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun stats(ids: List<String>, me: String?): Map<String, Stats> {
        if (ids.isEmpty()) return emptyMap()
        val marks = ids.joinToString(",") { "?" }
        val who = me ?: ""
        val out = HashMap<String, Stats>()
        ids.forEach { out[it] = Stats() }
        readableDatabase.rawQuery(
            """SELECT r.value, r.name, e.kind, COUNT(DISTINCT e.pubkey), MAX(e.pubkey=?)
               FROM refs r JOIN events e ON e.id=r.id
               WHERE r.value IN ($marks) AND r.name IN ('e','q') AND NOT (e.kind=7 AND e.content='-')
               GROUP BY r.value, r.name, e.kind""",
            arrayOf(who) + ids,
        ).use {
            while (it.moveToNext()) {
                val id = it.getString(0)
                val count = it.getInt(3)
                val mine = it.getInt(4) == 1
                val s = out.getValue(id)
                out[id] = when {
                    it.getString(1) == "q" -> s.copy(boosts = s.boosts + count)
                    it.getInt(2) == Kind.REACTION -> s.copy(reactions = s.reactions + count, mineReacted = s.mineReacted || mine)
                    it.getInt(2) == Kind.REPOST || it.getInt(2) == Kind.GENERIC_REPOST ->
                        s.copy(boosts = s.boosts + count, mineBoosted = s.mineBoosted || mine)
                    else -> s.copy(comments = s.comments + count)
                }
            }
        }
        readableDatabase.rawQuery(
            """SELECT z.target, SUM(z.msats), MAX(z.sender=?) FROM zaps z JOIN providers p
               ON p.pubkey=z.recipient AND p.nostr_pubkey=z.provider
               WHERE z.target IN ($marks) GROUP BY z.target""",
            arrayOf(who) + ids,
        ).use {
            while (it.moveToNext()) {
                val id = it.getString(0)
                out[id] = out.getValue(id).copy(zapMsats = it.getLong(1), mineZapped = it.getInt(2) == 1)
            }
        }
        return out
    }

    /** Zap recipients whose LNURL provider key is unknown or a week old (NIP-57 Appendix F). */
    fun unresolvedRecipients(ids: List<String>, now: Long): List<String> {
        if (ids.isEmpty()) return emptyList()
        val marks = ids.joinToString(",") { "?" }
        val out = ArrayList<String>()
        readableDatabase.rawQuery(
            """SELECT DISTINCT z.recipient FROM zaps z LEFT JOIN providers p ON p.pubkey=z.recipient
               WHERE z.target IN ($marks) AND (p.pubkey IS NULL OR p.checked_at < ?)""",
            ids.toTypedArray() + (now - 7 * 86400).toString(),
        ).use { while (it.moveToNext()) out += it.getString(0) }
        return out
    }

    fun setProvider(pubkey: String, nostrPubkey: String?, now: Long) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO providers(pubkey, nostr_pubkey, checked_at) VALUES(?,?,?)",
            arrayOf(pubkey, nostrPubkey, now),
        )
    }

    fun alerts(me: String, muted: Set<String>, limit: Int): List<Alert> {
        val out = ArrayList<Alert>()
        val zaps = HashMap<String, Triple<String, Long, String>>()
        readableDatabase.rawQuery(
            """SELECT z.receipt, z.sender, z.msats, z.comment FROM zaps z JOIN providers p
               ON p.pubkey=z.recipient AND p.nostr_pubkey=z.provider WHERE z.recipient=?""",
            arrayOf(me),
        ).use { while (it.moveToNext()) zaps[it.getString(0)] = Triple(it.getString(1), it.getLong(2), it.getString(3)) }
        events(
            """SELECT $COLUMNS FROM events WHERE id IN (SELECT id FROM refs WHERE name='p' AND value=?)
               AND pubkey != ? ORDER BY created_at DESC LIMIT ?""",
            arrayOf(me, me, limit.toString()),
        ) { e ->
            if (e.kind == Kind.ZAP_RECEIPT) {
                zaps[e.id]?.let { (sender, msats, comment) ->
                    if (sender !in muted) out += Alert(e, sender, msats, comment)
                }
            } else if (e.pubkey !in muted) {
                out += Alert(e, e.pubkey, 0, "")
            }
        }
        return out
    }

    fun newestAlertTime(me: String): Long =
        readableDatabase.rawQuery(
            "SELECT MAX(created_at) FROM events WHERE id IN (SELECT id FROM refs WHERE name='p' AND value=?) AND pubkey != ?",
            arrayOf(me, me),
        ).use { if (it.moveToFirst()) it.getLong(0) else 0 }

    fun unreadAlerts(me: String, since: Long): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM events WHERE id IN (SELECT id FROM refs WHERE name='p' AND value=?) AND pubkey != ? AND created_at > ?",
            arrayOf(me, me, since.toString()),
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun hasWrap(id: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM wraps WHERE id=?", arrayOf(id)).use { it.moveToFirst() }

    fun addWrap(id: String) {
        writableDatabase.execSQL("INSERT OR IGNORE INTO wraps(id) VALUES(?)", arrayOf(id))
    }

    fun addDm(dm: Dm) {
        val values = ContentValues(5).apply {
            put("id", dm.id)
            put("room", dm.room)
            put("sender", dm.sender)
            put("created_at", dm.createdAt)
            put("content", dm.content)
        }
        writableDatabase.insertWithOnConflict("dms", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun rooms(me: String): List<Room> {
        val out = ArrayList<Room>()
        readableDatabase.rawQuery(
            """SELECT d.id, d.room, d.sender, d.created_at, d.content FROM dms d
               JOIN (SELECT room, MAX(created_at) AS t FROM dms GROUP BY room) m
               ON m.room=d.room AND m.t=d.created_at GROUP BY d.room ORDER BY d.created_at DESC""",
            null,
        ).use {
            while (it.moveToNext()) {
                val dm = Dm(it.getString(0), it.getString(1), it.getString(2), it.getLong(3), it.getString(4))
                out += Room(dm.room, dm.room.split(',').filter { p -> p != me }, dm)
            }
        }
        return out
    }

    fun messages(room: String, limit: Int): List<Dm> {
        val out = ArrayList<Dm>()
        readableDatabase.rawQuery(
            "SELECT id, room, sender, created_at, content FROM dms WHERE room=? ORDER BY created_at DESC LIMIT ?",
            arrayOf(room, limit.toString()),
        ).use {
            while (it.moveToNext()) out += Dm(it.getString(0), it.getString(1), it.getString(2), it.getLong(3), it.getString(4))
        }
        return out
    }

    fun unreadMessages(me: String, since: Long): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM dms WHERE sender != ? AND created_at > ?",
            arrayOf(me, since.toString()),
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun searchProfiles(query: String, limit: Int): List<Event> {
        val like = "%" + query.replace("%", "").replace("_", "") + "%"
        return events(
            "SELECT $COLUMNS FROM events WHERE kind=0 AND content LIKE ? ORDER BY created_at DESC LIMIT ?",
            arrayOf(like, limit.toString()),
        )
    }

    /** Keeps the cache bounded: only ordinary events by people not followed are dropped. */
    fun prune(me: String?, keep: Int) {
        val db = writableDatabase
        val cutoff = db.rawQuery(
            "SELECT created_at FROM events ORDER BY created_at DESC LIMIT 1 OFFSET ?",
            arrayOf(keep.toString()),
        ).use { if (it.moveToFirst()) it.getLong(0) else return }
        val condition = """created_at < ? AND kind IN (1,6,7,16,1111) AND pubkey != ?
            AND pubkey NOT IN (SELECT pubkey FROM follows)"""
        val args = arrayOf(cutoff.toString(), me ?: "")
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM notes_fts WHERE docid IN (SELECT rowid FROM events WHERE kind=1 AND $condition)", args)
            db.execSQL("DELETE FROM refs WHERE id IN (SELECT id FROM events WHERE $condition)", args)
            db.execSQL("DELETE FROM events WHERE $condition", args)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun clearAll() {
        val db = writableDatabase
        for (table in listOf("refs", "zaps", "providers", "dms", "wraps", "follows", "events")) db.delete(table, null, null)
        db.execSQL("INSERT INTO notes_fts(notes_fts) VALUES('rebuild')")
    }

    private companion object {
        const val COLUMNS = "id, pubkey, created_at, kind, tags, content, sig"
        val NOTIFYING = setOf(Kind.NOTE, Kind.COMMENT, Kind.REPOST, Kind.GENERIC_REPOST, Kind.REACTION, Kind.ZAP_RECEIPT)
    }
}

fun roomKey(members: Set<String>) = members.sorted().joinToString(",")

fun isHexKey(s: String?) = Hex.isKey(s)
