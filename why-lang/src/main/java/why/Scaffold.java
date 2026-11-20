package why;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/** Writes a runnable site folder, so {@code why new mysite} needs no Gradle project first. */
final class Scaffold {

    private Scaffold() {}

    /** Creates the site; returns 0 on success and 1 with a message on failure. */
    static int create(Path target) {
        Path root = target.toAbsolutePath().normalize();
        try {
            if (Files.exists(root) && !isEmpty(root)) {
                System.err.println("why: " + root + " is not empty");
                return 1;
            }
            Files.createDirectories(root);
            for (Map.Entry<String, String> file : files().entrySet()) {
                Files.writeString(root.resolve(file.getKey()), file.getValue(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            System.err.println("why: cannot create " + root + ": " + e.getMessage());
            return 1;
        }
        System.out.println("why: created " + root);
        System.out.println();
        System.out.println("  java -jar why.jar " + target);
        System.out.println();
        System.out.println("Then open http://localhost:8080/.");
        return 0;
    }

    private static boolean isEmpty(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return false;
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.findAny().isEmpty();
        }
    }

    private static Map<String, String> files() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("index.why", """
                imports:
                header
                footer

                controller:
                title = "Hello, Why"
                if request.method == "POST"
                  body = form.body.strip
                  if body != ""
                    db.exec("insert into notes (body, created_at) values (?, datetime('now'))", body)
                    redirect("/")
                  end
                end
                entries = db.query("select * from notes order by id desc")

                view:
                {{> header}}
                <h1>{{title}}</h1>
                <form method="post" action="/">
                  <input type="hidden" name="_token" value="{{csrf.token}}">
                  <input name="body" placeholder="Leave a note" maxlength="120">
                  <button type="submit">Add</button>
                </form>
                <ul>
                {{#each entries}}<li>{{it.body}}</li>{{/each}}
                </ul>
                {{> footer}}
                """);
        files.put("header.why", """
                <!doctype html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Why</title>
                <link rel="stylesheet" href="/style.css">
                </head>
                <body>
                <main>
                """);
        files.put("footer.why", """
                </main>
                </body>
                </html>
                """);
        files.put("style.css", """
                body { font: 16px/1.5 system-ui, sans-serif; margin: 0; }
                main { max-width: 36rem; margin: 4rem auto; padding: 0 1.5rem; }
                form { display: flex; gap: .5rem; margin: 1.5rem 0; }
                input { flex: 1; padding: .5rem; font: inherit; }
                """);
        files.put("schema.sql", """
                create table if not exists notes (
                  id integer primary key,
                  body text not null,
                  created_at text not null
                );
                """);
        return files;
    }
}
