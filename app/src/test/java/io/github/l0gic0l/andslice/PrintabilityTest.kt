package io.github.l0gic0l.andslice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrintabilityTest {
    private fun check(
        openEdges: Int = 0, repaired: Int = 0, overhang: Double = 0.0, unsupported: Double = overhang,
        contact: Double = 400.0, height: Double = 20.0, outside: Boolean = false, tooBig: Boolean = false,
    ) = ObjectCheck(1, "part.stl", openEdges, repaired, overhang, unsupported, contact, height, outside, tooBig)

    @Test
    fun parsesTheEngineCheck() {
        val json = """[{"contact_mm2":0.0,"id":13,"name":"sphere.stl","open_edges":42,"outside":false,"overhang_mm2":310.2,
            "repaired_errors":43,"shells":1,"size":[30.0,30.0,30.0],"too_big":false,"unsupported_mm2":310.2}]"""
        val c = ObjectCheck.parseList(json).single()
        assertEquals(13L, c.id)
        assertEquals(42, c.openEdges)
        assertEquals(43, c.repairedErrors)
        assertEquals(30.0, c.heightMm, 1e-9)
        assertEquals(310.2, c.unsupportedMm2, 1e-9)
    }

    @Test
    fun overhangsOfferSupportsUnlessTheyAreOn() {
        val off = Printability.plateIssues(listOf(check(overhang = 400.0)), supports = false, brim = false, overhangAngle = 40).single()
        assertEquals("Large overhangs", off.title)
        assertTrue(off.warning)
        assertTrue(off.detail.contains("400 mm²") && off.detail.contains("40°"))
        assertEquals(listOf(IssueFix.Supports, IssueFix.Orient, IssueFix.PaintSupports), off.fixes)

        // Blockers painted over overhangs leave them unsupported with automatic supports on.
        val on = Printability.plateIssues(listOf(check(overhang = 400.0, unsupported = 120.0)), supports = true, brim = false, overhangAngle = 40)
        assertEquals(listOf(IssueFix.Orient, IssueFix.PaintSupports), on.single().fixes)

        // Small overhangs print.
        assertTrue(Printability.plateIssues(listOf(check(overhang = 30.0)), false, false, 40).isEmpty())
    }

    @Test
    fun meshErrorsAndFit() {
        val holes = Printability.plateIssues(listOf(check(openEdges = 4)), false, false, 45).single()
        assertEquals(listOf(IssueFix.Repair), holes.fixes)
        val fixed = Printability.plateIssues(listOf(check(repaired = 3)), false, false, 45).single()
        assertFalse(fixed.warning)
        assertTrue(fixed.fixes.isEmpty())

        val big = Printability.plateIssues(listOf(check(outside = true, tooBig = true)), false, false, 45).single()
        assertEquals(listOf(IssueFix.ScaleToFit), big.fixes)
        val off = Printability.plateIssues(listOf(check(outside = true)), false, false, 45).single()
        assertEquals(listOf(IssueFix.Arrange), off.fixes)
    }

    @Test
    fun bedContact() {
        val sphere = Printability.plateIssues(listOf(check(contact = 0.0, height = 30.0)), false, false, 45).single()
        assertEquals("Barely touches the bed", sphere.title)
        assertEquals(listOf(IssueFix.Orient, IssueFix.Brim, IssueFix.Supports), sphere.fixes)
        assertEquals(listOf(IssueFix.Orient), Printability.plateIssues(listOf(check(contact = 0.0)), true, true, 45).single().fixes)

        // 10 × 10 mm and 50 mm tall: a brim helps, but it is not a warning.
        val tower = Printability.plateIssues(listOf(check(contact = 100.0, height = 50.0)), false, false, 45).single()
        assertFalse(tower.warning)
        assertEquals(listOf(IssueFix.Brim), tower.fixes)
        assertTrue(Printability.plateIssues(listOf(check(contact = 100.0, height = 50.0)), false, true, 45).isEmpty())
    }

    @Test
    fun sliceWarnings() {
        val warning = "Detected print stability issues:\n\ntee.stl\nFloating bridge anchors, Low bed adhesion, Loose extrusions\n\n" +
            "Consider enabling supports.\nAlso consider enabling brim."
        val issue = Printability.sliceIssues(listOf(warning), supports = false, brim = false).single()
        assertEquals("Print stability issues", issue.title)
        assertEquals("tee.stl\nFloating bridge anchors, Low bed adhesion, Loose extrusions", issue.detail)
        assertEquals(listOf(IssueFix.Supports, IssueFix.Brim), issue.fixes)
        assertTrue(Printability.sliceIssues(listOf(warning), supports = true, brim = true).single().fixes.isEmpty())

        val other = Printability.sliceIssues(listOf("Toolpaths of \"a\" and \"b\" collide at 2.00 mm."), false, false).single()
        assertEquals("Slicing warning", other.title)
    }

    @Test
    fun areas() {
        assertEquals("320 mm²", Printability.area(320.4))
        assertEquals("12.5 cm²", Printability.area(1250.0))
    }
}
