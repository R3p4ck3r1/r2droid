package top.wsdx233.r2droid.feature.r2flutter.data

import org.json.JSONArray
import org.json.JSONObject

sealed interface FlutterDataState<out T> {
    data object Idle : FlutterDataState<Nothing>
    data object Loading : FlutterDataState<Nothing>
    data class Ready<T>(val value: T) : FlutterDataState<T>
    data class Error(val message: String) : FlutterDataState<Nothing>
}

enum class FlutterTargetStatus {
    CHECKING,
    AVAILABLE,
    UNAVAILABLE
}

data class FlutterOverview(val values: List<Pair<String, String>>)

data class FlutterFunction(
    val address: Long,
    val name: String,
    val size: Long?
) {
    companion object {
        fun fromJson(json: JSONObject) = FlutterFunction(
            address = json.optLong("addr"),
            name = json.optString("name", "method.unknown"),
            size = json.optionalLong("size")
        )
    }
}

data class FlutterField(
    val name: String,
    val type: String,
    val offset: Long,
    val flags: String
)

data class FlutterMethod(
    val name: String,
    val address: Long,
    val signature: String,
    val kind: String
)

data class FlutterClass(
    val name: String,
    val library: String,
    val superClass: String,
    val instanceSize: Long,
    val flags: String,
    val fields: List<FlutterField>,
    val methods: List<FlutterMethod>
) {
    companion object {
        fun fromJson(json: JSONObject): FlutterClass {
            val classFlags = json.optJSONObject("flags")
            return FlutterClass(
                name = json.optString("name", "Class"),
                library = json.optJSONObject("library")?.optString("name").orEmpty(),
                superClass = json.optJSONObject("super")?.optString("name").orEmpty(),
                instanceSize = json.optJSONObject("layout")?.optLong("instance_size") ?: 0L,
                flags = buildList {
                    if (classFlags?.optBoolean("abstract") == true) add("abstract")
                    if (classFlags?.optBoolean("enum") == true) add("enum")
                    if (classFlags?.optBoolean("mixin") == true) add("mixin")
                    if (classFlags?.optBoolean("toplevel") == true) add("toplevel")
                }.joinToString(", "),
                fields = json.optJSONArray("fields").mapObjects { field ->
                    val fieldFlags = field.optJSONObject("flags")
                    FlutterField(
                        name = field.optString("name", "field"),
                        type = field.optString("type", "dynamic"),
                        offset = field.optLong("offset"),
                        flags = buildList {
                            if (fieldFlags?.optBoolean("static") == true) add("static")
                            if (fieldFlags?.optBoolean("final") == true) add("final")
                            if (fieldFlags?.optBoolean("const") == true) add("const")
                            if (fieldFlags?.optBoolean("late") == true) add("late")
                        }.joinToString(", ")
                    )
                },
                methods = json.optJSONArray("methods").mapObjects { method ->
                    FlutterMethod(
                        name = method.optString("name", "method"),
                        address = method.optLong("entry"),
                        signature = method.optString("signature"),
                        kind = method.optString("kind")
                    )
                }
            )
        }
    }
}

data class FlutterString(
    val address: Long?,
    val value: String,
    val category: String,
    val length: Int
) {
    companion object {
        fun fromJson(json: JSONObject) = FlutterString(
            address = json.optionalLong("addr"),
            value = json.optString("value"),
            category = json.optString("category", "unknown"),
            length = json.optInt("len")
        )
    }
}

data class FlutterXrefNode(
    val type: String,
    val name: String,
    val address: Long?
) {
    companion object {
        fun fromJson(json: JSONObject?) = FlutterXrefNode(
            type = json?.optString("type", "unknown") ?: "unknown",
            name = json?.optString("name").orEmpty(),
            address = json?.optionalLong("addr")
        )
    }
}

data class FlutterXref(
    val kind: String,
    val origin: String,
    val source: FlutterXrefNode,
    val destination: FlutterXrefNode
) {
    companion object {
        fun fromJson(json: JSONObject) = FlutterXref(
            kind = json.optString("kind"),
            origin = json.optString("origin"),
            source = FlutterXrefNode.fromJson(json.optJSONObject("src")),
            destination = FlutterXrefNode.fromJson(json.optJSONObject("dst"))
        )
    }
}

data class FlutterInstruction(
    val index: Int,
    val address: Long,
    val name: String,
    val kind: String,
    val pcOffset: Long
) {
    companion object {
        fun fromJson(json: JSONObject) = FlutterInstruction(
            index = json.optInt("index"),
            address = json.optLong("address"),
            name = json.optString("name"),
            kind = json.optString("kind"),
            pcOffset = json.optLong("pc_offset")
        )
    }
}

data class FlutterComponent(
    val type: String,
    val name: String,
    val version: String,
    val confidence: Int,
    val source: String,
    val evidence: String
) {
    companion object {
        fun fromJson(json: JSONObject) = FlutterComponent(
            type = json.optString("type"),
            name = json.optString("name"),
            version = if (json.isNull("version")) "" else json.optString("version"),
            confidence = json.optInt("confidence"),
            source = json.optString("source"),
            evidence = json.optString("evidence")
        )
    }
}

internal fun <T> JSONArray?.mapObjects(mapper: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it) }.map(mapper)
}

internal fun JSONObject.optionalLong(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    return optLong(key)
}
