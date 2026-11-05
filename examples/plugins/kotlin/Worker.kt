package examples.plugins

import kotlinx.serialization.json.*

/** The worker is a standalone JVM program; it has no dependency on the editor. */
object Worker {
    fun outputId(instance: String, localName: String) = "modifier:$instance:" +
        localName.encodeToByteArray().joinToString("") { "%02x".format(it.toInt() and 255) }

    @JvmStatic fun main(args: Array<String>) {
        val objects = linkedMapOf<String, JsonElement>()
        System.`in`.bufferedReader(Charsets.UTF_8).forEachLine { line ->
            val request = Json.parseToJsonElement(line).jsonObject
            val response = buildJsonObject {
                put("protocol", 1); put("id", request.getValue("id"))
                try {
                    require(request.getValue("protocol").jsonPrimitive.int == 1)
                    if (request.getValue("reset").jsonPrimitive.boolean) objects.clear()
                    request.getValue("removed").jsonArray.forEach { objects.remove(it.jsonPrimitive.content) }
                    objects.putAll(request.getValue("changed").jsonObject)
                    val factor = request.getValue("config").jsonObject.getValue("factor").jsonPrimitive.float
                    putJsonArray("commands") {
                        request.getValue("meshes").jsonArray.forEach { raw -> addJsonObject {
                            val mesh = raw.jsonObject
                            put("op", "mesh_opacity"); put("id", mesh.getValue("id"))
                            put("value", mesh.getValue("opacity").jsonPrimitive.float * factor)
                        } }
                    }
                } catch (error: Exception) { put("error", error.message ?: "Algorithm failed") }
            }
            println(response); System.out.flush()
        }
    }
}
