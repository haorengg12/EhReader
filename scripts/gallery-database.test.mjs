import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { DatabaseSync } from 'node:sqlite';
import test from 'node:test';

const schema = JSON.parse(readFileSync(new URL('../app/schemas/com.hippo.ehviewer.dao.EhDatabase/2.json', import.meta.url))).database;
const previousSchema = JSON.parse(readFileSync(new URL('../app/schemas/com.hippo.ehviewer.dao.EhDatabase/1.json', import.meta.url))).database;

function database(definition = schema) {
  const db = new DatabaseSync(':memory:');
  db.exec('PRAGMA foreign_keys = ON');
  for (const entity of definition.entities) {
    db.exec(entity.createSql.replaceAll('${TABLE_NAME}', entity.tableName));
    for (const index of entity.indices ?? []) db.exec(index.createSql.replaceAll('${TABLE_NAME}', entity.tableName));
  }
  return db;
}

function insert(db, table, values) {
  const entity = schema.entities.find((item) => item.tableName === table);
  const row = Object.fromEntries(entity.fields.filter((field) => Object.hasOwn(values, field.columnName) || field.defaultValue === undefined).map((field) => [field.columnName,
    Object.hasOwn(values, field.columnName) ? values[field.columnName] : field.notNull ? field.affinity === 'TEXT' ? '' : 0 : null]));
  db.prepare(`INSERT INTO ${table} (${Object.keys(row).join(',')}) VALUES (${Object.keys(row).map(() => '?').join(',')})`).run(...Object.values(row));
}

test('Room schema: one metadata record serves downloads, history, favorites and bookmarks', () => {
  const db = database();
  try {
    insert(db, 'GALLERY_METADATA', { GID: 12, TITLE: 'before', METADATA_STATE: 'partial' });
    for (const table of ['DOWNLOADS', 'HISTORY', 'LOCAL_FAVORITES', 'BOOKMARKS']) {
      insert(db, table, { GID: 12 });
      const columns = db.prepare(`PRAGMA table_info(${table})`).all().map((column) => column.name);
      for (const name of ['TITLE', 'TITLE_JPN', 'TOKEN', 'THUMB', 'CATEGORY', 'RATING', 'SIMPLE_LANGUAGE']) assert.ok(!columns.includes(name));
    }
    db.prepare('UPDATE GALLERY_METADATA SET TITLE = ? WHERE GID = 12').run('after');
    for (const table of ['DOWNLOADS', 'HISTORY', 'LOCAL_FAVORITES', 'BOOKMARKS']) {
      assert.equal(db.prepare(`SELECT m.TITLE FROM ${table} b JOIN GALLERY_METADATA m USING (GID)`).get().TITLE, 'after');
    }
    assert.equal(db.prepare('SELECT COUNT(*) AS total FROM GALLERY_METADATA').get().total, 1);
  } finally { db.close(); }
});

test('Room schema: foreign keys reject orphan records, deletion retains other business relations', () => {
  const db = database();
  try {
    for (const table of ['DOWNLOADS', 'HISTORY', 'LOCAL_FAVORITES', 'BOOKMARKS', 'READING_STATE', 'DOWNLOAD_LOCATIONS']) {
      assert.throws(() => insert(db, table, { GID: 99 }), /FOREIGN KEY/);
    }
    insert(db, 'GALLERY_METADATA', { GID: 12, METADATA_STATE: 'partial' });
    insert(db, 'DOWNLOADS', { GID: 12 });
    insert(db, 'HISTORY', { GID: 12, TIME: 123 });
    insert(db, 'LOCAL_FAVORITES', { GID: 12, TIME: 456 });
    assert.throws(() => db.exec('DELETE FROM GALLERY_METADATA WHERE GID = 12'), /FOREIGN KEY/);
    db.exec('DELETE FROM DOWNLOADS WHERE GID = 12');
    assert.equal(db.prepare('SELECT TIME FROM HISTORY WHERE GID = 12').get().TIME, 123);
    assert.equal(db.prepare('SELECT TIME FROM LOCAL_FAVORITES WHERE GID = 12').get().TIME, 456);
    assert.equal(db.prepare('PRAGMA foreign_key_check').all().length, 0);
  } finally { db.close(); }
});

