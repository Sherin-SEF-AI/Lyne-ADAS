package com.lyne.adas.l1.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RiderMergeTest {
    // A motorbike ~12 m ahead: wheels-to-handlebar box.
    private val bike = BBox(0.45f, 0.55f, 0.53f, 0.70f)

    @Test fun riderOnBikeIsMergedIntoIt() {
        // Rider: torso above the bike, legs ending on the pegs (~55% down the bike box).
        val rider = BBox(0.455f, 0.42f, 0.525f, 0.63f)
        assertTrue(RiderMerge.isRider(rider, bike))
        val dets = arrayListOf(
            Detection(ObjectClass.MOTORCYCLE, 0.8f, bike),
            Detection(ObjectClass.PEDESTRIAN, 0.9f, rider),
        )
        RiderMerge.merge(dets)
        assertEquals(1, dets.size)
        val m = dets[0]
        assertEquals(ObjectClass.MOTORCYCLE, m.cls)
        assertEquals(0.42f, m.box.top, 1e-6f)        // extended to the rider's head
        assertEquals(bike.bottom, m.box.bottom, 1e-6f) // ground contact unchanged
        assertEquals(bike.left, m.box.left, 1e-6f)
        assertEquals(bike.right, m.box.right, 1e-6f)
    }

    @Test fun pedestrianBesideBikeIsNotMerged() {
        // Standing next to the parked bike: centre outside it, feet on the road at the wheel line.
        val ped = BBox(0.535f, 0.43f, 0.575f, 0.70f)
        assertFalse(RiderMerge.isRider(ped, bike))
        val dets = arrayListOf(
            Detection(ObjectClass.MOTORCYCLE, 0.8f, bike),
            Detection(ObjectClass.PEDESTRIAN, 0.9f, ped),
        )
        RiderMerge.merge(dets)
        assertEquals(2, dets.size)
        assertEquals(bike, dets.first { it.cls == ObjectClass.MOTORCYCLE }.box)
    }

    @Test fun pedestrianInFrontOfBikeIsNotMerged() {
        // Crossing in front of a bike (overlapping x), but feet on the road at/below the wheels.
        val ped = BBox(0.47f, 0.45f, 0.51f, 0.71f)
        assertFalse(RiderMerge.isRider(ped, bike))
    }

    @Test fun tinyDistantPersonIsNotARider() {
        // Far-away person whose box happens to fall inside a near bike's upper half.
        val far = BBox(0.485f, 0.56f, 0.495f, 0.585f)
        assertFalse(RiderMerge.isRider(far, bike))
    }

    @Test fun pillionAndRiderBothMerge() {
        val rider = BBox(0.46f, 0.42f, 0.52f, 0.63f)
        val pillion = BBox(0.47f, 0.43f, 0.53f, 0.64f)
        val other = Detection(ObjectClass.CAR, 0.9f, BBox(0.1f, 0.5f, 0.3f, 0.7f))
        val dets = arrayListOf(
            Detection(ObjectClass.PEDESTRIAN, 0.9f, rider),
            other,
            Detection(ObjectClass.MOTORCYCLE, 0.8f, bike),
            Detection(ObjectClass.PEDESTRIAN, 0.7f, pillion),
        )
        RiderMerge.merge(dets)
        assertEquals(2, dets.size)
        assertEquals(other, dets[0])
        assertEquals(0.42f, dets[1].box.top, 1e-6f)
    }

    @Test fun noTwoWheelerLeavesListUntouched() {
        val dets = arrayListOf(
            Detection(ObjectClass.PEDESTRIAN, 0.9f, BBox(0.4f, 0.4f, 0.45f, 0.7f)),
            Detection(ObjectClass.CAR, 0.9f, BBox(0.5f, 0.5f, 0.7f, 0.7f)),
        )
        val before = ArrayList(dets)
        RiderMerge.merge(dets)
        assertEquals(before, dets)
    }
}
