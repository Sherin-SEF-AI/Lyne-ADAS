package com.lyne.adas.l1.inference

/**
 * Model label -> [ObjectClass], by EXACT (normalized) label. Substring matching mapped COCO "carrot"
 * to CAR; an exact table cannot. Normalization only lowercases, trims and folds '_' to ' ', so
 * "stop_sign" and "Stop Sign" both match. A few aliases cover likely custom/fine-tuned label sets.
 * Anything not listed is UNKNOWN.
 */
object LabelMap {
    private val table: Map<String, ObjectClass> = buildMap {
        fun put(cls: ObjectClass, vararg names: String) { for (n in names) put(n, cls) }
        // COCO-80 classes Lyne reasons about.
        put(ObjectClass.PEDESTRIAN, "person", "pedestrian")
        put(ObjectClass.BICYCLE, "bicycle", "cycle")
        put(ObjectClass.CAR, "car", "vehicle")
        put(ObjectClass.MOTORCYCLE, "motorcycle", "motorbike", "scooter", "moped", "two wheeler", "two-wheeler")
        // A custom model's "rider" box is a person ON a two-wheeler: the two-wheeler is the road user.
        put(ObjectClass.MOTORCYCLE, "rider")
        put(ObjectClass.BUS, "bus")
        put(ObjectClass.TRUCK, "truck", "lorry")
        put(ObjectClass.STOP_SIGN, "stop sign")
        put(ObjectClass.AUTORICKSHAW, "autorickshaw", "auto-rickshaw", "auto rickshaw", "rickshaw", "tuk-tuk", "tuk tuk", "tuktuk")
        // Animals that matter on Indian roads. COCO "bird" is deliberately left out (small, flies off).
        // bear/zebra/giraffe are kept: they don't occur here, but cattle seen at odd angles are sometimes
        // scored as them, and anything that size on the road deserves the same warning.
        put(
            ObjectClass.ANIMAL,
            "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe",
            "animal", "cattle", "buffalo", "goat", "bull", "ox", "donkey", "camel", "pig", "monkey",
        )
    }

    fun normalize(label: String): String = label.trim().lowercase().replace('_', ' ')

    fun classFor(label: String?): ObjectClass = if (label == null) ObjectClass.UNKNOWN else table[normalize(label)] ?: ObjectClass.UNKNOWN

    /** Precomputed per-index table for a model's ordered label list (done once at detector init). */
    fun tableFor(labels: List<String>): Array<ObjectClass> = Array(labels.size) { classFor(labels[it]) }
}
