/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.server.apis.publicapi.services

import io.airbyte.api.model.generated.ListResourcesForWorkspacesRequestBody
import io.airbyte.api.model.generated.Pagination
import io.airbyte.api.model.generated.PartialSourceUpdate
import io.airbyte.api.model.generated.SourceCreate
import io.airbyte.api.model.generated.SourceDiscoverSchemaRead
import io.airbyte.api.model.generated.SourceDiscoverSchemaRequestBody
import io.airbyte.api.model.generated.SourceIdRequestBody
import io.airbyte.api.model.generated.SourceUpdate
import io.airbyte.commons.server.handlers.SchedulerHandler
import io.airbyte.commons.server.handlers.SourceHandler
import io.airbyte.commons.server.support.CurrentUserService
import io.airbyte.public_api.model.generated.InitiateOauthRequest
import io.airbyte.public_api.model.generated.SourceCreateRequest
import io.airbyte.public_api.model.generated.SourcePatchRequest
import io.airbyte.public_api.model.generated.SourcePutRequest
import io.airbyte.public_api.model.generated.SourceResponse
import io.airbyte.public_api.model.generated.SourcesResponse
import io.airbyte.server.apis.publicapi.constants.HTTP_RESPONSE_BODY_DEBUG_MESSAGE
import io.airbyte.server.apis.publicapi.errorHandlers.ConfigClientErrorHandler
import io.airbyte.server.apis.publicapi.mappers.SourceReadMapper
import io.airbyte.server.apis.publicapi.mappers.SourcesResponseMapper
import io.airbyte.server.apis.publicapi.problems.UnexpectedProblem
import io.micronaut.context.annotation.Secondary
import io.micronaut.context.annotation.Value
import io.micronaut.http.HttpStatus
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.util.UUID
import javax.ws.rs.core.Response

interface SourceService {
  fun createSource(
    sourceCreateRequest: SourceCreateRequest,
    sourceDefinitionId: UUID,
  ): SourceResponse

  fun updateSource(
    sourceId: UUID,
    sourcePutRequest: SourcePutRequest,
  ): SourceResponse

  fun partialUpdateSource(
    sourceId: UUID,
    sourcePatchRequest: SourcePatchRequest,
  ): SourceResponse

  fun deleteSource(sourceId: UUID)

  fun getSource(sourceId: UUID): SourceResponse

  fun getSourceSchema(
    sourceId: UUID,
    disableCache: Boolean,
  ): SourceDiscoverSchemaRead

  fun listSourcesForWorkspaces(
    workspaceIds: List<UUID>,
    includeDeleted: Boolean = false,
    limit: Int = 20,
    offset: Int = 0,
  ): SourcesResponse

  fun controllerInitiateOAuth(initiateOauthRequest: InitiateOauthRequest?): Response
}

