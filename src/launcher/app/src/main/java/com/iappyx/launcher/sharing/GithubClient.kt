/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.sharing

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * GitHub REST client that mirrors the iappyxOS container app's
 * `github_service.dart`, adapted for the launcher's three artefact types
 * and the
 * [iappyxOS-Launcher-showcase](https://github.com/iappyx/iappyxOS-Launcher-showcase)
 * repo.
 *
 * Submit flow:
 *   1. `getUsername` (also validates the token)
 *   2. `ensureFork` (if user isn't the repo owner)
 *   3. `syncFork` (best-effort; non-fatal)
 *   4. `getRef` for the base branch SHA
 *   5. Optionally `deleteRef` if the per-slug branch already exists
 *   6. `createRef` for the new branch
 *   7. `createTree` with the artefact files
 *   8. `createCommit` + `updateRef`
 *   9. `createPR`
 *
 * All network calls run on the caller's thread — caller is responsible
 * for using a worker (the launcher's existing AI / showcase fetchers
 * already follow this pattern).
 */
class GithubClient(private val token: String) {

    companion object {
        const val OWNER = "iappyx"
        const val REPO = "iappyxOS-Launcher-showcase"
        const val BASE_BRANCH = "main"
        private const val API = "https://api.github.com"
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json".toMediaType()

    private var cachedUsername: String? = null

    /** Tree entry — either inline text content (for HTML / JSON files) or
     *  a pre-created blob SHA (for binary files like screenshots). */
    sealed class TreeEntry {
        abstract val path: String
        data class Text(override val path: String, val content: String) : TreeEntry()
        data class Blob(override val path: String, val sha: String) : TreeEntry()
    }

    /** Validate the token and return the authenticated user's GitHub login.
     *  Throws [GithubException] on 401 / network failure. Cached so repeat
     *  submissions in the same session don't re-hit `/user`. */
    @Throws(GithubException::class)
    fun getUsername(): String {
        cachedUsername?.let { return it }
        val resp = http.newCall(authed("$API/user").get().build()).execute()
        resp.use { r ->
            if (!r.isSuccessful) {
                throw GithubException(
                    if (r.code == 401) "GitHub token is invalid or expired."
                    else "Couldn't reach GitHub (${r.code}).",
                )
            }
            val login = JSONObject((r.body?.string() ?: throw GithubException("Empty GitHub response."))).optString("login")
            if (login.isBlank()) throw GithubException("GitHub returned an empty username.")
            cachedUsername = login
            return login
        }
    }

    /** Submit one artefact (widget / wallpaper / transition) as a PR.
     *  Returns the PR's `html_url`. The caller has already pre-flighted
     *  user input (slug uniqueness etc.) — this function just executes
     *  the upload pipeline. */
    @Throws(GithubException::class)
    fun submitArtefact(
        kindFolder: String,    // "widgets" | "wallpapers" | "transitions"
        slug: String,           // kebab-case folder name under {kindFolder}/
        contentFileName: String, // "widget.html" | "wallpaper.html" | "spec.json"
        contentText: String,
        metaJson: String,
        title: String,
        description: String,
        attribution: List<String> = emptyList(),
    ): String {
        val username = getUsername()
        val isOwner = username.equals(OWNER, ignoreCase = true)
        val repoFullName = if (isOwner) "$OWNER/$REPO" else "$username/$REPO"
        val branch = "showcase/$kindFolder/$slug"

        if (!isOwner) {
            ensureFork(username)
            syncFork(username) // best-effort
        }

        val mainSha = getRef(repoFullName, "heads/$BASE_BRANCH")
        // If a previous attempt left the branch behind, replace it.
        try { deleteRef(repoFullName, "heads/$branch") } catch (_: GithubException) {}
        createRef(repoFullName, "refs/heads/$branch", mainSha)

        val readme = buildReadme(title, description, attribution)
        val treeEntries = listOf(
            TreeEntry.Text("$kindFolder/$slug/$contentFileName", contentText),
            TreeEntry.Text("$kindFolder/$slug/meta.json", metaJson),
            TreeEntry.Text("$kindFolder/$slug/README.md", readme),
        )
        val baseTree = getTreeSha(repoFullName, mainSha)
        val treeSha = createTree(repoFullName, baseTree, treeEntries)
        val commitSha = createCommit(
            repoFullName, "Add $kindFolder/$slug ($title)", treeSha, mainSha,
        )
        updateRef(repoFullName, "heads/$branch", commitSha)

        val prHead = if (isOwner) branch else "$username:$branch"
        return createPR(
            head = prHead,
            title = "Showcase: $title",
            body = buildPrBody(title, description, kindFolder, attribution),
        )
    }

    // ── internals ─────────────────────────────────────────────────

    private fun authed(url: String) = Request.Builder()
        .url(url)
        .header("Authorization", "token $token")
        .header("Accept", "application/vnd.github.v3+json")

    private fun ensureFork(username: String) {
        val check = http.newCall(authed("$API/repos/$username/$REPO").get().build()).execute()
        check.use { if (it.isSuccessful) return }

        val fork = http.newCall(
            authed("$API/repos/$OWNER/$REPO/forks")
                .post("".toRequestBody(jsonMediaType)).build(),
        ).execute()
        fork.use {
            if (it.code != 202 && it.code != 200) {
                throw GithubException("Couldn't fork the showcase repo (${it.code}).")
            }
        }
        // Poll until the fork is reachable (max ~30 s).
        repeat(15) {
            try { Thread.sleep(2000) } catch (_: InterruptedException) {}
            val poll = http.newCall(
                authed("$API/repos/$username/$REPO").get().build(),
            ).execute()
            poll.use { if (it.isSuccessful) return }
        }
        throw GithubException("Fork wasn't ready after 30 seconds.")
    }

    private fun syncFork(username: String) {
        try {
            val body = JSONObject().put("branch", BASE_BRANCH).toString()
                .toRequestBody(jsonMediaType)
            http.newCall(
                authed("$API/repos/$username/$REPO/merge-upstream").post(body).build(),
            ).execute().close()
            // 200 / 409 are both fine. Failure here is non-fatal — fork
            // may be slightly stale but PR can still proceed.
        } catch (_: Throwable) { /* best effort */ }
    }

    private fun getRef(repo: String, ref: String): String {
        // Newly-forked repos sometimes 404 on refs for a few seconds; retry.
        var lastError: String = "ref not ready"
        repeat(5) { attempt ->
            val resp = http.newCall(
                authed("$API/repos/$repo/git/ref/$ref").get().build(),
            ).execute()
            resp.use {
                if (it.isSuccessful) {
                    return JSONObject((it.body?.string() ?: throw GithubException("Empty GitHub response.")))
                        .getJSONObject("object").getString("sha")
                }
                lastError = "${it.code}"
            }
            if (attempt < 4) try { Thread.sleep(2000) } catch (_: InterruptedException) {}
        }
        throw GithubException("Couldn't read $ref: $lastError")
    }

    private fun createRef(repo: String, ref: String, sha: String) {
        val body = JSONObject().put("ref", ref).put("sha", sha).toString()
            .toRequestBody(jsonMediaType)
        http.newCall(
            authed("$API/repos/$repo/git/refs").post(body).build(),
        ).execute().use {
            if (it.code != 201) {
                throw GithubException("Couldn't create branch (${it.code}).")
            }
        }
    }

    private fun deleteRef(repo: String, ref: String) {
        http.newCall(
            authed("$API/repos/$repo/git/refs/$ref").delete().build(),
        ).execute().use {
            if (it.code != 204) {
                throw GithubException("Couldn't delete $ref (${it.code}).")
            }
        }
    }

    private fun updateRef(repo: String, ref: String, sha: String) {
        val body = JSONObject().put("sha", sha).put("force", true).toString()
            .toRequestBody(jsonMediaType)
        http.newCall(
            authed("$API/repos/$repo/git/refs/$ref").patch(body).build(),
        ).execute().use {
            if (!it.isSuccessful) {
                throw GithubException("Couldn't update branch (${it.code}).")
            }
        }
    }

    private fun getTreeSha(repo: String, commitSha: String): String {
        http.newCall(
            authed("$API/repos/$repo/git/commits/$commitSha").get().build(),
        ).execute().use {
            if (!it.isSuccessful) throw GithubException("Couldn't read commit (${it.code}).")
            return JSONObject((it.body?.string() ?: throw GithubException("Empty GitHub response."))).getJSONObject("tree").getString("sha")
        }
    }

    private fun createTree(repo: String, baseTree: String, entries: List<TreeEntry>): String {
        val tree = JSONArray()
        for (e in entries) {
            val node = JSONObject().apply {
                put("path", e.path)
                put("mode", "100644")
                put("type", "blob")
                when (e) {
                    is TreeEntry.Text -> put("content", e.content)
                    is TreeEntry.Blob -> put("sha", e.sha)
                }
            }
            tree.put(node)
        }
        val body = JSONObject().put("base_tree", baseTree).put("tree", tree).toString()
            .toRequestBody(jsonMediaType)
        http.newCall(
            authed("$API/repos/$repo/git/trees").post(body).build(),
        ).execute().use {
            if (it.code != 201) {
                throw GithubException("Couldn't build commit tree (${it.code}).")
            }
            return JSONObject((it.body?.string() ?: throw GithubException("Empty GitHub response."))).getString("sha")
        }
    }

    private fun createCommit(
        repo: String, message: String, treeSha: String, parentSha: String,
    ): String {
        val body = JSONObject().apply {
            put("message", message)
            put("tree", treeSha)
            put("parents", JSONArray().put(parentSha))
        }.toString().toRequestBody(jsonMediaType)
        http.newCall(
            authed("$API/repos/$repo/git/commits").post(body).build(),
        ).execute().use {
            if (it.code != 201) {
                throw GithubException("Couldn't create commit (${it.code}).")
            }
            return JSONObject((it.body?.string() ?: throw GithubException("Empty GitHub response."))).getString("sha")
        }
    }

    private fun createPR(head: String, title: String, body: String): String {
        val payload = JSONObject().apply {
            put("title", title); put("body", body); put("head", head); put("base", BASE_BRANCH)
        }.toString().toRequestBody(jsonMediaType)
        val resp = http.newCall(
            authed("$API/repos/$OWNER/$REPO/pulls").post(payload).build(),
        ).execute()
        resp.use {
            if (it.code == 201) {
                return JSONObject((it.body?.string() ?: throw GithubException("Empty GitHub response."))).getString("html_url")
            }
            // 422 = PR already open for this head — surface the existing one.
            if (it.code == 422) {
                val existing = http.newCall(
                    authed("$API/repos/$OWNER/$REPO/pulls?head=$head&state=open")
                        .get().build(),
                ).execute()
                existing.use { e ->
                    if (e.isSuccessful) {
                        val pulls = JSONArray((e.body?.string() ?: throw GithubException("Empty GitHub response.")))
                        if (pulls.length() > 0) {
                            return pulls.getJSONObject(0).getString("html_url")
                        }
                    }
                }
                throw GithubException("A pull request for this slug is already open.")
            }
            throw GithubException("Couldn't open the pull request (${it.code}).")
        }
    }

    private fun buildReadme(title: String, description: String, attribution: List<String>): String {
        val sb = StringBuilder()
        sb.append("# ").append(title).append("\n\n")
        sb.append(description).append("\n")
        if (attribution.isNotEmpty()) {
            sb.append("\n**Uses:**\n")
            for (a in attribution) sb.append("- ").append(a).append("\n")
        }
        return sb.toString()
    }

    private fun buildPrBody(
        title: String, description: String, kindFolder: String, attribution: List<String>,
    ): String {
        val sb = StringBuilder()
        sb.append("**").append(title).append("**\n\n")
        sb.append(description).append("\n\n")
        sb.append("Submitted via the iappyxOS Launcher app — folder ")
        sb.append("`").append(kindFolder).append("/`.")
        if (attribution.isNotEmpty()) {
            sb.append("\n\n**Uses:**\n")
            for (a in attribution) sb.append("- ").append(a).append("\n")
        }
        return sb.toString()
    }
}

class GithubException(message: String) : IOException(message)
