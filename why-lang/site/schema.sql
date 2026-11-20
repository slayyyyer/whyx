create table if not exists posts (
  id integer primary key,
  title text not null,
  body text not null,
  author text not null,
  created_at text not null
);

insert into posts (id, title, body, author, created_at) values
  (1, 'Why a new language?', 'Because writing a page should mean writing a page. No router, no build step, no ceremony: drop a .why file in the folder and it runs.', 'why', '2026-11-01'),
  (2, 'Templates with teeth', 'The view section is Handlebars-flavored, but a mustache evaluates a real expression from the controller.', 'why', '2026-11-08'),
  (3, 'Dropping files is the API', 'Copy a .why file into the site folder and it is live at its path. There is no routing table to update and no cache to clear.', 'why', '2026-11-15');

create table if not exists guestbook (
  id integer primary key,
  name text not null,
  message text not null,
  created_at text not null
);
