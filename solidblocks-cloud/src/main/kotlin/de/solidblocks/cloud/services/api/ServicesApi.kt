package de.solidblocks.cloud.services.api

import de.solidblocks.cloud.api.CloudApiHttpServer
import de.solidblocks.cloud.api.jsonRequest
import de.solidblocks.cloud.api.jsonResponse
import de.solidblocks.cloud.services.ServicesManager
import io.vertx.ext.web.RoutingContext

class ServicesApi(val cloudApiHttpServer: CloudApiHttpServer, val servicesManager: ServicesManager) {

    init {
        cloudApiHttpServer.configureSubRouter("/api/v1/services", configure = { router ->
            router.get("/catalog").handler(this::catalog)
            router.get().handler(this::list)
            router.post().handler(this::create)
        })
    }

    fun list(rc: RoutingContext) {
        val email = rc.user().principal().getString("email")

        rc.jsonResponse(
            ServicesResponse(
                servicesManager.services(email).map {
                    it.toResponse()
                }
            )
        )
    }

    fun create(rc: RoutingContext) {
        val email = rc.user().principal().getString("email")
        val request = rc.jsonRequest(ServiceCreateRequest::class.java)

        val service = servicesManager.create(email, request.name, request.type)

        if (service == null) {
            rc.jsonResponse(500)
        } else {
            rc.jsonResponse(ServiceResponseWrapper(service.toResponse()), 201)
        }
    }

    fun catalog(rc: RoutingContext) {
        val email = rc.user().principal().getString("email")
        rc.jsonResponse(servicesManager.serviceCatalog())
    }
}
