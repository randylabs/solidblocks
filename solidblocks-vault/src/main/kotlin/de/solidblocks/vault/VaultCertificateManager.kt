package de.solidblocks.vault

import de.solidblocks.base.ServiceReference
import mu.KotlinLogging
import org.springframework.vault.authentication.TokenAuthentication
import org.springframework.vault.client.VaultEndpoint
import org.springframework.vault.core.VaultTemplate
import java.net.URI
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.ExperimentalTime
import kotlin.time.toDuration

val cf = CertificateFactory.getInstance("X.509")

data class Certificate(val publicRaw: String) {

    val public: X509Certificate
        get() {
            return cf.generateCertificate(publicRaw.byteInputStream()) as X509Certificate
        }

    @OptIn(ExperimentalTime::class)
    val remainingCertificateLifetime: Duration
        get() {
            val currentEpocSeconds = public.notAfter.toInstant().toEpochMilli()
            return (currentEpocSeconds - Instant.now().toEpochMilli()).toDuration(DurationUnit.MILLISECONDS)
        }
}

@OptIn(ExperimentalTime::class)
class VaultCertificateManager(
    private val address: String,
    token: String,
    val reference: ServiceReference,
    val minCertificateLifetime: Duration = Duration.hours(2)
) {

    private val logger = KotlinLogging.logger {}

    private val vaultTemplate: VaultTemplate

    var certificate: Certificate? = null

    init {
        logger.info { "initializing vault manager for address '$address'" }
        vaultTemplate = VaultTemplate(VaultEndpoint.from(URI.create(address)), TokenAuthentication(token))

        thread(start = true) {

            while (true) {
                if (certificate != null) {

                    val remainingCertificateLifetime = certificate!!.remainingCertificateLifetime
                    if (remainingCertificateLifetime > minCertificateLifetime) {
                        logger.info { "certificate still has ${remainingCertificateLifetime.inWholeHours} hours left" }
                    } else {
                        logger.info { "certificate has less than ${minCertificateLifetime.inWholeHours} hours left" }
                        certificate = issueCertificate()
                    }
                }

                if (certificate == null) {
                    logger.info { "no active certificate found" }
                    certificate = issueCertificate()
                }

                Thread.sleep(5000)
            }
        }
    }

    fun issueCertificate(): Certificate? = try {
        logger.info { "issuing certificate" }
        val response = vaultTemplate.write(
            "${
            VaultConstants.pkiMountName(
                reference.cloud,
                reference.environment
            )
            }/issue/${VaultConstants.pkiMountName(reference.cloud, reference.environment)}",
            mapOf("common_name" to reference.service)
        )

        val certificate = response.data.get("certificate").toString()
        val privateKey = response.data.get("private_key").toString()
        val issuingCa = response.data.get("issuing_ca").toString()
        val serialNumber = response.data.get("serial_number").toString()

        val result = Certificate(certificate)
        logger.info { "issued certificate '${result.public.serialNumber}' valid until ${result.public.notAfter}" }
        result
    } catch (e: Exception) {
        logger.error { "failed to issue certificate for service '${reference.service}'" }
        null
    }

    fun seal(): Boolean {
        logger.info { "sealing vault at address '$address'" }
        vaultTemplate.opsForSys().seal()
        return true
    }
}
