package io.theficos.usuals.data.local

/** Counts every later write of the shown copy of a list, through a trigger on its first part. */
internal fun UsualsDatabase.projectionRewrites(): () -> Int {
    val sql = openHelper.writableDatabase
    sql.execSQL("CREATE TABLE IF NOT EXISTS rewrites (listId TEXT)")
    sql.execSQL("DELETE FROM rewrites")
    sql.execSQL("CREATE TRIGGER IF NOT EXISTS count_rewrites AFTER INSERT ON sync_projection WHEN NEW.optimistic = 1 AND NEW.part = 0 BEGIN INSERT INTO rewrites VALUES (NEW.listId); END")
    return { sql.query("SELECT COUNT(*) FROM rewrites").use { it.moveToFirst(); it.getInt(0) } }
}
