package com.lyne.adas.l1.inference

import org.junit.Assert.assertEquals
import org.junit.Test

class LabelMapTest {

    @Test fun substringLookalikesAreNotRoadUsers() {
        // Substring matching used to map all of these to a road-user class.
        for (l in listOf("carrot", "motorcyclist helmet", "bus stop", "truck bed", "scissors", "bird", "potted plant")) {
            assertEquals(l, ObjectClass.UNKNOWN, LabelMap.classFor(l))
        }
    }

    @Test fun cocoClassesMapExactly() {
        assertEquals(ObjectClass.PEDESTRIAN, LabelMap.classFor("person"))
        assertEquals(ObjectClass.BICYCLE, LabelMap.classFor("bicycle"))
        assertEquals(ObjectClass.CAR, LabelMap.classFor("car"))
        assertEquals(ObjectClass.MOTORCYCLE, LabelMap.classFor("motorcycle"))
        assertEquals(ObjectClass.BUS, LabelMap.classFor("bus"))
        assertEquals(ObjectClass.TRUCK, LabelMap.classFor("truck"))
        assertEquals(ObjectClass.STOP_SIGN, LabelMap.classFor("stop sign"))
        assertEquals(ObjectClass.STOP_SIGN, LabelMap.classFor("Stop_Sign"))
        assertEquals(ObjectClass.UNKNOWN, LabelMap.classFor("train"))
        assertEquals(ObjectClass.UNKNOWN, LabelMap.classFor(null))
    }

    @Test fun animalsAndAliases() {
        for (l in listOf("cow", "dog", "horse", "sheep", "elephant", "cat", "bear", "zebra", "giraffe", "cattle", "buffalo", "goat", "Animal")) {
            assertEquals(l, ObjectClass.ANIMAL, LabelMap.classFor(l))
        }
        for (l in listOf("autorickshaw", "auto-rickshaw", "rickshaw", "tuk-tuk")) assertEquals(l, ObjectClass.AUTORICKSHAW, LabelMap.classFor(l))
        assertEquals(ObjectClass.MOTORCYCLE, LabelMap.classFor("scooter"))
        assertEquals(ObjectClass.MOTORCYCLE, LabelMap.classFor("rider"))
        assertEquals(ObjectClass.CAR, LabelMap.classFor("vehicle"))
    }

    @Test fun cocoTableIsPrecomputedPerIndex() {
        val coco = listOf("person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
            "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat", "dog", "horse",
            "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack", "umbrella", "handbag", "tie",
            "suitcase", "frisbee", "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove",
            "skateboard", "surfboard", "tennis racket", "bottle", "wine glass", "cup", "fork", "knife", "spoon",
            "bowl", "banana", "apple", "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut",
            "cake", "chair", "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
            "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator", "book",
            "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush")
        val t = LabelMap.tableFor(coco)
        assertEquals(80, t.size)
        assertEquals(ObjectClass.UNKNOWN, t[coco.indexOf("carrot")])
        assertEquals(ObjectClass.UNKNOWN, t[coco.indexOf("hot dog")])
        assertEquals(ObjectClass.UNKNOWN, t[coco.indexOf("teddy bear")])
        assertEquals(ObjectClass.UNKNOWN, t[coco.indexOf("bird")])
        assertEquals(ObjectClass.ANIMAL, t[coco.indexOf("cow")])
        val mapped = t.count { it != ObjectClass.UNKNOWN }
        assertEquals(7 + 9, mapped) // 7 road classes + 9 animals
    }
}
