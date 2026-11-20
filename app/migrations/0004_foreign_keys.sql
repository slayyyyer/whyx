-- SQLite enforces references only when a connection asks, and the runtime now asks. These tables
-- predate that and deleting a thread left replies behind, so rebuild them with the constraints
-- the app assumed. The migration runner disables enforcement while a table is rebuilt and checks
-- the finished schema with foreign_key_check.
--
-- Rows written before enforcement can already be inconsistent. Keep an orphaned reply as a
-- top-level post rather than failing the migration, and drop interactions whose user or post is
-- already gone.
delete from posts where user_id not in (select id from users);
update posts set parent_id = null
  where parent_id is not null and parent_id not in (select id from posts);
delete from likes where user_id not in (select id from users) or post_id not in (select id from posts);
delete from reposts where user_id not in (select id from users) or post_id not in (select id from posts);
delete from follows where follower_id not in (select id from users) or followee_id not in (select id from users);

create table posts_new (
  id integer primary key autoincrement,
  user_id integer not null references users(id) on delete cascade,
  parent_id integer references posts(id) on delete cascade,
  body text not null,
  created_at text not null default (datetime('now'))
);
insert into posts_new (id, user_id, parent_id, body, created_at)
  select id, user_id, parent_id, body, created_at from posts;
drop table posts;
alter table posts_new rename to posts;
create index if not exists posts_user on posts (user_id, id desc);
create index if not exists posts_parent on posts (parent_id, id);

create table likes_new (
  user_id integer not null references users(id) on delete cascade,
  post_id integer not null references posts(id) on delete cascade,
  created_at text not null default (datetime('now')),
  unique (user_id, post_id)
);
insert into likes_new (user_id, post_id, created_at)
  select user_id, post_id, created_at from likes;
drop table likes;
alter table likes_new rename to likes;
create index if not exists likes_post on likes (post_id);

create table reposts_new (
  user_id integer not null references users(id) on delete cascade,
  post_id integer not null references posts(id) on delete cascade,
  created_at text not null default (datetime('now')),
  unique (user_id, post_id)
);
insert into reposts_new (user_id, post_id, created_at)
  select user_id, post_id, created_at from reposts;
drop table reposts;
alter table reposts_new rename to reposts;
create index if not exists reposts_post on reposts (post_id);

create table follows_new (
  follower_id integer not null references users(id) on delete cascade,
  followee_id integer not null references users(id) on delete cascade,
  created_at text not null default (datetime('now')),
  unique (follower_id, followee_id)
);
insert into follows_new (follower_id, followee_id, created_at)
  select follower_id, followee_id, created_at from follows;
drop table follows;
alter table follows_new rename to follows;
