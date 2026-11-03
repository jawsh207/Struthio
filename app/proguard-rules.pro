# libsecp256k1 binding: loaded reflectively and called through JNI.
-keep class fr.acinq.secp256k1.** { *; }
-dontwarn fr.acinq.secp256k1.**
