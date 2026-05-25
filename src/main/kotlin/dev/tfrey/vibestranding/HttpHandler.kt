package dev.tfrey.vibestranding

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.QueryStringDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.ide.HttpRequestHandler

/**
 * HTTP entry points for the Vibe Stranding plugin. Hosts both the REST API
 * (curl/scripting) and the MCP endpoint (Claude Code) on the IDE's built-in
 * HTTP server, default port 63342.
 *
 *   --- REST (query-param style, plain-text or JSON responses) ---
 *
 *   GET|POST /api/vibe-stranding/list                         (JSON array)
 *   GET|POST /api/vibe-stranding/get?name=<kebab>             (JSON object)
 *   POST     /api/vibe-stranding/new?description=<free text>
 *   POST     /api/vibe-stranding/new?name=<kebab>[&emoji=<one-emoji>]
 *   POST     /api/vibe-stranding/finish?name=<kebab>[&delete=true]
 *   POST     /api/vibe-stranding/delete?name=<kebab>[&force=true]
 *   POST     /api/vibe-stranding/busy?name=<kebab>      (hook: Claude turn started)
 *   POST     /api/vibe-stranding/idle?name=<kebab>      (hook: Claude turn ended)
 *   GET|POST /api/vibe-stranding/settings              (JSON object — current settings)
 *   POST     /api/vibe-stranding/settings?<key>=<val>… (apply updates atomically, return new JSON)
 *
 *   --- MCP (JSON-RPC over HTTP, for Claude Code) ---
 *
 *   POST     /api/vibe-stranding/mcp
 *       Streamable-HTTP MCP transport. Body is a single JSON-RPC 2.0
 *       request; response is the corresponding JSON-RPC reply (or 202 for
 *       notifications). Supported methods: initialize, notifications/
 *       initialized, ping, tools/list, tools/call. Tools mirror the REST
 *       routes one-for-one.
 *
 * All routes take an optional `project=<name|basePath>` (query param or, for
 * MCP, tool argument) to pick which IDE window to act on when more than one
 * is open. With a single open project, the parameter can be omitted.
 *
 * Threading: git work runs inline on the Netty thread. Terminal-tab
 * operations are bounced to the EDT via invokeAndWait. These are lightly
 * used personal-workflow endpoints, not a high-RPS surface.
 */
class HttpHandler : HttpRequestHandler() {

    override fun isSupported(request: FullHttpRequest): Boolean {
        if (request.method() != HttpMethod.GET && request.method() != HttpMethod.POST) return false
        return request.uri().substringBefore('?').startsWith(BASE_PATH)
    }

    override fun process(
        urlDecoder: QueryStringDecoder,
        request: FullHttpRequest,
        context: ChannelHandlerContext,
    ): Boolean {
        val endpoint = urlDecoder.path().removePrefix(BASE_PATH).substringBefore('/')

        if (endpoint == "mcp") {
            return handleMcp(request, context)
        }

        val params = urlDecoder.parameters().mapValues { it.value.firstOrNull().orEmpty() }
        val project = resolveProject(params["project"])
        if (project == null) {
            respond(request, context, HttpResponseStatus.BAD_REQUEST, MISSING_PROJECT_MESSAGE + "\n")
            return true
        }

        val result = when (endpoint) {
            "new" -> doNew(project, params)
            "resume" -> doResume(project, params)
            "finish" -> doFinish(project, params)
            "delete" -> doDelete(project, params)
            "list" -> doList(project)
            "get" -> doGet(project, params)
            "busy" -> doSetBusy(project, params, busy = true)
            "idle" -> doSetBusy(project, params, busy = false)
            "settings" -> doSettings(project, params)
            else -> OpResult(HttpResponseStatus.NOT_FOUND, "Unknown endpoint: $endpoint")
        }
        val contentType = if (result.isJson) "application/json; charset=utf-8" else "text/plain; charset=utf-8"
        val body = if (result.isJson) result.text else result.text + "\n"
        respond(request, context, result.status, body, contentType)
        return true
    }

