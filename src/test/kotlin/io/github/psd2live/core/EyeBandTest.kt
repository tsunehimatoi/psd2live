package io.github.psd2live.core

import io.github.psd2live.core.fit.*
import kotlin.math.*
import kotlin.test.*

class EyeBandTest {
	private val fit = EyeFit(Side.LEFT, 0f, 0f, 1f, 0f, 100f,
		List(17) { -.2f*sin(PI*it/16).toFloat() }, List(17) { .12f*sin(PI*it/16).toFloat() }, null, null)
	private val stroke = EyeStroke(EyeRegion.UPPER, List(65) { EyePoint(it/64f, -.2f*sin(PI*it/64).toFloat()) },
		List(65) { .02f }, List(65) { true })
	private val band = EyeBand(fit, stroke)

	@Test fun aProvidedClosedDrawingSuppliesTheBandsClosedTarget() {
		val drawing=List(17) { .1f + it*.001f }
		val drawnBand=EyeBand(fit.copy(closed=drawing),stroke)
		for(i in drawing.indices) assertEquals(drawing[i],drawnBand.closed(i/16f,false),.000001f)
	}

	@Test fun everySourceVertexIsExactlyUnchangedAtNeutral() {
		for (i in -10..110) for (j in -20..20) {
			val u = i/100f; val v = band.centre(u)+j/100f
			assertEquals(EyePoint(u,v), band.move(u,v,1f,false,true))
		}
	}
	@Test fun aToothCanCrossTheBandWhileTheBandRetainsItsThickness() {
		val u=.5f; val c=band.centre(u); val h=band.halfWidth
		for (open in listOf(0f,.1f,.3f,.5f,.7f,1f)) {
			val a=band.move(u,c-h,open,false,true); val b=band.move(u,c+h,open,false,true)
			assertEquals(2*h,b.y-a.y,0.0001f)
		}
		val ordinary=band.move(u,c-5*h,0f,false,true)
		val smiling=band.move(u,c-5*h,0f,true,true)
		assertTrue(ordinary.y>band.closed(u,false))
		assertTrue(smiling.y<band.closed(u,true))
	}
	@Test fun branchRootsAndStrokeStationsHaveContinuousFirstDerivatives() {
		val h=band.halfWidth; val e=0.00001f
		for (u in listOf(.25f,.5f,.75f)) {
			val root=band.centre(u)-h
			fun y(v: Float)=band.move(u,v,0f,false,true).y
			assertEquals((y(root)-y(root-e))/e,(y(root+e)-y(root))/e,.02f)
			assertEquals((band.centre(u)-band.centre(u-e))/e,(band.centre(u+e)-band.centre(u))/e,.02f)
		}
	}
	@Test fun theWholeClosingRangeRemainsFiniteAndContinuousIncludingExtensions() {
		for (u in listOf(-.2f,0f,.5f,1f,1.2f)) for (v in listOf(-.4f,0f,.4f)) {
			var previous=band.move(u,v,0f,false,true)
			for (i in 1..100) {
				val current=band.move(u,v,i/100f,false,true)
				assertTrue(current.x.isFinite() && current.y.isFinite())
				assertTrue(current.distance(previous)<.03f)
				previous=current
			}
		}
	}
}
