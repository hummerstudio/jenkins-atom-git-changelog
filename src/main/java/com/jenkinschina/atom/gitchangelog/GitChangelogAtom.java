package com.jenkinschina.atom.gitchangelog;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Changelog as an atom: turn the commits since the last tag into a file the release notes can quote.
 *
 * <p>The plugin it replaces reads the SCM changelog from Jenkins; an atom has no such model, so it asks git. That is
 * also why it works anywhere git does, on any agent.
 */
public class GitChangelogAtom implements AtomTask {

    private static final String SEPARATOR = "\u001f";
    private static final String RECORD = "\u001e";

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        Path workspace = context.getWorkspace();
        String since = context.getParam("since", "");
        String until = context.getParam("until", "HEAD");
        String outFile = context.getParam("output", "CHANGELOG.md");
        String label = context.getParam("label", "Changelog");

        if (!git(workspace, "rev-parse", "--is-inside-work-tree").strip().equals("true")) {
            return AtomResult.error(ErrorType.USER, 40001, "not a git repository: " + workspace);
        }

        String base = since.isBlank() ? previousTag(workspace) : since;
        String range;
        if (base.isBlank()) {
            range = until;
            context.log("[changelog] no tag found: listing the whole history up to " + until);
        } else {
            range = base + ".." + until;
        }

        String format = "--pretty=format:%H" + SEPARATOR + "%an" + SEPARATOR + "%ad" + SEPARATOR + "%s" + RECORD;
        String raw = git(workspace, "log", "--no-merges", "--date=short", format, range);
        List<Commit> commits = parse(raw);

        StringBuilder markdown = new StringBuilder("## Changes");
        if (!base.isBlank()) {
            markdown.append(" since ").append(base);
        }
        markdown.append("\n\n");
        if (commits.isEmpty()) {
            markdown.append("- No commits\n");
        } else {
            for (Commit commit : commits) {
                markdown.append("- ").append(commit.subject())
                        .append(" (").append(commit.author()).append(", ").append(commit.date()).append(")\n");
            }
        }

        Path file = workspace.resolve(outFile);
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(file, markdown.toString(), StandardCharsets.UTF_8);
        context.log("[changelog] " + commits.size() + " commit(s) in " + range + " -> " + outFile);

        AtomResult result = AtomResult.success()
                .data("commits", String.valueOf(commits.size()))
                .data("since", base)
                .data("until", until)
                .data("outputFile", outFile)
                .artifact("changelog", outFile);
        Path dir = workspace.resolve("out/changelog");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("index.html"), render(label, range, commits), StandardCharsets.UTF_8);
        return result.report("report", label, dir.toString(), "index.html");
    }

    private static String previousTag(Path workspace) {
        String described = git(workspace, "describe", "--tags", "--abbrev=0").strip();
        return described.isBlank() ? "" : described;
    }

    private static List<Commit> parse(String raw) {
        List<Commit> commits = new ArrayList<>();
        if (raw.isBlank()) {
            return commits;
        }
        for (String record : raw.split(RECORD)) {
            String entry = record.strip();
            if (entry.isEmpty()) {
                continue;
            }
            String[] parts = entry.split(SEPARATOR);
            if (parts.length >= 4) {
                commits.add(new Commit(parts[0], parts[1], parts[2], parts[3]));
            }
        }
        return commits;
    }

    static String git(Path workspace, String... args) {
        try {
            List<String> command = new ArrayList<>(List.of("git"));
            command.addAll(List.of(args));
            ProcessBuilder builder = new ProcessBuilder(command).directory(workspace.toFile()).redirectErrorStream(true);
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            Process process = builder.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }
            process.waitFor();
            return out.toString();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String render(String label, String range, List<Commit> commits) {
        StringBuilder html = new StringBuilder("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">");
        html.append("<title>").append(escape(label)).append("</title><style>")
                .append("body{font-family:-apple-system,'Segoe UI',sans-serif;margin:0;padding:28px;background:#f6f7f9}")
                .append("h1{font-size:22px;margin:0 0 4px}p.meta{color:#6b7280;font-size:13px;margin:0 0 18px}")
                .append("table{width:100%;border-collapse:collapse;background:#fff;border:1px solid #e5e7eb;font-size:13px}")
                .append("th,td{text-align:left;padding:8px 12px;border-bottom:1px solid #eef0f3}")
                .append("th{background:#fafbfc;color:#6b7280}code{font-family:ui-monospace,Menlo,monospace}")
                .append("</style></head><body>");
        html.append("<h1>").append(escape(label)).append("</h1><p class=\"meta\">")
                .append(commits.size()).append(" commit(s) · <code>").append(escape(range)).append("</code></p>");
        html.append("<table><tr><th>Commit</th><th>Subject</th><th>Author</th><th>Date</th></tr>");
        for (Commit commit : commits) {
            html.append("<tr><td><code>").append(escape(commit.hash().substring(0, Math.min(7, commit.hash().length()))))
                    .append("</code></td><td>").append(escape(commit.subject())).append("</td><td>")
                    .append(escape(commit.author())).append("</td><td>").append(escape(commit.date()))
                    .append("</td></tr>");
        }
        html.append("</table></body></html>");
        return html.toString();
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private record Commit(String hash, String author, String date, String subject) {}
}