    // ---------- Shared operation layer ---------------------------------------

    /** Outcome of one plugin operation, suitable for either REST or MCP framing. */
    private data class OpResult(val status: HttpResponseStatus, val text: String, val isJson: Boolean = false) {
        val isOk: Boolean get() = status.code() in 200..299
    }

    private fun doNew(project: Project, params: Map<String, String>): OpResult {
        val explicitName = params["name"]?.trim().orEmpty()
        val description = params["description"]?.trim().orEmpty()
        val explicitEmoji = params["emoji"]?.takeIf { it.isNotBlank() }

        val (strand, inferredEmoji) = when {
            explicitName.isNotEmpty() -> {
                if (!STRAND_NAME.matches(explicitName)) {
                    return OpResult(
                        HttpResponseStatus.BAD_REQUEST,
                        "Invalid 'name' '$explicitName' (must match ${STRAND_NAME.pattern}).",
                    )
                }
                explicitName to fallbackEmoji(explicitName)
            }
            description.isNotEmpty() -> {
                // Naive slug is authoritative for the strand id (= dir +
                // branch name), so the tab label always matches on-disk
                // identity. LLM is consulted for an emoji only.
                val slug = StrandNamer.naiveSlug(description)
                val emoji = StrandNamer.suggestEmoji(description) ?: fallbackEmoji(slug)
                slug to emoji
            }
            else -> return OpResult(
                HttpResponseStatus.BAD_REQUEST,
                "Pass either 'name' (kebab slug) or 'description' (free text).",
            )
        }

        val emoji = explicitEmoji ?: inferredEmoji
        val svc = service(project)
        val background = pickStrandBackground(
            svc.listStrands().mapNotNull { svc.metadata.read(it)?.background },
        )

        return when (val r = svc.createStrand(strand)) {
            is GitStrands.CreateResult.Ok -> {
                svc.metadata.write(
                    strand,
                    StrandMeta(emoji, description.takeIf { it.isNotEmpty() }, background),
                )
                project.getService(StrandDescriber::class.java).schedule(strand)
                TerminalTabs.openTerminalTab(
                    project,
                    r.path.toString(),
                    tabLabel(emoji, strand),
                    STRAND_COMMAND,
                    strand,
                    background,
                )
                val warn = if (r.linkIssues.isEmpty()) "" else "\nSymlink issues:\n${r.linkIssues.joinToString("\n")}"
                OpResult(HttpResponseStatus.OK, "Created strand '$strand' $emoji at ${r.path}$warn")
            }
            is GitStrands.CreateResult.Failed ->
                OpResult(HttpResponseStatus.CONFLICT, r.message)
        }
    }

    private fun doResume(project: Project, params: Map<String, String>): OpResult {
        val strand = requireStrandName(params)
            ?: return OpResult(HttpResponseStatus.BAD_REQUEST, "Missing or invalid 'name'.")
        val svc = service(project)
        if (strand !in svc.listStrands()) {
            return OpResult(HttpResponseStatus.NOT_FOUND, "No strand named '$strand'.")
        }
        // Self-heal symlinks + busy hooks before reopening: a previous spawn
        // may have failed silently, the strand may predate the busy-hook
        // feature, or the IDE's built-in HTTP port may have changed since
        // last create.
        val issues = svc.ensureLinks(strand).toMutableList()
        issues += svc.ensureClaudeHooks(strand)
        val meta = svc.metadata.read(strand)
        val emoji = meta?.emoji ?: fallbackEmoji(strand)
        var focused = false
        ApplicationManager.getApplication().invokeAndWait {
            focused = TerminalTabs.focusTabForStrand(project, strand)
            if (!focused) {
                TerminalTabs.openTerminalTab(
                    project,
                    svc.strandPath(strand).toString(),
                    tabLabel(emoji, strand),
                    RESUME_COMMAND,
                    strand,
                    meta?.background,
                )
            }
        }
        val verb = if (focused) "Focused" else "Resumed"
        val warn = if (issues.isEmpty()) "" else "\nSetup issues:\n${issues.joinToString("\n")}"
        return OpResult(HttpResponseStatus.OK, "$verb '$strand'.$warn")
    }

