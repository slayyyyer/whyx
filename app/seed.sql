-- `insert or ignore` keeps this seed safe to apply to a database that already has it: a demo
-- database created before migrations existed adopts both files and keeps exactly its own rows.
-- password_hash is 'pbkdf2-sha256$<rounds>$<salt>$<key>': the row carries its own cost and a
-- random 16-byte salt, so the seed stores no other password material.
-- Every demo account signs in with the password whylang.
insert or ignore into users (id, handle, name, bio, location, color, password_hash, created_at) values
  (1, 'alice', 'Alice Chen', 'Building tiny languages and large feeds. Pages should be files.', 'Berlin', '#1d9bf0', 'pbkdf2-sha256$120000$0cf9cfd49931a2734fc120dfad1d4f35$895a2a59c3fc9afebeda61bd904518ada18a3b91228c147192db2d1632fa6e9f', datetime('now', '-240 days')),
  (2, 'bob', 'Bob Okafor', 'Frontend, CSS crimes, and one more refactor.', 'Lagos', '#f91880', 'pbkdf2-sha256$120000$ee5388307dbe795b4b0f5259c778458a$126e7e0583a47ef30050a0d5c4980d2f6a9236d5a1c556c958bedca3bda315ad', datetime('now', '-210 days')),
  (3, 'carol', 'Carol Diaz', 'Infrastructure. SQLite is a database. Ask me about backups.', 'Austin', '#00ba7c', 'pbkdf2-sha256$120000$fa0cfa6e7c3899e6da61dc1731933ee7$5f8db0a20a6215605c46b3cecf78bde1ae6c88a9283522b8f878e2ef0abe19b4', datetime('now', '-180 days')),
  (4, 'dave', 'Dave Park', 'Models, indexes, and the bug between them.', 'Seoul', '#ffd400', 'pbkdf2-sha256$120000$94967ad01eeacdf362e2a7b8dd983e40$edf86b4e33e2b9e21c68373f20b845e026c9709d8e16f2d5b9034043fb9193ac', datetime('now', '-120 days')),
  (5, 'erin', 'Erin Walsh', 'Product design. Timelines should feel alive.', 'Dublin', '#7856ff', 'pbkdf2-sha256$120000$0a76c6891fe485172c117d118110fbdd$fae97ddea48f07b43cc15271532fdad4ff6af6cf9205c1fe056e6601ba3922c0', datetime('now', '-90 days')),
  (6, 'frank', 'Frank Ito', 'Lurking professionally since 2019.', 'Osaka', '#ff7a00', 'pbkdf2-sha256$120000$edc5f6d224463b2324ef53654868ba78$3dcf8c47b920ab92ae59c5828aee5c955c01590dfbed87f609f584a966bfe3a4', datetime('now', '-30 days'));

insert or ignore into posts (id, user_id, parent_id, body, created_at) values
  (1, 1, null, 'shipped a page language where the URL is the filename. no router, no build step: drop a .why file in the folder and refresh.', datetime('now', '-2 days')),
  (2, 2, null, 'hot take: the best state management is a form POST and a 303 redirect.', datetime('now', '-47 hours')),
  (3, 3, null, 'sqlite in WAL mode with a busy_timeout handles more write traffic than your app will ever see.', datetime('now', '-44 hours')),
  (4, 4, null, 'spent the morning tuning a model. spent the afternoon finding out the bug was a missing index.', datetime('now', '-40 hours')),
  (5, 5, null, 'design note: a timeline feels alive when the timestamp is relative and the avatar is a colored circle.', datetime('now', '-36 hours')),
  (6, 1, null, 'extended why with sessions today. one small table, one cookie, and login just works.', datetime('now', '-30 hours')),
  (7, 2, null, 'reminder that text-wrap: pretty fixes almost every headline you hate.', datetime('now', '-26 hours')),
  (8, 3, null, 'backups you have never restored are not backups. they are hopes.', datetime('now', '-22 hours')),
  (9, 4, null, 'papers are just blog posts with better typography.', datetime('now', '-18 hours')),
  (10, 5, null, 'shipping the feed with 280 characters and no algorithm. chronological is a feature.', datetime('now', '-12 hours')),
  (11, 1, null, 'reading the source of a thousand line server is a superpower. you can hold the whole thing in your head.', datetime('now', '-6 hours')),
  (12, 6, null, 'first post. testing the waters. hello.', datetime('now', '-2 hours')),
  (13, 2, 1, 'using the filename as the route is such a good constraint.', datetime('now', '-46 hours')),
  (14, 3, 1, 'does it hot reload? asking for my refresh key.', datetime('now', '-45 hours')),
  (15, 1, 14, 'every request reparses the page, so yes. edit and refresh.', datetime('now', '-44 hours')),
  (16, 5, 3, 'the pessimistic version of this take is a full Saturday of ops.', datetime('now', '-42 hours')),
  (17, 4, 2, 'forms and redirects: the original optimistic UI.', datetime('now', '-38 hours')),
  (18, 2, 10, 'chronological plus a follow graph is all a feed needs.', datetime('now', '-10 hours'));

insert or ignore into follows (follower_id, followee_id, created_at) values
  (1, 2, datetime('now', '-200 days')),
  (1, 3, datetime('now', '-190 days')),
  (1, 5, datetime('now', '-80 days')),
  (2, 1, datetime('now', '-200 days')),
  (2, 3, datetime('now', '-150 days')),
  (3, 1, datetime('now', '-170 days')),
  (3, 4, datetime('now', '-100 days')),
  (4, 1, datetime('now', '-110 days')),
  (4, 2, datetime('now', '-110 days')),
  (4, 5, datetime('now', '-70 days')),
  (5, 1, datetime('now', '-85 days')),
  (5, 2, datetime('now', '-85 days')),
  (6, 1, datetime('now', '-20 days'));

insert or ignore into likes (user_id, post_id, created_at) values
  (1, 2, datetime('now', '-40 hours')),
  (1, 3, datetime('now', '-40 hours')),
  (1, 5, datetime('now', '-30 hours')),
  (1, 7, datetime('now', '-20 hours')),
  (2, 1, datetime('now', '-45 hours')),
  (2, 3, datetime('now', '-40 hours')),
  (2, 6, datetime('now', '-25 hours')),
  (2, 10, datetime('now', '-10 hours')),
  (3, 1, datetime('now', '-44 hours')),
  (3, 2, datetime('now', '-40 hours')),
  (3, 6, datetime('now', '-28 hours')),
  (3, 9, datetime('now', '-12 hours')),
  (4, 1, datetime('now', '-44 hours')),
  (4, 10, datetime('now', '-10 hours')),
  (4, 5, datetime('now', '-30 hours')),
  (5, 1, datetime('now', '-40 hours')),
  (5, 6, datetime('now', '-24 hours')),
  (5, 12, datetime('now', '-1 hours')),
  (6, 1, datetime('now', '-20 hours')),
  (6, 10, datetime('now', '-8 hours'));

insert or ignore into reposts (user_id, post_id, created_at) values
  (2, 1, datetime('now', '-40 hours')),
  (5, 6, datetime('now', '-20 hours')),
  (3, 1, datetime('now', '-38 hours'));