test('Room schema: unknown metadata and legacy path stay null; failed transaction leaves no partial business state', () => {
  const db = database();
  try {
    insert(db, 'GALLERY_METADATA', { GID: 12, METADATA_STATE: 'partial' });
    insert(db, 'DOWNLOADS', { GID: 12 });
    for (const field of ['TOKEN', 'RATING', 'PAGES', 'SOURCE', 'DETAIL_URL', 'FAVORITE_COUNT']) {
      assert.equal(db.prepare(`SELECT ${field} AS value FROM GALLERY_METADATA WHERE GID = 12`).get().value, null);
    }
    assert.equal(db.prepare('SELECT LEGACY_DOWNLOAD_PATH FROM DOWNLOADS WHERE GID = 12').get().LEGACY_DOWNLOAD_PATH, null);
    const progress = db.prepare('SELECT FINISHED_PAGES, ATTEMPTED_PAGES, TOTAL_PAGES FROM DOWNLOADS WHERE GID = 12').get();
    assert.deepEqual({ ...progress }, { FINISHED_PAGES: 0, ATTEMPTED_PAGES: 0, TOTAL_PAGES: 0 });
    db.exec('BEGIN');
    try {
      db.exec('UPDATE DOWNLOADS SET STATE = 3 WHERE GID = 12');
      insert(db, 'HISTORY', { GID: 99 });
      assert.fail('Foreign key failure expected');
    } catch {
      db.exec('ROLLBACK');
    }
    assert.equal(db.prepare('SELECT STATE FROM DOWNLOADS WHERE GID = 12').get().STATE, 0);
    assert.equal(db.prepare('SELECT COUNT(*) AS total FROM HISTORY').get().total, 0);
  } finally { db.close(); }
});


test('DOWNLOADS legacy import marker defaults to false and cannot be null', () => {
  const db = database();
  try {
    insert(db, 'GALLERY_METADATA', { GID: 12, METADATA_STATE: 'partial' });
    insert(db, 'DOWNLOADS', { GID: 12 });
    const column = db.prepare('PRAGMA table_info(DOWNLOADS)').all().find((item) => item.name === 'IMPORTED_FROM_LEGACY');
    assert.equal(column.notnull, 1);
    assert.equal(column.dflt_value, '0');
    assert.equal(db.prepare('SELECT IMPORTED_FROM_LEGACY FROM DOWNLOADS WHERE GID = 12').get().IMPORTED_FROM_LEGACY, 0);
    assert.throws(() => db.exec('UPDATE DOWNLOADS SET IMPORTED_FROM_LEGACY = NULL WHERE GID = 12'), /NOT NULL/);
    db.exec('UPDATE DOWNLOADS SET IMPORTED_FROM_LEGACY = 1 WHERE GID = 12');
    db.exec('UPDATE DOWNLOADS SET STATE = 3, FINISHED_PAGES = 20 WHERE GID = 12');
    assert.equal(db.prepare('SELECT IMPORTED_FROM_LEGACY FROM DOWNLOADS WHERE GID = 12').get().IMPORTED_FROM_LEGACY, 1);
    insert(db, 'GALLERY_METADATA', { GID: 13, METADATA_STATE: 'partial' });
    insert(db, 'DOWNLOADS', { GID: 13 });
    assert.equal(db.prepare('SELECT IMPORTED_FROM_LEGACY FROM DOWNLOADS WHERE GID = 13').get().IMPORTED_FROM_LEGACY, 0);
  } finally { db.close(); }
});

