package com.samples.pusher.core


import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.samples.pusher.core.model.PusherConfiruration
import com.samples.pusher.core.utils.cloneRepository
import com.samples.verifier.Code
import com.samples.verifier.GitException
import com.samples.verifier.model.CollectionOfRepository
import com.samples.verifier.model.ExecutionResult
import org.eclipse.egit.github.core.PullRequest
import org.eclipse.egit.github.core.PullRequestMarker
import org.eclipse.egit.github.core.RepositoryId
import org.eclipse.egit.github.core.client.GitHubClient
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.slf4j.LoggerFactory
import java.io.File
import java.lang.Exception
import kotlin.random.Random.Default.nextInt

//data class AuthData(val name: String, val password: String)

typealias CollectionSamples = Map<Code, ExecutionResult>
internal class SamplesPusher(val url: String, val path: String,
                             val user: String, val password: String = "",
                             val branch: String = "master") {
    private val logger = LoggerFactory.getLogger("Samples Pusher")

    var configuraton: PusherConfiruration = PusherConfiruration()
    fun readConfigFromFile(filename:String): SamplesPusher {
        configuraton.readFromFile(filename)
        return this
    }
    fun push(inputFile: String) {
        val mapper = jacksonObjectMapper()
        val res = mapper.readValue(File(inputFile), object : TypeReference<CollectionOfRepository>() {})
        val dir = File(url.substringAfterLast('/').substringBeforeLast('.'))

        try {
            logger.debug("Cloning the repository... ")
            val git = cloneRepository(dir, url, branch)
            val dirSamples = prepareTargetPath(dir)

            val branchName = "new-branch-${nextInt()}"
            git.checkout().setCreateBranch(true).setName(branchName).call()

            writeAndDeleteSnippets(dirSamples, res.snippets, res?.diff?.deletedFiles ?: emptyList<String>() )
            logger.debug(".kt files are  written")

            commitAndPush(git)
            logger.debug(".kt are pushed")

            val client = createGHClient()
            createPR(client, branchName)

        } catch (e: GitException) {
            logger.error("${e.message}")
        } finally {
            if (dir.isDirectory) {
                dir.deleteRecursively()
            } else {
                dir.delete()
            }
        }
    }

    private fun prepareTargetPath(repoDir: File): File {
        val dirSamples = repoDir.resolve(path)
        if (!dirSamples.exists())
            if (!dirSamples.mkdirs()) {
                throw Exception("Can't creare directory by path ${dirSamples.path}")
            }

        logger.debug("Created the path ${dirSamples.path}")
        return dirSamples
    }
    private fun writeAndDeleteSnippets(dirSamples:File, res:CollectionSamples, deleteFiles: List<String>) {
        val mng = SnippetManager(dirSamples)
        deleteFiles.forEach{ mng.removeAllSnippets(it)}
        res.forEach {
            if (it.value.errors.isNotEmpty()) {
                logger.error("Filename: ${it.value.fileName}")
                logger.error("Code: \n${it.key}")
                logger.error("Errors: \n${it.value.errors.joinToString("\n")}")
                // Create issue!!!
            } else {
                mng.addSnippet(it.key, it.value.fileName)
            }
        }

    }

    private fun commitAndPush(git: Git) {
        git.add().addFilepattern(".").call()
        git.commit().setAll(true).setAllowEmpty(true).setMessage("New samples").setCommitter(configuraton.committerName, configuraton.committerEmail).call()

        val credentialsProvider: CredentialsProvider = UsernamePasswordCredentialsProvider(user, password)
        git.push().setRemote(url).setCredentialsProvider(credentialsProvider).call()
    }

    private fun createGHClient(): GitHubClient {
        val client = GitHubClient()

        if(password.isEmpty())
            client.setOAuth2Token(user)
        else
            client.setCredentials(user, password)
        return client
    }

    private fun createPR(client:GitHubClient, headBranch: String) {
        val prServise = org.eclipse.egit.github.core.service.PullRequestService(client)
        var pr = PullRequest()
        pr.setTitle("New samples")
        pr.setBody("New files")
        pr.setBase(PullRequestMarker().setLabel(configuraton.baseBranchPR))
        pr.setHead(PullRequestMarker().setLabel(headBranch))
        logger.info(RepositoryId.createFromUrl(url).generateId())
        pr = prServise.createPullRequest(RepositoryId.createFromUrl(url), pr)
        logger.debug("Push request  is created ${pr.url}")
    }

}