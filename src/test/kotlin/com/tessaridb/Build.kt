package com.tessaridb

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Translates the query corpus's `build` notation into calls on this builder.
 *
 * It lives beside the tests rather than inside one because two suites need it:
 * the offline rendering check and the live node run. A second translation
 * written for the second suite would be a second chance to be wrong, in the one
 * place where both suites' agreement is the evidence.
 */
internal object Build {
    fun render(build: JsonObject): Rendered {
        val kind = build.keys.single()
        val held = build.getValue(kind).jsonObject
        return when (kind) {
            "select" -> select(held)
            "create_record" -> {
                val statement = createRecord(held.text("table"), Corpus.value(held.getValue("id")))
                for ((field, value) in fields(held)) statement.set(field, value)
                statement.render()
            }
            "create_in_table" -> {
                val statement = createInTable(held.text("table"))
                for ((field, value) in fields(held)) statement.set(field, value)
                statement.render()
            }
            "update_record" -> {
                val statement = updateRecord(held.text("table"), Corpus.value(held.getValue("id")))
                for ((field, value) in fields(held)) statement.set(field, value)
                statement.render()
            }
            "delete_record" ->
                deleteRecord(held.text("table"), Corpus.value(held.getValue("id"))).render()
            else -> error("the corpus carries a statement this test does not translate: $kind")
        }
    }

    private fun select(held: JsonObject): Rendered {
        val statement = com.tessaridb.select(held.text("from"))
        for (item in (held["fields"] as? JsonArray).orEmpty()) {
            if (item is JsonPrimitive) {
                statement.field(item.content)
            } else {
                val window = item.jsonObject.getValue("lines").jsonObject
                statement.lines(
                    window.text("field"),
                    window.getValue("start").jsonPrimitive.long,
                    window.getValue("count").jsonPrimitive.long,
                )
            }
        }
        held["where"]?.let { statement.where(filter(it.jsonObject)) }
        for (pair in (held["order"] as? JsonArray).orEmpty()) {
            val ordering = pair.jsonArray
            statement.orderBy(
                ordering[0].jsonPrimitive.content,
                Direction.valueOf(ordering[1].jsonPrimitive.content.uppercase()),
            )
        }
        held["start"]?.let { statement.start(it.jsonPrimitive.long) }
        // Named in corpus order rather than in clause order, deliberately: the
        // rendered sequence is the builder's to decide, and a translation that
        // called them in the order they must appear would prove nothing.
        held["staleness"]?.let { statement.staleness(it.jsonPrimitive.content) }
        held["answered_by"]?.let { statement.answeredBy(it.jsonPrimitive.content) }
        held["limit"]?.let { statement.limit(it.jsonPrimitive.long) }
        return statement.render()
    }

    private fun filter(spec: JsonObject): Filter {
        val kind = spec.keys.single()
        return when (kind) {
            "compare" -> {
                val held = spec.getValue("compare").jsonObject
                compare(
                    held.text("field"),
                    Operator.valueOf(held.text("op").uppercase()),
                    Corpus.value(held.getValue("value")),
                )
            }
            "and" -> {
                val both = spec.getValue("and").jsonArray
                and(filter(both[0].jsonObject), filter(both[1].jsonObject))
            }
            "or" -> {
                val both = spec.getValue("or").jsonArray
                or(filter(both[0].jsonObject), filter(both[1].jsonObject))
            }
            else -> error("the corpus carries a filter shape this test does not translate: $kind")
        }
    }

    private fun fields(held: JsonObject): List<Pair<String, Value>> =
        (held["set"] as? JsonObject).orEmpty().map { (field, value) -> field to Corpus.value(value) }

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())

    private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())
}
