package de.solidblocks.provisioner.vault.policy

import de.solidblocks.api.resources.ResourceDiff
import de.solidblocks.api.resources.ResourceDiffItem
import de.solidblocks.api.resources.infrastructure.IInfrastructureResourceProvisioner
import de.solidblocks.core.Result
import de.solidblocks.provisioner.Provisioner
import mu.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.vault.core.VaultTemplate
import org.springframework.vault.support.Policy

@Component
class VaultPolicyProvisioner(val provisioner: Provisioner) :
    IInfrastructureResourceProvisioner<VaultPolicy, VaultPolicyRuntime> {

    private val logger = KotlinLogging.logger {}

    override fun getResourceType(): Class<VaultPolicy> {
        return VaultPolicy::class.java
    }

    override fun lookup(resource: VaultPolicy): Result<VaultPolicyRuntime> {
        val vaultClient = provisioner.provider(VaultTemplate::class.java).createClient()

        return try {
            val policy = vaultClient.opsForSys().getPolicy(resource.name)

            if (null == policy) {
                Result(resource)
            } else {
                Result(resource, VaultPolicyRuntime(policy.rules))
            }
        } catch (e: Exception) {
            Result(resource, failed = true, message = e.message)
        }
    }

    override fun diff(resource: VaultPolicy): Result<ResourceDiff> {
        return lookup(resource).mapResourceResultOrElse(
            {

                val changes = mutableListOf<ResourceDiffItem>()

                if (!it.rules.containsAll(resource.rules)) {
                    changes.add(ResourceDiffItem("rules", changed = true))
                }

                ResourceDiff(resource, changes = changes)
            },
            {
                ResourceDiff(resource, missing = true)
            }
        )
    }

    override fun apply(resource: VaultPolicy): Result<*> {
        val vaultClient = provisioner.provider(VaultTemplate::class.java).createClient()
        vaultClient.opsForSys().createOrUpdatePolicy(resource.name, Policy.of(resource.rules))
        return Result<Any>(resource)
    }
}
