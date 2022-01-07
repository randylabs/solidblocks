package de.solidblocks.provisioner

import de.solidblocks.api.resources.ResourceDiff
import de.solidblocks.api.resources.ResourceDiffItem
import de.solidblocks.api.resources.ResourceGroup
import de.solidblocks.api.resources.allChangedOrMissingResources
import de.solidblocks.api.resources.infrastructure.IInfrastructureResourceProvisioner
import de.solidblocks.api.resources.infrastructure.InfrastructureProvisioner
import de.solidblocks.base.ProvisionerRegistry
import de.solidblocks.core.IInfrastructureResource
import de.solidblocks.core.IResource
import de.solidblocks.core.IResourceLookup
import de.solidblocks.core.Result
import de.solidblocks.core.getAllInfraParents
import de.solidblocks.core.getInfraParents
import de.solidblocks.core.logName
import de.solidblocks.provisioner.hetzner.cloud.floatingip.FloatingIp
import de.solidblocks.provisioner.hetzner.cloud.floatingip.FloatingIpAssignment
import de.solidblocks.provisioner.hetzner.cloud.network.Network
import de.solidblocks.provisioner.hetzner.cloud.server.Server
import de.solidblocks.provisioner.hetzner.cloud.ssh.SshKey
import de.solidblocks.provisioner.hetzner.cloud.volume.Volume
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import io.github.resilience4j.retry.RetryRegistry
import mu.KotlinLogging
import java.time.Duration
import java.util.function.Supplier

class Provisioner(private val provisionerRegistry: ProvisionerRegistry) : InfrastructureProvisioner {

    private val logger = KotlinLogging.logger {}

    private val appliedResources: MutableList<IResource> = mutableListOf()

    val retryConfig = RetryConfig.custom<Boolean>()
        .maxAttempts(15)
        .waitDuration(Duration.ofMillis(5000))
        .retryOnResult { !it }
        .build()

    val retryRegistry: RetryRegistry = RetryRegistry.of(retryConfig)

    private val resourceGroups = mutableListOf<ResourceGroup>()

    fun createResourceGroup(name: String, dependsOn: Set<ResourceGroup> = emptySet()): ResourceGroup {
        return ResourceGroup(name, dependsOn).apply {
            resourceGroups.add(this)
        }
    }

    override fun <LookupType : IResourceLookup<RuntimeType>, RuntimeType> lookup(resource: LookupType): Result<RuntimeType> =
        try {
            this.provisionerRegistry.datasource(resource).lookup(resource)
        } catch (e: Exception) {
            logger.error(e) { "lookup for ${resource.logName()} failed" }
            Result(failed = true, message = e.message)
        }

