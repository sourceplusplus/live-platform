package spp.platform.core.service

import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import io.vertx.servicediscovery.Record
import io.vertx.servicediscovery.ServiceDiscovery
import io.vertx.servicediscovery.ServiceDiscoveryOptions
import io.vertx.servicediscovery.types.EventBusService
import io.vertx.serviceproxy.ServiceBinder
import org.slf4j.LoggerFactory
import spp.platform.core.service.live.LiveProviders
import spp.protocol.SourceMarkerServices.Utilize
import spp.protocol.service.LiveService
import kotlin.system.exitProcess

class ServiceProvider : CoroutineVerticle() {

    companion object {
        private val log = LoggerFactory.getLogger(ServiceProvider::class.java)
        lateinit var liveProviders: LiveProviders
    }

    private var discovery: ServiceDiscovery? = null
    private var liveService: Record? = null

    override suspend fun start() {
        try {
            discovery = if (config.getJsonObject("storage").getString("selector") == "redis") {
                val sdHost = config.getJsonObject("storage").getJsonObject("redis").getString("host")
                val sdPort = config.getJsonObject("storage").getJsonObject("redis").getString("port")
                ServiceDiscovery.create(
                    vertx, ServiceDiscoveryOptions().setBackendConfiguration(
                        JsonObject()
                            .put("connectionString", "redis://$sdHost:$sdPort")
                            .put("key", "records")
                    )
                )
            } else {
                ServiceDiscovery.create(vertx, ServiceDiscoveryOptions())
            }

            liveProviders = LiveProviders(vertx, discovery!!)

            liveService = publishService(
                Utilize.LIVE_SERVICE,
                LiveService::class.java,
                liveProviders.liveService
            )
        } catch (throwable: Throwable) {
            throwable.printStackTrace()
            log.error("Failed to start SkyWalking provider", throwable)
            exitProcess(-1)
        }
    }

    private suspend fun <T> publishService(address: String, clazz: Class<T>, service: T): Record {
        ServiceBinder(vertx).setIncludeDebugInfo(true).setAddress(address).register(clazz, service)
        val record = EventBusService.createRecord(
            address, address, clazz,
            JsonObject().put("INSTANCE_ID", config.getString("SPP_INSTANCE_ID"))
        )
        discovery!!.publish(record).await()
        return record
    }

    override suspend fun stop() {
        discovery!!.unpublish(liveService!!.registration).onComplete {
            if (it.succeeded()) {
                log.info("Live service unpublished")
            } else {
                log.error("Failed to unpublish live service", it.cause())
            }
        }.await()
        discovery!!.close()
    }
}
