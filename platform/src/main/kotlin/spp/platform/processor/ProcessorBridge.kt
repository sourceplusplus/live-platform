package spp.platform.processor

import io.vertx.core.json.Json
import io.vertx.core.net.NetServerOptions
import io.vertx.ext.bridge.BridgeEventType
import io.vertx.ext.bridge.BridgeOptions
import io.vertx.ext.bridge.PermittedOptions
import io.vertx.ext.eventbus.bridge.tcp.TcpEventBusBridge
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import io.vertx.servicediscovery.ServiceDiscoveryOptions
import org.slf4j.LoggerFactory
import spp.platform.core.SourceSubscriber
import spp.protocol.SourceMarkerServices
import spp.protocol.platform.PlatformAddress
import spp.protocol.platform.PlatformAddress.MARKER_DISCONNECTED
import spp.protocol.probe.ProbeAddress
import spp.protocol.processor.ProcessorAddress.*
import spp.protocol.processor.status.ProcessorConnection

class ProcessorBridge(private val netServerOptions: NetServerOptions) : CoroutineVerticle() {

    private val log = LoggerFactory.getLogger(ProcessorBridge::class.java)

    override suspend fun start() {
        log.debug("ProcessorBridge started")
        TcpEventBusBridge.create(
            vertx,
            BridgeOptions()
                //from processor
                .addInboundPermitted(PermittedOptions().setAddress(ServiceDiscoveryOptions.DEFAULT_ANNOUNCE_ADDRESS))
                .addInboundPermitted(PermittedOptions().setAddress(ServiceDiscoveryOptions.DEFAULT_USAGE_ADDRESS))
                .addInboundPermitted(PermittedOptions().setAddress(PlatformAddress.PROCESSOR_CONNECTED.address))
                .addInboundPermitted(PermittedOptions().setAddress(BREAKPOINT_HIT.address))
                .addInboundPermitted(PermittedOptions().setAddress(LOG_HIT.address))
                .addInboundPermitted(
                    PermittedOptions().setAddressRegex(SourceMarkerServices.Provide.LIVE_VIEW_SUBSCRIBER + "\\..+")
                )
                .addInboundPermitted(PermittedOptions().setAddress(SourceMarkerServices.Provide.LIVE_INSTRUMENT_SUBSCRIBER))
                .addInboundPermitted(
                    PermittedOptions().setAddressRegex(SourceMarkerServices.Provide.LIVE_INSTRUMENT_SUBSCRIBER + "\\..+")
                )
                .addInboundPermitted(PermittedOptions().setAddress(SourceMarkerServices.Utilize.LIVE_SERVICE))
                .addInboundPermitted(
                    PermittedOptions().setAddressRegex(ProbeAddress.LIVE_BREAKPOINT_REMOTE.address + "\\:.+")
                )
                .addInboundPermitted(
                    PermittedOptions().setAddressRegex(ProbeAddress.LIVE_LOG_REMOTE.address + "\\:.+")
                )
                .addInboundPermitted(
                    PermittedOptions().setAddressRegex(ProbeAddress.LIVE_METER_REMOTE.address + "\\:.+")
                )
                .addInboundPermitted(
                    PermittedOptions().setAddressRegex(ProbeAddress.LIVE_SPAN_REMOTE.address + "\\:.+")
                )
                //to processor
                .addOutboundPermitted(PermittedOptions().setAddress(ProbeAddress.REMOTE_REGISTERED.address))
                .addOutboundPermitted(PermittedOptions().setAddress(PlatformAddress.LIVE_BREAKPOINT_APPLIED.address))
                .addOutboundPermitted(PermittedOptions().setAddress(PlatformAddress.LIVE_BREAKPOINT_REMOVED.address))
                .addOutboundPermitted(PermittedOptions().setAddress(PlatformAddress.LIVE_LOG_APPLIED.address))
                .addOutboundPermitted(PermittedOptions().setAddress(PlatformAddress.LIVE_LOG_REMOVED.address))
                .addOutboundPermitted(PermittedOptions().setAddress(PlatformAddress.LIVE_METER_APPLIED.address))
                .addOutboundPermitted(PermittedOptions().setAddress(PlatformAddress.LIVE_METER_REMOVED.address))
                .addOutboundPermitted(PermittedOptions().setAddress(PlatformAddress.LIVE_SPAN_APPLIED.address))
                .addOutboundPermitted(PermittedOptions().setAddress(PlatformAddress.LIVE_SPAN_REMOVED.address))
                .addOutboundPermitted(PermittedOptions().setAddress(MARKER_DISCONNECTED.address))
                .addOutboundPermitted(PermittedOptions().setAddress(SourceMarkerServices.Utilize.LOG_COUNT_INDICATOR))
                .addOutboundPermitted(PermittedOptions().setAddress(SourceMarkerServices.Utilize.LIVE_VIEW))
                .addOutboundPermitted(PermittedOptions().setAddress(SourceMarkerServices.Utilize.LIVE_INSTRUMENT))
                .addOutboundPermitted(PermittedOptions().setAddress(SET_LOG_PUBLISH_RATE_LIMIT.address)),
            netServerOptions
        ) {
            if (it.type() == BridgeEventType.SEND) {
                val address = it.rawMessage.getString("address")
                if (address == PlatformAddress.PROCESSOR_CONNECTED.address) {
                    val conn = Json.decodeValue(
                        it.rawMessage.getJsonObject("body").toString(), ProcessorConnection::class.java
                    )
                    SourceSubscriber.addSubscriber(it.socket().writeHandlerID(), conn.processorId)

                    it.socket().closeHandler { _ ->
                        vertx.eventBus().publish(
                            PlatformAddress.PROCESSOR_DISCONNECTED.address,
                            it.rawMessage.getJsonObject("body")
                        )
                    }
                }

                //auto-add processor id to headers
                val processorId = SourceSubscriber.getSubscriber(it.socket().writeHandlerID())
                if (processorId != null && it.rawMessage.containsKey("headers")) {
                    it.rawMessage.getJsonObject("headers").put("processor_id", processorId)
                }
            }
            it.complete(true)
        }.listen(config.getString("bridge_port").toInt()).await()
    }
}
