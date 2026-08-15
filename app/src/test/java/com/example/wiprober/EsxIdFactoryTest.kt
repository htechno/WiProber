package com.example.wiprober

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class EsxIdFactoryTest {
    @Test
    fun createsStrictDistinctUuidsFromRepeatedDeterministicEntropy() {
        val floorPlanId = EsxIdFactory.create("floor-plan:floor-3", "fixed-test-id")
        val imageId = EsxIdFactory.create("floor-image:floor-3", "fixed-test-id")

        UUID.fromString(floorPlanId)
        UUID.fromString(imageId)
        assertTrue(EsxIdFactory.isUuid(floorPlanId))
        assertTrue(EsxIdFactory.isUuid(imageId))
        assertNotEquals(floorPlanId, imageId)
    }

    @Test
    fun rejectsFormerPrefixedIdentifiers() {
        assertTrue(!EsxIdFactory.isUuid("wiprober-floor-3-image-3fbd1035-2ec9-45f9-b5f5-ee99dafd4470"))
    }
}
