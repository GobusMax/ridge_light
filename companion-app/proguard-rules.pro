# OsmAnd's AIDL parcelables travel inside Bundles, which carry class names: OsmAnd must find
# them under their original names, so they are kept unobfuscated.
-keep class net.osmand.aidlapi.** { *; }