    private fun doFinish(project: Project, params: Map<String, String>): OpResult {
        val strand = requireStrandName(params)
            ?: return OpResult(HttpResponseStatus.BAD_REQUEST, "Missing or invalid 'name'.")
        val alsoDelete = params["delete"]?.lowercase() == "true"
        val svc = service(project)

        return when (val r = svc.finishStrand(strand)) {
            is GitStrands.FinishResult.Finished -> {
                if (!alsoDelete) {
                    OpResult(HttpResponseStatus.OK, "Finished '$strand' (${r.commits} commit(s)).")
                } else {
                    val td = svc.deleteStrand(strand, force = false)
                    if (td.ok) {
                        ApplicationManager.getApplication().invokeAndWait {
                            TerminalTabs.closeTabForStrand(project, strand)
                        }
                        OpResult(
                            HttpResponseStatus.OK,
                            "Finished '$strand' (${r.commits} commit(s)) and deleted the worktree.",
                        )
                    } else {
                        OpResult(
                            HttpResponseStatus.OK,
                            "Finished '$strand' (${r.commits} commit(s)), " +
                                "but delete failed:\n${td.stderr}",
                        )
                    }
                }
            }
            GitStrands.FinishResult.NothingToFinish -> OpResult(
                HttpResponseStatus.OK,
                "'$strand' is already up to date; nothing to finish.",
            )
            is GitStrands.FinishResult.Conflict -> OpResult(
                HttpResponseStatus.CONFLICT,
                "Rebase of '$strand' hit conflicts. Resolve them in ${r.worktree}, " +
                    "run 'git rebase --continue', then finish again.",
            )
            is GitStrands.FinishResult.Failed -> OpResult(
                HttpResponseStatus.INTERNAL_SERVER_ERROR,
                r.message,
            )
        }
    }

    private fun doDelete(project: Project, params: Map<String, String>): OpResult {
        val strand = requireStrandName(params)
            ?: return OpResult(HttpResponseStatus.BAD_REQUEST, "Missing or invalid 'name'.")
        val force = params["force"]?.lowercase() == "true"
        val r = service(project).deleteStrand(strand, force)
        if (!r.ok) return OpResult(HttpResponseStatus.INTERNAL_SERVER_ERROR, r.stderr)
        ApplicationManager.getApplication().invokeAndWait {
            TerminalTabs.closeTabForStrand(project, strand)
        }
        return OpResult(HttpResponseStatus.OK, "Deleted '$strand'.")
    }

    private fun doList(project: Project): OpResult {
        val svc = service(project)
        val array = buildJsonArray {
            svc.listStrands().forEach { name ->
                add(strandJson(name, svc.metadata.read(name)))
            }
        }
        return OpResult(HttpResponseStatus.OK, JSON.encodeToString(JsonElement.serializer(), array), isJson = true)
    }

    /**
     * Toggle the strand's tab into / out of a "Claude is working" animation.
     * Hook-only endpoint — wired up by the per-strand `.claude/settings.local.json`
     * written at strand creation, so Claude's UserPromptSubmit hook flips it
     * busy and Stop flips it idle.
     */
    private fun doSetBusy(project: Project, params: Map<String, String>, busy: Boolean): OpResult {
        val strand = requireStrandName(params)
            ?: return OpResult(HttpResponseStatus.BAD_REQUEST, "Missing or invalid 'name'.")
        TerminalTabs.setBusy(project, strand, busy)
        return OpResult(HttpResponseStatus.OK, if (busy) "busy" else "idle")
    }

