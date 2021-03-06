package no.nav.syfo

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.ktor.application.Application
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.ContentNegotiation
import io.ktor.features.StatusPages
import io.ktor.http.HttpStatusCode
import io.ktor.jackson.jackson
import io.ktor.response.respond
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.util.KtorExperimentalAPI
import io.prometheus.client.hotspot.DefaultExports
import no.nav.syfo.api.LegeSuspensjonClient
import no.nav.syfo.api.StsOidcClient
import no.nav.syfo.api.SyketilfelleClient
import no.nav.syfo.api.registerNaisApi
import no.nav.syfo.api.registerRuleApi
import no.nav.syfo.ws.configureSTSFor
import no.nav.tjeneste.virksomhet.person.v3.binding.PersonV3
import no.nhn.schemas.reg.hprv2.IHPR2Service
import org.apache.cxf.binding.soap.SoapMessage
import org.apache.cxf.binding.soap.interceptor.AbstractSoapInterceptor
import org.apache.cxf.jaxws.JaxWsProxyFactoryBean
import org.apache.cxf.message.Message
import org.apache.cxf.phase.Phase
import org.apache.cxf.ws.addressing.WSAddressingFeature
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.TimeUnit

val log: Logger = LoggerFactory.getLogger("sm-rules")

data class ApplicationState(var ready: Boolean = false, var running: Boolean = true)

val objectMapper: ObjectMapper = ObjectMapper().apply {
    registerKotlinModule()
    registerModule(JavaTimeModule())
    configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
}

@KtorExperimentalAPI
fun main(args: Array<String>) {
    val config: ApplicationConfig = objectMapper.readValue(File(System.getenv("CONFIG_FILE")))
    val credentials: VaultCredentials = objectMapper.readValue(vaultApplicationPropertiesPath.toFile())
    val applicationState = ApplicationState()

    DefaultExports.initialize()
    val personV3 = JaxWsProxyFactoryBean().apply {
        address = config.personV3EndpointURL
        serviceClass = PersonV3::class.java
    }.create() as PersonV3
    configureSTSFor(personV3, credentials.serviceuserUsername,
            credentials.serviceuserPassword, config.securityTokenServiceUrl)

    val helsepersonellv1 = JaxWsProxyFactoryBean().apply {
        address = config.helsepersonellv1EndpointUrl
        // TODO: Contact someone about this hacky workaround
        // talk to HDIR about HPR about they claim to send a ISO-8859-1 but its really UTF-8 payload
        val interceptor = object : AbstractSoapInterceptor(Phase.RECEIVE) {
            override fun handleMessage(message: SoapMessage?) {
                if (message != null)
                    message[Message.ENCODING] = "utf-8"
            }
        }
        inInterceptors.add(interceptor)
        inFaultInterceptors.add(interceptor)
        features.add(WSAddressingFeature())
        serviceClass = IHPR2Service::class.java
    }.create() as IHPR2Service
    configureSTSFor(helsepersonellv1, credentials.serviceuserUsername,
            credentials.serviceuserPassword, config.securityTokenServiceUrl)

    val oidcClient = StsOidcClient(config.stsRestEndpointUrl, credentials.serviceuserUsername, credentials.serviceuserPassword)
    val legeSuspensjonClient = LegeSuspensjonClient(config.legeSuspensjonEndpointUrl, credentials, oidcClient)
    val syketilfelleClient = SyketilfelleClient(config.syketilfelleEndpointUrl, oidcClient)

    val applicationServer = embeddedServer(Netty, config.applicationPort) {
        initRouting(applicationState, personV3, helsepersonellv1, legeSuspensjonClient, syketilfelleClient)
        applicationState.ready = true
    }.start(wait = true)

    Runtime.getRuntime().addShutdownHook(Thread {
        // Kubernetes polls every 5 seconds for liveness, mark as not ready and wait 5 seconds for a new readyness check
        applicationState.ready = false
        Thread.sleep(10000)
        applicationServer.stop(10, 10, TimeUnit.SECONDS)
    })
}

@KtorExperimentalAPI
fun Application.initRouting(applicationState: ApplicationState, personV3: PersonV3, helsepersonellv1: IHPR2Service, legeSuspensjonClient: LegeSuspensjonClient, syketilfelleClient: SyketilfelleClient) {
    routing {
        registerNaisApi(readynessCheck = { applicationState.ready }, livenessCheck = { applicationState.running })
        registerRuleApi(personV3, helsepersonellv1, legeSuspensjonClient, syketilfelleClient)
    }
    install(ContentNegotiation) {
        jackson {
            registerKotlinModule()
            registerModule(JavaTimeModule())
        }
    }
    install(StatusPages) {
        exception<Throwable> { cause ->
            call.respond(HttpStatusCode.InternalServerError, cause.message ?: "Unknown error")

            log.error("Caught exception while trying to validate against rules", cause)
            throw cause
        }
    }
}
