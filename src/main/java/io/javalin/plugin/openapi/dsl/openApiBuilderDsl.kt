/**
 * This file contains helper methods which convert javalin concepts
 * to OpenApi documentation.
 */
package io.javalin.plugin.openapi.dsl

import io.javalin.core.PathParser
import io.javalin.core.PathSegment
import io.javalin.core.event.HandlerMetaInfo
import io.javalin.core.util.OptionalDependency
import io.javalin.core.util.Util
import io.javalin.http.HandlerType
import io.javalin.plugin.openapi.CreateSchemaOptions
import io.javalin.plugin.openapi.annotations.HttpMethod
import io.javalin.plugin.openapi.annotations.PathInfo
import io.javalin.plugin.openapi.annotations.scanForAnnotations
import io.javalin.plugin.openapi.external.schema
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.Paths

fun Components.applyMetaInfoList(handlerMetaInfoList: List<HandlerMetaInfo>) {
    handlerMetaInfoList
            .mapNotNull { it.extractDocumentation() }
            .flatMap { it.componentsUpdaterList }
            .applyAllUpdates(this)
}

fun Paths.applyMetaInfoList(handlerMetaInfoList: List<HandlerMetaInfo>, options: CreateSchemaOptions) {
    handlerMetaInfoList
            .filter { it.extractDocumentation()?.let { !it.isIgnored } ?: true }
            .groupBy { it.path }
            .forEach { (url, metaInfos) ->
                val pathParser = PathParser(url)
                updatePath(pathParser.getOpenApiUrl()) {
                    applyMetaInfoList(options, pathParser, metaInfos)
                }
            }
}

fun PathParser.getOpenApiUrl(): String {
    val segmentsString = segments
            .joinToString("/") {
                when (it) {
                    is PathSegment.Normal -> it.content
                    /*
                     * At the moment, OpenApi does not support wildcards. So we just leave it as it is.
                     * Once it is implemented we can change this.
                     */
                    is PathSegment.Wildcard -> "*"
                    is PathSegment.Parameter -> "{${it.name}}"
                }
            }
    return "/$segmentsString"
}

fun PathItem.applyMetaInfoList(options: CreateSchemaOptions, path: PathParser, handlerMetaInfoList: List<HandlerMetaInfo>) {
    handlerMetaInfoList
            .forEach { metaInfo ->
                val pathItemHttpMethod = metaInfo.httpMethod.asPathItemHttpMethod() ?: return@forEach
                operation(pathItemHttpMethod, Operation().apply {
                    applyMetaInfo(options, path, metaInfo)
                })
            }
}


fun Operation.applyMetaInfo(options: CreateSchemaOptions, path: PathParser, metaInfo: HandlerMetaInfo) {
    var documentation = metaInfo.extractDocumentation()
    options.defaultOperation?.setup(this, documentation)

    // Use path scanning to get the documentation if activated
    if (documentation == null && options.packagePrefixesToScan.isNotEmpty()) {
        Util.ensureDependencyPresent(OptionalDependency.CLASS_GRAPH)
        metaInfo.getPathInfo()?.let { pathInfo ->
            val documentationFromScan = scanForAnnotations(options.packagePrefixesToScan)
            documentation = documentationFromScan[pathInfo]
        }
    }

    operationId = metaInfo.createDefaultOperationId(path)
    summary = metaInfo.createDefaultSummary(path)
    if (path.pathParamNames.isNotEmpty()) {
        path.pathParamNames.forEach { pathParamName ->
            updateParameter {
                name = pathParamName
                `in` = "path"
                required = true
                schema(String::class.java)
            }
        }
    }

    documentation?.let { documentation ->
        documentation.parameterUpdaterListMapping
                .values
                .forEach { updaters ->
                    updateParameter { updaters.applyAllUpdates(this) }
                }

        if (documentation.hasRequestBodies()) {
            updateRequestBody {
                documentation.requestBodyList.applyAllUpdates(this)
            }
        }

        if (documentation.hasResponses()) {
            updateResponses {
                documentation.responseUpdaterListMapping
                        .forEach { name, updater ->
                            updateResponse(name) {
                                updater.applyAllUpdates(this)
                            }
                        }
            }
        }
        documentation.operationUpdaterList.applyAllUpdates(this)
    }
}

fun HandlerMetaInfo.getPathInfo(): PathInfo? = httpMethod.asAnnotationHttpMethod()?.let { method ->
    PathInfo(path, method)
}

fun HandlerType.asAnnotationHttpMethod(): HttpMethod? = when (this) {
    HandlerType.GET -> HttpMethod.GET
    HandlerType.PUT -> HttpMethod.PUT
    HandlerType.POST -> HttpMethod.POST
    HandlerType.DELETE -> HttpMethod.DELETE
    HandlerType.OPTIONS -> HttpMethod.OPTIONS
    HandlerType.HEAD -> HttpMethod.HEAD
    HandlerType.PATCH -> HttpMethod.PATCH
    HandlerType.TRACE -> HttpMethod.TRACE
    else -> null
}

fun HandlerType.asPathItemHttpMethod(): PathItem.HttpMethod? = when (this) {
    HandlerType.GET -> PathItem.HttpMethod.GET
    HandlerType.PUT -> PathItem.HttpMethod.PUT
    HandlerType.POST -> PathItem.HttpMethod.POST
    HandlerType.DELETE -> PathItem.HttpMethod.DELETE
    HandlerType.OPTIONS -> PathItem.HttpMethod.OPTIONS
    HandlerType.HEAD -> PathItem.HttpMethod.HEAD
    HandlerType.PATCH -> PathItem.HttpMethod.PATCH
    HandlerType.TRACE -> PathItem.HttpMethod.TRACE
    else -> null
}

private fun HandlerMetaInfo.createDefaultOperationId(path: PathParser): String {
    val metaInfo = this
    val lowerCaseMethod = metaInfo.httpMethod.toString().toLowerCase()
    val capitalizedPath = path.asReadableWords().joinToString("") { it.capitalize() }
    return lowerCaseMethod + capitalizedPath
}

private fun HandlerMetaInfo.createDefaultSummary(path: PathParser): String {
    val metaInfo = this
    val capitalizedMethod = metaInfo.httpMethod.toString().toLowerCase().capitalize()
    return (listOf(capitalizedMethod) + path.asReadableWords()).joinToString(" ") { it.trim() }
}

private fun PathParser.asReadableWords(): List<String> {
    val words = mutableListOf<String>()
    segments.forEach { segment ->
        when (segment) {
            is PathSegment.Normal -> words.add(segment.content.dashCaseToCamelCase())
            is PathSegment.Wildcard -> words.addAll(arrayOf("with", "wildcard"))
            is PathSegment.Parameter -> {
                words.add("with")
                words.add(segment.name.dashCaseToCamelCase())
            }
        }
    }
    return words
}

private fun String.dashCaseToCamelCase() = split("-")
        .map { it.toLowerCase() }
        .mapIndexed { index, s -> if (index > 0) s.capitalize() else s }
        .joinToString("")
