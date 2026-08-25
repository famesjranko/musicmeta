package com.landofoz.musicmeta.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SecretsSearchTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A demo directory inside [worktree], whose `.git` file points at [main]'s worktree git dir. */
    private fun demoDirInWorktree(main: File, worktree: File): File {
        File(worktree, ".git").writeText("gitdir: ${File(main, ".git/worktrees/wt").absolutePath}\n")
        return File(worktree, "demo").apply { mkdirs() }
    }

    @Test
    fun `a worktree's git file resolves to the main checkout's secrets`() {
        // Given - a worktree whose .git file names an absolute gitdir under the main checkout
        val main = tmp.newFolder("main")
        val worktree = tmp.newFolder("wt")
        File(worktree, ".git").writeText("gitdir: ${File(main, ".git/worktrees/wt").absolutePath}\n")

        // When - resolving the main checkout's secrets from the worktree root
        val secrets = mainCheckoutSecrets(worktree)

        // Then - the main checkout's secrets.properties is named
        assertEquals(File(main, "secrets.properties"), secrets)
    }

    @Test
    fun `a relative gitdir resolves against the worktree root`() {
        // Given - a .git file whose gitdir path is relative, as git is permitted to write
        val main = tmp.newFolder("main")
        val worktree = tmp.newFolder("wt")
        File(worktree, ".git").writeText("gitdir: ../main/.git/worktrees/wt\n")

        // When - resolving the main checkout's secrets from the worktree root
        val secrets = mainCheckoutSecrets(worktree)

        // Then - the relative path lands on the same main checkout's secrets.properties
        assertEquals(File(main, "secrets.properties").canonicalFile, secrets?.canonicalFile)
    }

    @Test
    fun `a plain checkout's git directory contributes nothing`() {
        // Given - an ordinary checkout where .git is a directory, not a worktree pointer file
        val root = tmp.newFolder("plain")
        File(root, ".git").mkdirs()

        // When - resolving the main checkout's secrets
        val secrets = mainCheckoutSecrets(root)

        // Then - nothing joins the search path
        assertNull(secrets)
    }

    @Test
    fun `a git file that is not a worktree pointer contributes nothing`() {
        // Given - a .git file whose gitdir does not end in worktrees slash name (a submodule shape)
        val root = tmp.newFolder("sub")
        File(root, ".git").writeText("gitdir: ../.git/modules/sub\n")

        // When - resolving the main checkout's secrets
        val secrets = mainCheckoutSecrets(root)

        // Then - nothing joins the search path
        assertNull(secrets)
    }

    @Test
    fun `an unparseable git file contributes nothing`() {
        // Given - a .git file with no gitdir line at all
        val root = tmp.newFolder("junk")
        File(root, ".git").writeText("not a pointer\n")

        // When - resolving the main checkout's secrets
        val secrets = mainCheckoutSecrets(root)

        // Then - nothing joins the search path
        assertNull(secrets)
    }

    @Test
    fun `the main checkout's secrets file is the outermost entry on the search path`() {
        // Given - a demo directory in a worktree beside the main checkout
        val main = tmp.newFolder("main")
        val worktree = tmp.newFolder("wt")
        val demoDir = demoDirInWorktree(main, worktree)

        // When - building the search path a run out of that demo directory reads
        val path = secretsSearchPath(demoDir).map { it.canonicalFile }

        // Then - the main checkout's file comes first, then the repo root's, then the demo's own
        assertEquals(
            listOf(main, worktree, demoDir).map { File(it, "secrets.properties").canonicalFile },
            path,
        )
    }

    @Test
    fun `a nearer secrets file wins per key over the main checkout's`() {
        // Given - a demo directory, repo root and main checkout that each hold a secrets file
        val main = tmp.newFolder("main")
        File(main, "secrets.properties").writeText("lastfm.apikey=from-main\ndiscogs.token=from-main\n")
        val worktree = tmp.newFolder("wt")
        val demoDir = demoDirInWorktree(main, worktree)
        File(worktree, "secrets.properties").writeText("lastfm.apikey=from-repo-root\nfanarttv.apikey=from-repo-root\n")
        File(demoDir, "secrets.properties").writeText("lastfm.apikey=from-demo\n")

        // When - loading the secrets a run out of that demo directory sees
        val secrets = loadSecrets(demoDir)

        // Then - the nearest file holding a key supplies it, and the main checkout supplies the rest
        assertEquals("from-demo", secrets["lastfm.apikey"])
        assertEquals("from-repo-root", secrets["fanarttv.apikey"])
        assertEquals("from-main", secrets["discogs.token"])
    }
}
