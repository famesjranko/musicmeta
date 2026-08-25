package com.landofoz.musicmeta.demoweb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SecretsSearchTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun mainCheckout(name: String): File {
        val main = tmp.newFolder(name)
        File(main, ".git/worktrees/wt").mkdirs()
        return main
    }

    @Test
    fun `a worktree's git file resolves to the main checkout's secrets`() {
        // Given - a worktree whose .git file names an absolute gitdir under the main checkout
        val main = mainCheckout("main")
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
        val main = mainCheckout("main")
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

        // Then - no extra search-path entry
        assertNull(secrets)
    }

    @Test
    fun `a git file that is not a worktree pointer contributes nothing`() {
        // Given - a .git file whose gitdir does not end in worktrees slash name (a submodule shape)
        val root = tmp.newFolder("sub")
        File(root, ".git").writeText("gitdir: ../.git/modules/sub\n")

        // When - resolving the main checkout's secrets
        val secrets = mainCheckoutSecrets(root)

        // Then - no extra search-path entry
        assertNull(secrets)
    }

    @Test
    fun `an unparseable git file contributes nothing`() {
        // Given - a .git file with no gitdir line at all
        val root = tmp.newFolder("junk")
        File(root, ".git").writeText("not a pointer\n")

        // When - resolving the main checkout's secrets
        val secrets = mainCheckoutSecrets(root)

        // Then - no extra search-path entry
        assertNull(secrets)
    }
}
