package com.lyne.adas.l1.ui

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Offline debugging aid: set LYNE_MASK_DIR to a folder of 80x45 "*.mask" files (0/1 chars). */
class CorridorDumpTest {
    @Test fun dump() {
        val dir = System.getenv("LYNE_MASK_DIR")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        val top = System.getenv("LYNE_TOP")?.toFloat() ?: 0.45f
        for (f in dir!!.listFiles { x -> x.name.endsWith(".mask") }!!.sorted()) {
            val m = f.readText().trim().map { (it - '0').toByte() }.toByteArray()
            val c = Corridor.fromMask(m, 80, 45, top)
            val out = File(f.path.removeSuffix(".mask") + ".cor")
            if (c == null) { out.writeText("null"); continue }
            out.writeText((0 until Corridor.N).joinToString("\n") { "${c.rows[it]} ${c.left[it]} ${c.right[it]}" })
        }
    }
}
