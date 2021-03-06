package org.sirix.rest.crud

import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.Context
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.ext.auth.User
import io.vertx.ext.auth.oauth2.OAuth2Auth
import io.vertx.ext.web.Route
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.impl.HttpStatusException
import io.vertx.kotlin.core.executeBlockingAwait
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.withContext
import org.brackit.xquery.XQuery
import org.sirix.access.Databases
import org.sirix.api.Database
import org.sirix.api.ResourceManager
import org.sirix.api.XdmNodeReadTrx
import org.sirix.exception.SirixUsageException
import org.sirix.rest.Serialize
import org.sirix.rest.SessionDBStore
import org.sirix.service.xml.serialize.XMLSerializer
import org.sirix.xquery.DBSerializer
import org.sirix.xquery.SirixCompileChain
import org.sirix.xquery.SirixQueryContext
import org.sirix.xquery.node.BasicDBStore
import org.sirix.xquery.node.DBCollection
import org.sirix.xquery.node.DBNode
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.ZoneId

class Get(private val location: Path, private val keycloak: OAuth2Auth) {
    suspend fun handle(ctx: RoutingContext): Route {
        val vertxContext = ctx.vertx().orCreateContext
        val dbName: String? = ctx.pathParam("database")
        val resName: String? = ctx.pathParam("resource")

        val query: String? = ctx.queryParam("query").getOrElse(0) { ctx.bodyAsString }

        if (dbName == null && resName == null) {
            if (query == null || query.isEmpty())
                listDatabases(ctx)
            else
                xquery(query, null, ctx, vertxContext, ctx.get("user") as User)
        } else {
            get(dbName, ctx, resName, query, vertxContext, ctx.get("user") as User)
        }

        return ctx.currentRoute()
    }

    private fun listDatabases(ctx: RoutingContext) {
        val databases = Files.list(location)

        val buffer = StringBuilder()

        buffer.appendln("<rest:sequence xmlns:rest=\"https://sirix.io/rest\">")

        databases.use {
            databases.filter { Files.isDirectory(it) }.forEach {
                buffer.appendln("  <rest:item database-name=\"${it.fileName}\"/>")
            }
        }

        buffer.append("</rest:sequence>")

        val content = buffer.toString()

        ctx.response().setStatusCode(200)
                .putHeader("Content-Type", "application/xml")
                .putHeader("Content-Length", content.length.toString())
                .write(content)
                .end()
    }

    private suspend fun get(dbName: String?, ctx: RoutingContext, resName: String?, query: String?,
                            vertxContext: Context, user: User) {
        val revision: String? = ctx.queryParam("revision").getOrNull(0)
        val revisionTimestamp: String? = ctx.queryParam("revision-timestamp").getOrNull(0)
        val startRevision: String? = ctx.queryParam("start-revision").getOrNull(0)
        val endRevision: String? = ctx.queryParam("end-revision").getOrNull(0)
        val startRevisionTimestamp: String? = ctx.queryParam("start-revision-timestamp").getOrNull(0)
        val endRevisionTimestamp: String? = ctx.queryParam("end-revision-timestamp").getOrNull(0)

        val nodeId: String? = ctx.queryParam("nodeId").getOrNull(0)

        val database: Database
        try {
            database = Databases.openDatabase(location.resolve(dbName))
        } catch (e: SirixUsageException) {
            ctx.fail(HttpStatusException(HttpResponseStatus.NOT_FOUND.code(), e))
            return
        }

        database.use {
            val manager: ResourceManager
            try {
                if (resName == null) {
                    val buffer = StringBuilder()
                    buffer.appendln("<rest:sequence xmlns:rest=\"https://sirix.io/rest\">")

                    for (resource in it.listResources()) {
                        buffer.appendln("  <rest:item resource-name=\"${resource.fileName}\"/>")
                    }

                    buffer.appendln("</rest:sequence>")
                } else {
                    manager = database.getResourceManager(resName)

                    manager.use {
                        if (query != null && query.isNotEmpty()) {
                            queryResource(dbName, database, revision, revisionTimestamp, manager, ctx, nodeId, query,
                                    vertxContext, user)
                        } else {
                            val revisions: Array<Int> =
                                    getRevisionsToSerialize(startRevision, endRevision, startRevisionTimestamp,
                                            endRevisionTimestamp, manager, revision, revisionTimestamp)
                            serializeResource(manager, revisions, nodeId?.toLongOrNull(), ctx)
                        }
                    }
                }
            } catch (e: SirixUsageException) {
                ctx.fail(HttpStatusException(HttpResponseStatus.NOT_FOUND.code(), e))
                return
            }

        }
    }

    private fun getRevisionsToSerialize(startRevision: String?, endRevision: String?, startRevisionTimestamp: String?,
                                        endRevisionTimestamp: String?, manager: ResourceManager, revision: String?,
                                        revisionTimestamp: String?): Array<Int> {
        return when {
            startRevision != null && endRevision != null -> parseIntRevisions(startRevision, endRevision)
            startRevisionTimestamp != null && endRevisionTimestamp != null -> {
                val tspRevisions = parseTimestampRevisions(startRevisionTimestamp, endRevisionTimestamp)
                getRevisionNumbers(manager, tspRevisions).toList().toTypedArray()
            }
            else -> getRevisionNumber(revision, revisionTimestamp, manager)
        }
    }