@Singleton
@Secondary
open class SourceServiceImpl(
  private val userService: UserServiceImpl,
  private val sourceHandler: SourceHandler,
  private val schedulerHandler: SchedulerHandler,
  private val currentUserService: CurrentUserService,
) : SourceService {
  companion object {
    private val log = LoggerFactory.getLogger(SourceServiceImpl::class.java)
  }

  @Value("\${airbyte.api.host}")
  var publicApiHost: String? = null

  /**
   * Creates a source.
   */
  override fun createSource(
    sourceCreateRequest: SourceCreateRequest,
    sourceDefinitionId: UUID,
  ): SourceResponse {
    val sourceCreateOss = SourceCreate()
    sourceCreateOss.name = sourceCreateRequest.name
    sourceCreateOss.sourceDefinitionId = sourceDefinitionId
    sourceCreateOss.workspaceId = sourceCreateRequest.workspaceId
    sourceCreateOss.connectionConfiguration = sourceCreateRequest.configuration
    sourceCreateOss.secretId = sourceCreateRequest.secretId

    val result =
      kotlin.runCatching { sourceHandler.createSource(sourceCreateOss) }
        .onFailure {
          log.error("Error for createSource", it)
          ConfigClientErrorHandler.handleError(it, sourceDefinitionId.toString())
        }
    log.debug(HTTP_RESPONSE_BODY_DEBUG_MESSAGE + result)
    return SourceReadMapper.from(result.getOrNull()!!)
  }

  /**
   * Updates a source fully with full replacement of configuration.
   */
  override fun updateSource(
    sourceId: UUID,
    sourcePutRequest: SourcePutRequest,
  ): SourceResponse {
    val sourceUpdate =
      SourceUpdate()
        .sourceId(sourceId)
        .connectionConfiguration(sourcePutRequest.configuration)
        .name(sourcePutRequest.name)

    val result =
      kotlin.runCatching { sourceHandler.updateSource(sourceUpdate) }
        .onFailure {
          log.error("Error for updateSource", it)
          ConfigClientErrorHandler.handleError(it, sourceId.toString())
        }
    log.debug(HTTP_RESPONSE_BODY_DEBUG_MESSAGE + result)
    return SourceReadMapper.from(result.getOrNull()!!)
  }

  /**
   * Updates a source allowing patch semantics including within the configuration.
   */
  override fun partialUpdateSource(
    sourceId: UUID,
    sourcePatchRequest: SourcePatchRequest,
  ): SourceResponse {
    val sourceUpdate =
      PartialSourceUpdate()
        .sourceId(sourceId)
        .connectionConfiguration(sourcePatchRequest.configuration)
        .name(sourcePatchRequest.name)
        .secretId(sourcePatchRequest.secretId)

    val result =
      kotlin.runCatching { sourceHandler.partialUpdateSource(sourceUpdate) }
        .onFailure {
          log.error("Error for partialUpdateSource", it)
          ConfigClientErrorHandler.handleError(it, sourceId.toString())
        }

    log.debug(HTTP_RESPONSE_BODY_DEBUG_MESSAGE + result)
    return SourceReadMapper.from(result.getOrNull()!!)
  }

  /**
   * Deletes a source by ID.
   */
  override fun deleteSource(sourceId: UUID) {
    val sourceIdRequestBody = SourceIdRequestBody().sourceId(sourceId)
    val result =
      kotlin.runCatching { sourceHandler.deleteSource(sourceIdRequestBody) }
        .onFailure {
          log.error("Error for deleteSource", it)
          ConfigClientErrorHandler.handleError(it, sourceId.toString())
        }
    log.debug(HTTP_RESPONSE_BODY_DEBUG_MESSAGE + result)
  }

  /**
   * Gets a source by ID.
   */
  override fun getSource(sourceId: UUID): SourceResponse {
    val sourceIdRequestBody = SourceIdRequestBody()
    sourceIdRequestBody.sourceId = sourceId

    val result =
      kotlin.runCatching { sourceHandler.getSource(sourceIdRequestBody) }
        .onFailure {
          log.error("Error for getSource", it)
          ConfigClientErrorHandler.handleError(it, sourceId.toString())
        }
    log.debug(HTTP_RESPONSE_BODY_DEBUG_MESSAGE + result)
    return SourceReadMapper.from(result.getOrNull()!!)
  }

  /**
   * Gets a source's schema.
   */
  override fun getSourceSchema(
    sourceId: UUID,
    disableCache: Boolean,
  ): SourceDiscoverSchemaRead {
    val sourceDiscoverSchemaRequestBody = SourceDiscoverSchemaRequestBody().sourceId(sourceId).disableCache(disableCache)

    val result =
      kotlin.runCatching { schedulerHandler.discoverSchemaForSourceFromSourceId(sourceDiscoverSchemaRequestBody) }
        .onFailure {
          log.error("Error for getSourceSchema", it)
          ConfigClientErrorHandler.handleError(it, sourceId.toString())
        }

    val sourceDefinitionSpecificationRead = result.getOrNull()!!
    log.debug(HTTP_RESPONSE_BODY_DEBUG_MESSAGE + result)
    if (sourceDefinitionSpecificationRead.jobInfo?.succeeded == false) {
      var errorMessage = "Something went wrong in the connector."
      if (sourceDefinitionSpecificationRead.jobInfo?.failureReason!!.externalMessage != null) {
        errorMessage += " logs:" + sourceDefinitionSpecificationRead.jobInfo!!.failureReason!!.externalMessage
      } else if (sourceDefinitionSpecificationRead.jobInfo?.failureReason!!.internalMessage != null) {
        errorMessage += " logs:" + sourceDefinitionSpecificationRead.jobInfo!!.failureReason!!.internalMessage
      }
      throw UnexpectedProblem(HttpStatus.BAD_REQUEST, errorMessage)
    }
    return result.getOrNull()!!
  }

  /**
   * Lists sources by workspace IDs or all sources if no workspace IDs are provided.
   */
  override fun listSourcesForWorkspaces(
    workspaceIds: List<UUID>,
    includeDeleted: Boolean,
    limit: Int,
    offset: Int,
  ): SourcesResponse {
    val pagination: Pagination = Pagination().pageSize(limit).rowOffset(offset)
    val workspaceIdsToQuery = workspaceIds.ifEmpty { userService.getAllWorkspaceIdsForUser(currentUserService.currentUser.userId) }
    val listResourcesForWorkspacesRequestBody = ListResourcesForWorkspacesRequestBody()
    listResourcesForWorkspacesRequestBody.includeDeleted = includeDeleted
    listResourcesForWorkspacesRequestBody.pagination = pagination
    listResourcesForWorkspacesRequestBody.workspaceIds = workspaceIdsToQuery

    val result =
      kotlin.runCatching { sourceHandler.listSourcesForWorkspaces(listResourcesForWorkspacesRequestBody) }
        .onFailure {
          log.error("Error for listSourcesForWorkspaces", it)
          ConfigClientErrorHandler.handleError(it, workspaceIds.toString())
        }

    log.debug(HTTP_RESPONSE_BODY_DEBUG_MESSAGE + result)
    return SourcesResponseMapper.from(
      result.getOrNull()!!,
      workspaceIds,
      includeDeleted,
      limit,
      offset,
      publicApiHost!!,
    )
  }

  override fun controllerInitiateOAuth(initiateOauthRequest: InitiateOauthRequest?): Response {
    return Response.status(Response.Status.NOT_IMPLEMENTED).build()
  }
}
