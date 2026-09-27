package com.rosalina.studio
import org.junit.Assert.*
import org.junit.Test
class LimitsTest {
    @Test fun acceptedSizes() { Limits.SIZES.forEach { Limits.validate(it.first,it.second,12,.55f) } }
    @Test(expected=IllegalArgumentException::class) fun rejectLarge() { Limits.validate(1024,1024,12,.5f) }
    @Test(expected=IllegalArgumentException::class) fun rejectNaN() { Limits.validate(512,512,12,Float.NaN) }
    @Test(expected=IllegalArgumentException::class) fun rejectExcessSteps() { Limits.validate(512,512,300,.5f) }
    @Test fun routeImagesOnly() {
        assertEquals("a blue car",Limits.imageIntent("Create an image of a blue car"))
        assertEquals("a forest",Limits.imageIntent("/image a forest"))
        assertNull(Limits.imageIntent("Explain how to create an image"))
        assertNull(Limits.imageIntent("Generate code for my app"))
    }
    @Test fun errorsNeverNull() { assertEquals("Exception",Limits.error(Exception())) }
}
