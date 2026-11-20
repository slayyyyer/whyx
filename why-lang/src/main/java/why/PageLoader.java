package why;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Splits a page file into its imports, controller and view sections. */
public final class PageLoader {

    private static final List<String> SECTIONS = List.of("imports", "controller", "view");
    private static final Pattern LABEL = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*):$");
    private static final Pattern NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    private static WhyError pageTooLarge(String display, long bytes) {
        return new WhyError("page '" + display + "' is " + bytes + " bytes; the limit is "
                + AllocationLimits.PAGE_BYTES, display, 0);
    }

    private final Site site;

    public PageLoader(Site site) {
        this.site = site;
    }

    public Page load(Path file) {
        String display = site.relative(file);
        String text;
        try {
            long size = Files.size(file);
            if (size > AllocationLimits.PAGE_BYTES) throw pageTooLarge(display, size);
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new WhyError("cannot read page: " + e.getMessage(), display, 0);
        }
        long bytes = text.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > AllocationLimits.PAGE_BYTES) throw pageTooLarge(display, bytes);

        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) lines.remove(lines.size() - 1);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.endsWith("\r")) lines.set(i, line.substring(0, line.length() - 1));
        }

        boolean anyLabel = false;
        for (String line : lines) {
            if (LABEL.matcher(line).matches()) {
                anyLabel = true;
                break;
            }
        }
        if (!anyLabel) return new Page(file, List.of(), "", 1, text, 1);

        List<Page.Import> imports = new ArrayList<>();
        StringBuilder controller = new StringBuilder();
        StringBuilder view = new StringBuilder();
        int section = -1;
        int controllerLine = 1;
        int viewLine = 1;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int lineNo = i + 1;

            if (section < 2) {
                Matcher label = LABEL.matcher(line);
                if (label.matches()) {
                    String name = label.group(1);
                    int rank = SECTIONS.indexOf(name);
                    if (rank < 0) {
                        throw new WhyError("unknown section '" + name + ":' (expected imports:, controller: or view:)",
                                display, lineNo);
                    }
                    if (rank <= section) {
                        throw new WhyError("section '" + name + ":' is out of order", display, lineNo);
                    }
                    section = rank;
                    if (rank == 1) controllerLine = lineNo + 1;
                    if (rank == 2) viewLine = lineNo + 1;
                    continue;
                }
                if (section < 0 && !line.isBlank()) {
                    throw new WhyError("text before the first section label", display, lineNo);
                }
            }

            switch (section) {
                case 0 -> {
                    String name = line.strip();
                    if (name.isEmpty()) continue;
                    if (!NAME.matcher(name).matches()) {
                        throw new WhyError("import '" + name + "' is not a bare name", display, lineNo);
                    }
                    imports.add(new Page.Import(name, lineNo));
                }
                case 1 -> controller.append(line).append('\n');
                case 2 -> view.append(line).append('\n');
                default -> {
                    // Blank lines before the first label: not part of any section.
                }
            }
        }

        return new Page(file, imports, controller.toString(), controllerLine, view.toString(), viewLine);
    }
}
