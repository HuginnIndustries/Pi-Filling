# Pi-Filling app ProGuard/R8 rules.
#
# The wrapper protocol uses kotlinx-serialization's runtime tree API
# (Json/JsonObject/buildJsonObject), not @Serializable codegen, so no generated
# $$serializer classes need keeping. Keep the serialization runtime itself.
-keepclassmembers class kotlinx.serialization.json.** { *; }
-dontnote kotlinx.serialization.**
