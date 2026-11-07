package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The git-changelog atom, started the way the runner starts it.
 *
 * <p>These are the checks the old {@code tools/smoke.sh} made — the range from the last tag, an explicit range, and a
 * directory that is not a repository — written as JUnit.
 *
 * <p>The repositories are built here with git and handed to the atom through a {@code .git} file, so the atom still
 * runs in the throwaway workspace the harness gives it while reading the history the test prepared.
 */
class GitChangelogIT {

    private Atom atom;

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
    }

    @Test
    @DisplayName("the last tag is the range: two commits, the tagged one excluded, and an HTML report")
    void listsTheCommitsSinceTheLastTag() throws Exception {
        Path repo = repository("tagged");
        commit(repo, "a.txt", "a\n", "first commit");
        git(repo, "tag", "v1.0.0");
        commit(repo, "b.txt", "b\n", "add b");
        commit(repo, "c.txt", "c\n", "add c");

        AtomResult run = atom.file(".git", "gitdir: " + repo.resolve(".git") + "\n")
                .input("label", "Changelog")
                .run();

        run.assertOk();
        assertEquals("success", run.status());
        assertEquals("2", run.field("commits"), "range: " + run.file("output.json"));
        assertEquals("v1.0.0", run.field("since"));
        assertTrue(run.file("CHANGELOG.md").contains("add b"), "CHANGELOG.md misses a commit:\n" + run.file("CHANGELOG.md"));
        assertFalse(run.file("CHANGELOG.md").contains("first commit"),
                "the tagged commit must not be in the range:\n" + run.file("CHANGELOG.md"));
        assertTrue(run.exists("out/changelog/index.html"), "no HTML report");
    }

    @Test
    @DisplayName("an explicit range is honoured: HEAD~2..HEAD is two commits")
    void honoursAnExplicitRange() throws Exception {
        Path repo = repository("range");
        commit(repo, "f", "1\n", "one");
        commit(repo, "f", "1\n2\n", "two");
        commit(repo, "f", "1\n2\n3\n", "three");

        AtomResult run = atom.file(".git", "gitdir: " + repo.resolve(".git") + "\n")
                .input("since", "HEAD~2")
                .input("label", "Range")
                .run();

        run.assertOk();
        assertEquals("2", run.field("commits"), "HEAD~2..HEAD must be 2 commits: " + run.file("output.json"));
    }

    @Test
    @DisplayName("a directory that is not a repository is refused by name")
    void refusesOutsideARepository() throws Exception {
        // the workspace lives under the atom's own checkout, so git must be stopped from finding that repository
        AtomResult run = atom.env("GIT_CEILING_DIRECTORIES", System.getProperty("atom.scratch"))
                .input("{}")
                .run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("not a git repository"),
                "message: " + run.file("output.json"));
    }

    private Path repository(String name) throws Exception {
        Path repo = Path.of(System.getProperty("atom.scratch")).resolve("repo-" + name);
        // a repository left over from an earlier run would already have the commits and the tag
        if (Files.exists(repo)) {
            try (var walk = Files.walk(repo)) {
                for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
        Files.createDirectories(repo);
        git(repo, "init", "-q", "-b", "main");
        git(repo, "config", "user.email", "smoke@local");
        git(repo, "config", "user.name", "Smoke");
        return repo;
    }

    private static void commit(Path repo, String relative, String content, String message) throws Exception {
        Files.writeString(repo.resolve(relative), content);
        git(repo, "add", relative);
        git(repo, "commit", "-q", "-m", message);
    }

    private static void git(Path repo, String... args) throws Exception {
        java.util.List<String> command = new java.util.ArrayList<>(java.util.List.of("git"));
        command.addAll(java.util.List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(repo.toFile()).redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), "git " + String.join(" ", args) + " failed: " + output);
    }
}
