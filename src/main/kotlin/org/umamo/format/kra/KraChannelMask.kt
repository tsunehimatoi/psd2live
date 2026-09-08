package org.umamo.format.kra

import org.umamo.format.art.ChannelMask

/*
 * The channelflags -> ChannelMask mapping, split out of KraReader so it can live in commonMain.
 *
 * KraReader itself cannot: it is a zip container plus a JDOM/SAX XML parse, both JVM-only, so it
 * stays in jvmAndroidMain alongside the FormatRegistry that is its only non-test caller.  This
 * mapping is pure arithmetic over a string and belongs with the rest of KRA's platform-free logic
 * (KraColorModel, KraLzf, KraTileData), where KraChannelMaskTest can reach it from commonTest and
 * exercise it on every target.
 */

/**
 * Builds the neutral per-channel mask from Krita's channelflags attribute.
 *
 * channelflags is a per-channel enable mask in channel-index order ('0' disables a channel,
 * anything else enables it; indices beyond the string default to enabled), or empty meaning all
 * channels enabled. The channel-index order is the color space's own: for RGB it is Blue, Green,
 * Red, Alpha (the addChannel sequence in RgbU8ColorSpace.cpp), and for GRAYA it is Gray, Alpha.
 * Grayscale's single gray channel maps onto R, G, and B together, because the raster expands gray
 * to RGB.
 *
 * A disabled alpha is Krita's "Inherit Alpha", which the caller maps onto the neutral clipped flag.
 * That clipping is an approximation: PSD clips to the single base layer directly below, whereas
 * Krita clips to the composite of everything below in the group.
 *
 * @param String? channelFlags  The layer's channelflags attribute (null/empty means all enabled).
 * @param KraPixelFormat format  The resolved pixel format (gives channel family and order).
 * @return ChannelMask the enabled state of the output red/green/blue/alpha channels.
 */
internal fun channelMaskFrom(channelFlags: String?, format: KraPixelFormat): ChannelMask {
	// KRA: kis_kra_utils.cpp stringToFlags ('0' disables, default enabled); channel order from the
	// color space addChannel sequence (RgbU8ColorSpace.cpp / GrayU8ColorSpace.cpp).
	if (channelFlags.isNullOrEmpty()) {
		return ChannelMask.ALL
	}

	fun channelEnabled(channelIndex: Int): Boolean =
		channelIndex !in channelFlags.indices || channelFlags[channelIndex] != '0'
	return if (format.isRgb) {
		ChannelMask(red = channelEnabled(2), green = channelEnabled(1), blue = channelEnabled(0), alpha = channelEnabled(3))
	} else {
		val grayEnabled = channelEnabled(0)
		ChannelMask(red = grayEnabled, green = grayEnabled, blue = grayEnabled, alpha = channelEnabled(1))
	}
}