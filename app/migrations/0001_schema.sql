-- The first migration owns the schema: a fresh database runs every file in this folder in order,
-- so changing the schema is a new file here rather than deleting the demo database.
create table if not exists users (
  id integer primary key autoincrement,
  handle text not null unique,
  name text not null,
  bio text not null default '',
  location text not null default '',
  color text not null default '#1d9bf0',
  password_hash text not null,
  created_at text not null default (datetime('now'))
);

create table if not exists posts (
  id integer primary key autoincrement,
  user_id integer not null references users(id),
  parent_id integer,
  body text not null,
  created_at text not null default (datetime('now'))
);

create table if not exists follows (
  follower_id integer not null,
  followee_id integer not null,
  created_at text not null default (datetime('now')),
  unique (follower_id, followee_id)
);

create table if not exists likes (
  user_id integer not null,
  post_id integer not null,
  created_at text not null default (datetime('now')),
  unique (user_id, post_id)
);

create table if not exists reposts (
  user_id integer not null,
  post_id integer not null,
  created_at text not null default (datetime('now')),
  unique (user_id, post_id)
);

create index if not exists posts_user on posts (user_id, id desc);
create index if not exists posts_parent on posts (parent_id, id);
create index if not exists likes_post on likes (post_id);
create index if not exists reposts_post on reposts (post_id);