    /**
     * Read with no setting args, or atomically update any subset of
     * [VibeStrandingSettings.FIELDS] keys and return the new state. The
     * `project` param is filtered out so it isn't treated as a setting.
     */
    private fun doSettings(project: Project, params: Map<String, String>): OpResult {
        val settings = VibeStrandingSettings.get(project)
        val updates = params.filterKeys { it != "project" }
        if (updates.isNotEmpty()) {
            val errors = settings.applyUpdates(updates)
            if (errors.isNotEmpty()) {
                return OpResult(HttpResponseStatus.BAD_REQUEST, errors.joinToString("\n"))
            }
        }
        return OpResult(
            HttpResponseStatus.OK,
            JSON.encodeToString(JsonElement.serializer(), settings.snapshot()),
            isJson = true,
        )
    }

    private fun doGet(project: Project, params: Map<String, String>): OpResult {
        val strand = requireStrandName(params)
            ?: return OpResult(HttpResponseStatus.BAD_REQUEST, "Missing or invalid 'name'.")
        val svc = service(project)
        if (strand !in svc.listStrands()) {
            return OpResult(HttpResponseStatus.NOT_FOUND, "No strand named '$strand'.")
        }
        val json = strandJson(strand, svc.metadata.read(strand))
        return OpResult(HttpResponseStatus.OK, JSON.encodeToString(JsonElement.serializer(), json), isJson = true)
    }

    private fun strandJson(name: String, meta: StrandMeta?): JsonObject = buildJsonObject {
        put("name", name)
        put("emoji", meta?.emoji?.let(::JsonPrimitive) ?: JsonNull)
        put("description", meta?.description?.let(::JsonPrimitive) ?: JsonNull)
        put("generated_description", meta?.generatedDescription?.let(::JsonPrimitive) ?: JsonNull)
        put("color", meta?.background?.let(::JsonPrimitive) ?: JsonNull)
    }

    // ---------- MCP dispatcher ----------------------------------------------

    private fun handleMcp(request: FullHttpRequest, context: ChannelHandlerContext): Boolean {
        if (request.method() != HttpMethod.POST) {
            respond(
                request,
                context,
                HttpResponseStatus.METHOD_NOT_ALLOWED,
                "MCP endpoint accepts POST only.\n",
            )
            return true
        }

        val body = request.content().toString(Charsets.UTF_8)
        val rpc = try {
            JSON.parseToJsonElement(body).jsonObject
        } catch (t: Throwable) {
            respondJson(request, context, jsonRpcError(null, -32700, "Parse error: ${t.message}"))
            return true
        }

        val id = rpc["id"]
        val method = rpc["method"]?.jsonPrimitive?.contentOrNull
        if (method == null) {
            respondJson(request, context, jsonRpcError(id, -32600, "Invalid request: missing 'method'"))
            return true
        }
        val params = rpc["params"]?.jsonObject

        // Notifications carry no id and expect no JSON-RPC body — just a bare
        // 202 Accepted.
        val isNotification = id == null

        try {
            val result = dispatchMcp(method, params)
            if (isNotification) {
                respond(request, context, HttpResponseStatus.ACCEPTED, "")
            } else {
                respondJson(request, context, jsonRpcSuccess(id, result))
            }
        } catch (t: McpMethodNotFound) {
            respondJson(request, context, jsonRpcError(id, -32601, "Method not found: ${t.method}"))
        } catch (t: McpInvalidParams) {
            respondJson(request, context, jsonRpcError(id, -32602, t.message ?: "Invalid params"))
        } catch (t: Throwable) {
            respondJson(
                request,
                context,
                jsonRpcError(id, -32603, "Internal error: ${t.javaClass.simpleName}: ${t.message}"),
            )
        }
        return true
    }

    private class McpMethodNotFound(val method: String) : RuntimeException(method)
    private class McpInvalidParams(message: String) : RuntimeException(message)

