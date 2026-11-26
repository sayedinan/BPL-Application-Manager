-- Usernames that differ only in case could pass for one another.
-- Triggers, not a unique index: creating an index would fail, and stop the app from
-- starting, if such a pair already exists. These only refuse new clashes.
-- lower() folds ASCII only, which covers every character a new username may contain.
CREATE TRIGGER users_username_nocase_insert BEFORE INSERT ON users
WHEN EXISTS (SELECT 1 FROM users WHERE lower(username) = lower(NEW.username))
BEGIN SELECT RAISE(ABORT, 'username already exists'); END;

CREATE TRIGGER users_username_nocase_update BEFORE UPDATE OF username ON users
WHEN NEW.username <> OLD.username
  AND EXISTS (SELECT 1 FROM users WHERE lower(username) = lower(NEW.username) AND id <> OLD.id)
BEGIN SELECT RAISE(ABORT, 'username already exists'); END;
