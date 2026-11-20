-- One row per failed sign-in, counted only inside the window the login page asks for, so the
-- per-account and per-IP limits slide instead of resetting on a fixed clock.
create table if not exists login_attempts (
  id integer primary key autoincrement,
  kind text not null,
  subject text not null,
  attempted_at integer not null
);

create index if not exists login_attempts_subject on login_attempts (kind, subject, attempted_at);
