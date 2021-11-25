package de.solidblocks.provisioner

import de.solidblocks.provisioner.fixtures.TestConfiguration
import de.solidblocks.provisioner.fixtures.TestResource
import de.solidblocks.provisioner.fixtures.TestResourceProvisioner
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.util.*

@SpringBootTest(classes = [TestConfiguration::class])
class ProvisionerTest(
    @Autowired
    val provisioner: Provisioner,

    @Autowired
    val testResourceProvisioner: TestResourceProvisioner
) {

    @Test
    fun handlesFailingResourceLookups() {

        val resource = TestResource(UUID.randomUUID())

        testResourceProvisioner.reset()
        provisioner.clear()
        testResourceProvisioner.failOnLookup(resource)

        val result = provisioner.lookup<TestResource, String>(resource)

        assertTrue(result.isEmptyOrFailed())
        assertTrue(testResourceProvisioner.lookupCount(resource) == 1)
    }

    @Test
    fun resourcesAreAppliedWhenParentChanges() {

        val resource1 = TestResource(UUID.randomUUID())
        val resource2 = TestResource(UUID.randomUUID(), listOf(resource1))

        testResourceProvisioner.reset()
        provisioner.clear()
        testResourceProvisioner.diffIsMissing(resource1)

        val layer = provisioner.createResourceGroup("layer1")
        layer.addResource(resource1)
        layer.addResource(resource2)

        assertTrue(provisioner.apply())

        assertTrue(testResourceProvisioner.applyCount(resource2) == 1)
    }

    @Test
    fun diffIsOmittedWhenParentsAreMissing() {

        val resource1 = TestResource(UUID.randomUUID())
        val resource2 = TestResource(UUID.randomUUID(), listOf(resource1))

        testResourceProvisioner.reset()
        provisioner.clear()
        testResourceProvisioner.diffIsMissing(resource1)

        val layer = provisioner.createResourceGroup("layer1")
        layer.addResource(resource1)
        layer.addResource(resource2)

        assertTrue(provisioner.apply())

        assertTrue(testResourceProvisioner.diffCount(resource2) == 0)
    }

    @Test
    fun handlesFailingResourceDiffs() {

        val resource = TestResource(UUID.randomUUID())

        testResourceProvisioner.reset()
        provisioner.clear()
        testResourceProvisioner.failOnDiff(resource)

        provisioner.createResourceGroup("layer1").addResource(resource)

        val result = provisioner.apply()

        assertFalse(result)
        assertTrue(testResourceProvisioner.diffCount(resource) == 1)
    }

    @Test
    fun handlesFailingResourceApply() {

        val resource = TestResource(UUID.randomUUID())

        testResourceProvisioner.reset()
        provisioner.clear()
        testResourceProvisioner.failOnApply(resource)
        testResourceProvisioner.diffIsMissing(resource)

        provisioner.createResourceGroup("layer1").addResource(resource)

        val result = provisioner.apply()

        assertFalse(result)
        assertTrue(testResourceProvisioner.applyCount(resource) == 1)
    }
}