test('schema 1 to 2 adds a false marker without changing existing downloads or relationships', () => {
  const db = database(previousSchema);
  try {
    insert(db, 'GALLERY_METADATA', { GID: 12, TITLE: 'existing', METADATA_STATE: 'complete' });
    // 使用版本 1 的真实列清单填充记录。
    const oldDownloads = previousSchema.entities.find((item) => item.tableName === 'DOWNLOADS');
    const row = Object.fromEntries(oldDownloads.fields.map((field) => [field.columnName,
      field.columnName === 'GID' ? 12 : field.columnName === 'LEGACY_DOWNLOAD_PATH' ? 'old/path' : field.notNull ? 7 : null]));
    db.prepare('INSERT INTO DOWNLOADS (' + Object.keys(row).join(',') + ') VALUES (' + Object.keys(row).map(() => '?').join(',') + ')').run(...Object.values(row));
    insert(db, 'HISTORY', { GID: 12, TIME: 123 });
    const flag = schema.entities.find((item) => item.tableName === 'DOWNLOADS').fields.find((item) => item.columnName === 'IMPORTED_FROM_LEGACY');
    const expectedSql = 'ALTER TABLE DOWNLOADS ADD COLUMN IMPORTED_FROM_LEGACY ' + flag.affinity + ' NOT NULL DEFAULT ' + flag.defaultValue;
    const generatedSource = process.env.EHREADER_MIGRATION_1_2_SOURCE;
    const actualSql = generatedSource ? readFileSync(generatedSource, 'utf8').match(/execSQL\("([^"\n]+)"\)/)?.[1] : expectedSql;
    assert.ok(actualSql, 'Room-generated migration SQL must exist');
    assert.equal(actualSql.replaceAll(String.fromCharCode(96), ''), expectedSql);
    db.exec(actualSql);
    const migrated = db.prepare('SELECT * FROM DOWNLOADS WHERE GID = 12').get();
    assert.deepEqual({ ...migrated }, { ...row, IMPORTED_FROM_LEGACY: 0 });
    assert.equal(db.prepare('SELECT TIME FROM HISTORY WHERE GID = 12').get().TIME, 123);
    assert.equal(db.prepare('SELECT TITLE FROM GALLERY_METADATA WHERE GID = 12').get().TITLE, 'existing');
    assert.equal(db.prepare('PRAGMA foreign_key_check').all().length, 0);
    db.exec('UPDATE DOWNLOADS SET IMPORTED_FROM_LEGACY = 1 WHERE GID = 12');
    assert.throws(() => db.exec('ALTER TABLE DOWNLOADS ADD COLUMN IMPORTED_FROM_LEGACY INTEGER NOT NULL DEFAULT 0'), /duplicate column/);
    assert.equal(db.prepare('SELECT IMPORTED_FROM_LEGACY FROM DOWNLOADS WHERE GID = 12').get().IMPORTED_FROM_LEGACY, 1);
  } finally { db.close(); }
});

test('copying full download rows for new-format backup retains an explicitly imported marker', () => {
  const source = database();
  const target = database();
  try {
    insert(source, 'GALLERY_METADATA', { GID: 12, METADATA_STATE: 'complete' });
    insert(source, 'DOWNLOADS', { GID: 12, IMPORTED_FROM_LEGACY: 1, LEGACY_DOWNLOAD_PATH: 'old/12' });
    insert(target, 'GALLERY_METADATA', { GID: 12, METADATA_STATE: 'complete' });
    const row = source.prepare('SELECT * FROM DOWNLOADS WHERE GID = 12').get();
    insert(target, 'DOWNLOADS', row);
    assert.equal(target.prepare('SELECT IMPORTED_FROM_LEGACY FROM DOWNLOADS WHERE GID = 12').get().IMPORTED_FROM_LEGACY, 1);
    assert.equal(target.prepare('SELECT LEGACY_DOWNLOAD_PATH FROM DOWNLOADS WHERE GID = 12').get().LEGACY_DOWNLOAD_PATH, 'old/12');
  } finally { source.close(); target.close(); }
});
