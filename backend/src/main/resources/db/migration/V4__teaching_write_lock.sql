CREATE TABLE teaching_write_lock (
  id INTEGER PRIMARY KEY,
  revision BIGINT NOT NULL DEFAULT 0
);
INSERT INTO teaching_write_lock(id,revision) VALUES(1,0);