    private fun dispatchMcp(method: String, params: JsonObject?): JsonElement = when (method) {
        "initialize" -> buildJsonObject {
            put("protocolVersion", MCP_PROTOCOL_VERSION)
            putJsonObject("capabilities") {
                putJsonObject("tools") { /* no listChanged */ }
            }
            putJsonObject("serverInfo") {
                put("name", "vibe-stranding")
                put("version", "0.1.0")
            }
        }
        "notifications/initialized" -> JsonNull
        "ping" -> buildJsonObject { /* empty pong */ }
        "tools/list" -> buildJsonObject {
            putJsonArray("tools") {
                add(TOOL_NEW_STRAND)
                add(TOOL_RESUME_STRAND)
                add(TOOL_FINISH_STRAND)
                add(TOOL_DELETE_STRAND)
                add(TOOL_LIST_STRANDS)
                add(TOOL_GET_STRAND)
                add(TOOL_SETTINGS)
            }
        }
        "tools/call" -> callTool(params)
        else -> throw McpMethodNotFound(method)
    }

    private fun callTool(params: JsonObject?): JsonElement {
        val toolName = params?.get("name")?.jsonPrimitive?.contentOrNull
            ?: throw McpInvalidParams("tools/call requires 'name'")
        val args = params["arguments"]?.jsonObject ?: buildJsonObject {}

        val project = resolveProject(args["project"]?.jsonPrimitive?.contentOrNull)
            ?: return toolError(MISSING_PROJECT_MESSAGE)

        // MCP tool args arrive as JSON values; the op layer wants strings
        // (matching the REST query-param shape). Booleans like `unravel:
        // true` flatten to "true", which is exactly what `doX` checks.
        val argMap = args.mapValues { (_, v) ->
            (v as? JsonPrimitive)?.contentOrNull.orEmpty()
        }

        val result = when (toolName) {
            "new_strand" -> doNew(project, argMap)
            "resume_strand" -> doResume(project, argMap)
            "finish_strand" -> doFinish(project, argMap)
            "delete_strand" -> doDelete(project, argMap)
            "list_strands" -> doList(project)
            "get_strand" -> doGet(project, argMap)
            "settings" -> doSettings(project, argMap)
            else -> return toolError("Unknown tool: $toolName")
        }
        return toolResult(result.text, isError = !result.isOk)
    }

    private fun toolResult(text: String, isError: Boolean): JsonObject = buildJsonObject {
        putJsonArray("content") {
            addJsonObject {
                put("type", "text")
                put("text", text)
            }
        }
        if (isError) put("isError", true)
    }

    private fun toolError(text: String): JsonObject = toolResult(text, isError = true)