    private suspend fun queryResource(dbName: String?, database: Database, revision: String?,
                                      revisionTimestamp: String?, manager: ResourceManager, ctx: RoutingContext,
                                      nodeId: String?, query: String, vertxContext: Context, user: User) {
        withContext(vertxContext.dispatcher()) {
            val dbCollection = DBCollection(dbName, database)

            dbCollection.use {
                val revisionNumber = getRevisionNumber(revision, revisionTimestamp, manager)

                val trx: XdmNodeReadTrx
                try {
                    trx = manager.beginNodeReadTrx(revisionNumber[0])

                    trx.use {
                        if (nodeId == null)
                            trx.moveToFirstChild()
                        else
                            trx.moveTo(nodeId.toLong())

                        val dbNode = DBNode(trx, dbCollection)

                        xquery(query, dbNode, ctx, vertxContext, user)
                    }
                } catch (e: SirixUsageException) {
                    ctx.fail(HttpStatusException(HttpResponseStatus.NOT_FOUND.code(), e))
                }
            }
        }
    }

    private fun getRevisionNumber(rev: String?, revTimestamp: String?, manager: ResourceManager): Array<Int> {
        return if (rev != null) {
            arrayOf(rev.toInt())
        } else if (revTimestamp != null) {
            var revision = getRevisionNumber(manager, revTimestamp)
            if (revision == 0)
                arrayOf(++revision)
            else
                arrayOf(revision)
        } else {
            arrayOf(manager.mostRecentRevisionNumber)
        }
    }

    private suspend fun xquery(query: String, node: DBNode?, routingContext: RoutingContext, vertxContext: Context,
                               user: User) {
        vertxContext.executeBlockingAwait(Handler<Future<Nothing>> {
            // Initialize queryResource context and store.
            val dbStore = SessionDBStore(BasicDBStore.newBuilder().build(), user)

            dbStore.use {
                val queryCtx = SirixQueryContext(dbStore)

                node.let { queryCtx.contextItem = node }

                val out = ByteArrayOutputStream()

                out.use {
                    PrintStream(out).use {
                        XQuery(SirixCompileChain(dbStore), query).prettyPrint().serialize(queryCtx,
                                DBSerializer(it, true, true))
                    }

                    val body = String(out.toByteArray(), StandardCharsets.UTF_8)

                    routingContext.response().setStatusCode(200)
                            .putHeader("Content-Type", "application/xml")
                            .putHeader("Content-Length", body.length.toString())
                            .write(body)
                            .end()
                }
            }

            it.complete(null)
        })
    }

    private fun getRevisionNumber(manager: ResourceManager, revision: String): Int {
        val revisionDateTime = LocalDateTime.parse(revision)
        val zdt = revisionDateTime.atZone(ZoneId.systemDefault())
        return manager.getRevisionNumber(zdt.toInstant())
    }

    private fun getRevisionNumbers(manager: ResourceManager,
                                   revisions: Pair<LocalDateTime, LocalDateTime>): Array<Int> {
        val zdtFirstRevision = revisions.first.atZone(ZoneId.systemDefault())
        val zdtLastRevision = revisions.second.atZone(ZoneId.systemDefault())
        var firstRevisionNumber = manager.getRevisionNumber(zdtFirstRevision.toInstant())
        var lastRevisionNumber = manager.getRevisionNumber(zdtLastRevision.toInstant())

        if (firstRevisionNumber == 0) ++firstRevisionNumber
        if (lastRevisionNumber == 0) ++lastRevisionNumber

        return (firstRevisionNumber..lastRevisionNumber).toSet().toTypedArray()
    }

    private fun serializeResource(manager: ResourceManager, revisions: Array<Int>, nodeId: Long?, ctx: RoutingContext) {
        val out = ByteArrayOutputStream()

        val serializerBuilder = XMLSerializer.XMLSerializerBuilder(manager, out).revisions(revisions.toIntArray())

        nodeId?.let { serializerBuilder.startNodeKey(nodeId) }

        val serializer = serializerBuilder.emitIDs().emitRESTful().emitRESTSequence().prettyPrint().build()

        Serialize().serializeXml(serializer, out, ctx)
    }

    private fun parseIntRevisions(startRevision: String, endRevision: String): Array<Int> {
        return (startRevision.toInt()..endRevision.toInt()).toSet().toTypedArray()
    }

    private fun parseTimestampRevisions(startRevision: String,
                                        endRevision: String): Pair<LocalDateTime, LocalDateTime> {
        val firstRevisionDateTime = LocalDateTime.parse(startRevision)
        val lastRevisionDateTime = LocalDateTime.parse(endRevision)

        return Pair(firstRevisionDateTime, lastRevisionDateTime)
    }
}