    fun <ResourceType : IResource, ReturnType> provisioner(resource: ResourceType): IInfrastructureResourceProvisioner<ResourceType, ReturnType> {
        return this.provisionerRegistry.provisioner(resource)
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun apply(): Boolean {
        appliedResources.clear()

        logger.info {
            "applying resource groups ${resourceGroups.joinToString(", ") { it.name }}"
        }

        val allLayerDiffs = HashMap<ResourceGroup, List<ResourceDiff>>()

        for (resourceGroup in resourceGroups) {

            val diffs = diffForResourceGroup(resourceGroup, allLayerDiffs) ?: return false
            logger.info {
                "resource group '${resourceGroup.name}', changed resources: ${
                diffs.filter { it.hasChanges() }.joinToString(", ") { it.toString() }
                }, missing resources: ${diffs.filter { it.isMissing() }.joinToString(", ") { it.resource.id() }}"
            }

            allLayerDiffs[resourceGroup] = diffs

            val diffsForAllLayersWithChanges =
                allLayerDiffs.flatMap { it.value }.filter { it.hasChangesOrMissing() }

            val resourcesThatDependOnResourcesWithChanges = resourceGroup.resources.filter {
                it.getInfraParents().any { parent -> diffsForAllLayersWithChanges.any { it.resource == parent } }
            }

            val o = resourcesThatDependOnResourcesWithChanges.map {
                ResourceDiff(
                    it,
                    changes = listOf(ResourceDiffItem("parent", changed = true))
                )
            }

            val resourcesToApply = allLayerDiffs[resourceGroup]!! + o
            val result = apply(resourcesToApply)
            if (!result) {
                return false
            }
        }

        return true
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun apply(diffs: List<ResourceDiff>): Boolean {

        for (diffToDestroy in diffs.filter { it.needsRecreate() }) {
            val resource = diffToDestroy.resource as IInfrastructureResource<IResource, Any>

            logger.info { "destroying ${resource.logName()}" }
            val result = this.provisionerRegistry.provisioner<IResource, Any>(resource).destroy(resource)
            if (!result) {
                logger.error { "destroying ${resource.logName()} failed" }
                return false
            }
        }

        for (diffWithChange in diffs.filter { it.hasChangesOrMissing() }) {
            val resource = diffWithChange.resource as IInfrastructureResource<Any, Any>

            if (appliedResources.contains(resource)) {
                logger.info { "${resource.logName()} already applied" }
                continue
            }

            logger.info { "applying ${resource.logName()}" }

            try {
                val result = this.provisionerRegistry.provisioner<IResource, Any>(resource).apply(resource)

                if (result.failed) {
                    logger.error { "applying ${diffWithChange.resource.logName()} failed, result was: '${result.errorMessage()}'" }
                    return false
                }

                appliedResources.add(resource)
            } catch (e: Exception) {
                logger.error(e) { "apply failed for resource ${diffWithChange.resource.logName()}" }
                return false
            }
        }

        return true
    }

    private fun diffForResourceGroup(
        resourceGroup: ResourceGroup,
        allLayerDiffs: Map<ResourceGroup, List<ResourceDiff>>
    ): List<ResourceDiff>? {
        logger.info { "creating diff for resource group '${resourceGroup.name}'" }

        val changedOrMissingResources = allLayerDiffs.allChangedOrMissingResources()

        for (parentResourceGroup in resourceGroup.dependsOn) {

            val resourceGroupAllInfraParents = resourceGroup.resources.getAllInfraParents()
            val resourceGroupMissingOrChangedParents = resourceGroupAllInfraParents.filter { changedOrMissingResources.contains(it) }

            if (resourceGroupMissingOrChangedParents.isNotEmpty()) {
                logger.info {
                    "skipping healthcheck for resource group ${resourceGroup.name} the following dependencies were missing or changed: ${
                    resourceGroupMissingOrChangedParents.joinToString(
                        ", "
                    ) { it.id() }
                    } "
                }

                continue
            }

            val resourcesWithHealthChecks = parentResourceGroup.resources.filter { it.getHealthCheck() != null }
            for (resourcesWithHealthCheck in resourcesWithHealthChecks) {
                val allInfraParents = resourcesWithHealthCheck.getAllInfraParents()
                val missingOrChangedParents = allInfraParents.filter { changedOrMissingResources.contains(it) }

                if (missingOrChangedParents.isNotEmpty()) {
                    logger.info {
                        "skipping healthcheck for ${resourcesWithHealthCheck.id()} the following dependencies were missing or changed: ${
                        missingOrChangedParents.joinToString(
                            ", "
                        ) { it.id() }
                        } "
                    }

                    continue
                }

                logger.info { "running healthcheck for '${resourcesWithHealthCheck.id()}" }

                val decorateFunction: Supplier<Boolean> =
                    Retry.decorateSupplier(retryRegistry.retry("healthcheck")) {
                        resourcesWithHealthCheck.getHealthCheck()!!.invoke()
                    }

                val result = decorateFunction.get()
                if (!result) {
                    logger.error { "healthcheck for ${resourcesWithHealthCheck.id()} failed" }
                    return null
                }
            }
        }

        val resources = resourceGroup.hierarchicalResourceList().toSet()
        val result = mutableListOf<ResourceDiff>()

        for (resource in resources) {
            val allInfraParents = resource.getAllInfraParents()
            val missingOrChangedParents = allInfraParents.filter { changedOrMissingResources.contains(it) } + result.filter { it.hasChangesOrMissing() }.map { it.resource }

            if (missingOrChangedParents.isNotEmpty()) {
                logger.info {
                    "skipping diff for ${resource.id()} the following dependencies were missing or changed: ${
                    missingOrChangedParents.joinToString(
                        ", "
                    ) { it.id() }
                    } "
                }

                result.add(ResourceDiff(resource, missing = true))

                continue
            }

            try {
                logger.info { "creating diff for ${resource.logName()}" }
                val diff = this.provisionerRegistry.provisioner<IResource, Any>(resource).diff(resource)

                if (diff.failed) {
                    logger.info { "diff failed for ${resource.logName()}" }
                    return null
                }

                result.add(diff.result!!)
            } catch (e: Exception) {
                logger.error(e) { "diff failed for resource ${resource.logName()}" }
                return null
            }
        }

        return result
    }

    fun destroyAll(destroyVolumes: Boolean): Boolean {
        if (!this.provisionerRegistry.provisioner(FloatingIpAssignment::class).destroyAll()) {
            return false
        }

        if (!this.provisionerRegistry.provisioner(Server::class).destroyAll()) {
            return false
        }

        if (destroyVolumes) {
            if (!this.provisionerRegistry.provisioner(Volume::class).destroyAll()) {
                return false
            }
        }

        if (!this.provisionerRegistry.provisioner(Network::class).destroyAll()) {
            return false
        }
        if (!this.provisionerRegistry.provisioner(SshKey::class).destroyAll()) {
            return false
        }
        if (!this.provisionerRegistry.provisioner(FloatingIp::class).destroyAll()) {
            return false
        }

        return true
    }

    fun clear() {
        resourceGroups.clear()
    }

    fun addResourceGroup(resourceGroup: ResourceGroup) {
        resourceGroups.add(resourceGroup)
    }
}