    private fun jsonRpcSuccess(id: JsonElement?, result: JsonElement): String = JSON.encodeToString(
        JsonElement.serializer(),
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id ?: JsonNull)
            put("result", result)
        },
    )

    private fun jsonRpcError(id: JsonElement?, code: Int, message: String): String = JSON.encodeToString(
        JsonElement.serializer(),
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id ?: JsonNull)
            putJsonObject("error") {
                put("code", code)
                put("message", message)
            }
        },
    )

    // ---------- Helpers ------------------------------------------------------

    private fun resolveProject(hint: String?): Project? {
        val open = ProjectManager.getInstance().openProjects
        if (open.isEmpty()) return null
        if (hint.isNullOrBlank()) return open.singleOrNull()
        return open.firstOrNull { it.name == hint || it.basePath == hint }
    }

    private fun requireStrandName(params: Map<String, String>): String? {
        val name = params["name"]?.trim().orEmpty()
        return if (STRAND_NAME.matches(name)) name else null
    }

    private fun service(project: Project): GitStrands = project.getService(GitStrands::class.java)

    private fun respond(
        request: FullHttpRequest,
        context: ChannelHandlerContext,
        status: HttpResponseStatus,
        body: String,
        contentType: String = "text/plain; charset=utf-8",
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val response = DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            status,
            Unpooled.wrappedBuffer(bytes),
        )
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType)
        HttpUtil.setContentLength(response, bytes.size.toLong())

        // Honour the request's keep-alive header. `org.jetbrains.io.Responses`
        // would have done this for us, but it's internal-impl and not on the
        // plugin classpath, so we inline the equivalent Netty flow.
        val keepAlive = HttpUtil.isKeepAlive(request)
        if (keepAlive) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
        }
        val future = context.channel().writeAndFlush(response)
        if (!keepAlive) {
            future.addListener(ChannelFutureListener.CLOSE)
        }
    }

    private fun respondJson(request: FullHttpRequest, context: ChannelHandlerContext, json: String) =
        respond(request, context, HttpResponseStatus.OK, json, "application/json")

    private companion object {
        const val BASE_PATH = "/api/vibe-stranding/"
        const val MCP_PROTOCOL_VERSION = "2024-11-05"
        const val MISSING_PROJECT_MESSAGE =
            "No matching open project. Pass 'project' (name or basePath) if multiple IDE windows are open."

        private val JSON = Json { ignoreUnknownKeys = true }

        // ---- Tool schemas -------------------------------------------------
        //
        // Built once at class init. The descriptions are intentionally rich,
        // since they're how Claude decides when to call us.

        private val TOOL_NEW_STRAND = buildJsonObject {
            put("name", "new_strand")
            put(
                "description",
                "Start a new strand of work. Creates a git worktree off the default branch and opens " +
                    "a named terminal tab in the IDE running `claude`. " +
                    "Pass either a free-text `description` (the plugin coins a kebab slug + emoji via " +
                    "claude haiku) or an explicit `name`. " +
                    "Use whenever the user asks to start, create, spin up, or begin a " +
                    "strand / feature / branch of work.",
            )
            putJsonObject("inputSchema") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("description") {
                        put("type", "string")
                        put(
                            "description",
                            "Free-text description; the plugin picks a kebab slug + emoji. Preferred input.",
                        )
                    }
                    putJsonObject("name") {
                        put("type", "string")
                        put(
                            "description",
                            "Explicit kebab-case slug, e.g. 'oauth-cleanup'. Use only when the " +
                                "user supplied a name verbatim.",
                        )
                    }
                    putJsonObject("emoji") {
                        put("type", "string")
                        put("description", "Override the tab emoji. Set only when the user explicitly asked for one.")
                    }
                    putJsonObject("project") {
                        put("type", "string")
                        put("description", "Project name or basePath; needed only when multiple IDE windows are open.")
                    }
                }
            }
        }

        private val TOOL_RESUME_STRAND = buildJsonObject {
            put("name", "resume_strand")
            put(
                "description",
                "Resume an existing strand: focuses its terminal tab if one is open, otherwise opens " +
                    "a new tab in the strand's worktree running `claude --continue` to pick up the " +
                    "last session. Use when the user asks to resume, reopen, get back to, or jump " +
                    "into an existing strand / feature / branch whose tab is gone.",
            )
            putJsonObject("inputSchema") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("name") {
                        put("type", "string")
                        put("description", "Kebab slug of the strand to resume.")
                    }
                    putJsonObject("project") {
                        put("type", "string")
                        put("description", "Project name or basePath; needed only when multiple IDE windows are open.")
                    }
                }
                putJsonArray("required") { add("name") }
            }
        }

        private val TOOL_FINISH_STRAND = buildJsonObject {
            put("name", "finish_strand")
            put(
                "description",
                "Finish a strand: auto-commits outstanding work in its worktree, rebases onto " +
                    "main, fast-forwards. Set `delete: true` to also remove the worktree and close " +
                    "its tab on success. Use when the user asks to ship, land, merge, or finish a " +
                    "strand / feature / branch.",
            )
            putJsonObject("inputSchema") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("name") {
                        put("type", "string")
                        put("description", "Kebab slug of the strand to finish.")
                    }
                    putJsonObject("delete") {
                        put("type", "boolean")
                        put(
                            "description",
                            "If true, delete the worktree and close the tab after a successful finish.",
                        )
                    }
                    putJsonObject("project") {
                        put("type", "string")
                        put("description", "Project name or basePath; needed only when multiple IDE windows are open.")
                    }
                }
                putJsonArray("required") { add("name") }
            }
        }

        private val TOOL_DELETE_STRAND = buildJsonObject {
            put("name", "delete_strand")
            put(
                "description",
                "Delete a strand without finishing it: removes the worktree, deletes the branch, " +
                    "closes the tab. Use when the user asks to tear down, abandon, discard, or " +
                    "delete a strand / feature / branch. Set `force: true` only when the user " +
                    "explicitly wants to discard uncommitted or unmerged work.",
            )
            putJsonObject("inputSchema") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("name") {
                        put("type", "string")
                        put("description", "Kebab slug of the strand to delete.")
                    }
                    putJsonObject("force") {
                        put("type", "boolean")
                        put(
                            "description",
                            "If true, discard uncommitted/unmerged work. Default false (refuses if work would be lost).",
                        )
                    }
                    putJsonObject("project") {
                        put("type", "string")
                        put("description", "Project name or basePath; needed only when multiple IDE windows are open.")
                    }
                }
                putJsonArray("required") { add("name") }
            }
        }

        private val TOOL_LIST_STRANDS = buildJsonObject {
            put("name", "list_strands")
            put(
                "description",
                "List the currently active strands. Returns a JSON array of objects with " +
                    "`name` (kebab slug), `emoji`, `description` (the user's create-time prompt), " +
                    "`generated_description` (a one-sentence blurb produced by a background LM call " +
                    "a few minutes after creation; may be null while still pending or if generation " +
                    "failed), and `color` (hex like `#4E78A0`). Prefer `generated_description` over " +
                    "`description` when both are present — it reflects what the strand actually " +
                    "turned out to be about. Any field may be null for strands created without " +
                    "metadata. Use to disambiguate when the user refers to a strand without its " +
                    "exact slug, or to confirm what's in flight.",
            )
            putJsonObject("inputSchema") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("project") {
                        put("type", "string")
                        put("description", "Project name or basePath; needed only when multiple IDE windows are open.")
                    }
                }
            }
        }

        private val TOOL_SETTINGS = buildJsonObject {
            put("name", "settings")
            put(
                "description",
                buildString {
                    append(
                        "Read or update this project's Vibe Stranding plugin settings. " +
                            "Call with no setting arguments to read the current values. " +
                            "Pass any subset of the setting names below as arguments to update them — " +
                            "only the keys you pass are changed, and an invalid key or value rejects " +
                            "the whole call without mutating anything. Returns the (possibly updated) " +
                            "settings as JSON.\n\nSettings:",
                    )
                    VibeStrandingSettings.FIELDS.forEach {
                        append(
                            "\n- ",
                        ).append(it.name).append(" (").append(it.jsonType).append("): ").append(it.description)
                    }
                },
            )
            putJsonObject("inputSchema") {
                put("type", "object")
                putJsonObject("properties") {
                    VibeStrandingSettings.FIELDS.forEach { field ->
                        putJsonObject(field.name) {
                            put("type", field.jsonType)
                            put("description", field.description)
                        }
                    }
                    putJsonObject("project") {
                        put("type", "string")
                        put("description", "Project name or basePath; needed only when multiple IDE windows are open.")
                    }
                }
            }
        }

        private val TOOL_GET_STRAND = buildJsonObject {
            put("name", "get_strand")
            put(
                "description",
                "Fetch metadata for one strand: returns a JSON object with `name`, `emoji`, " +
                    "`description` (create-time prompt), `generated_description` (background-" +
                    "generated one-liner; may be null), and `color` (same shape as a single entry " +
                    "from `list_strands`). Prefer `generated_description` over `description` when " +
                    "both are present. Use when the user asks what a strand is about, or when you " +
                    "need richer context than the slug alone.",
            )
            putJsonObject("inputSchema") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("name") {
                        put("type", "string")
                        put("description", "Kebab slug of the strand to look up.")
                    }
                    putJsonObject("project") {
                        put("type", "string")
                        put("description", "Project name or basePath; needed only when multiple IDE windows are open.")
                    }
                }
                putJsonArray("required") { add("name") }
            }
        }
    }
}
