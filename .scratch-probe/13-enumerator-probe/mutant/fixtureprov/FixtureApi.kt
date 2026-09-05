package fixture

// Hand-known answers for this file:
//   ARM A routes  : directRoute (calls transport.fetchJson), fetchHelper (private, a false positive)
//   ARM A misses  : indirectRoute (goes through fetchHelper)
//   ARM B routes  : builtLink (called by directRoute) -> pinned=true
//   ARM B misses  : indirectRoute (inline URL, no builder)
//   decoyUrl      : a builder no suspend fun calls, so ARM B must NOT report it
internal class FixtureApi(private val httpClient: HttpClient) {
    suspend fun directRoute(id: String): String? = transport.fetchJson(builtLink(id))

    suspend fun indirectRoute(id: String): String? = fetchHelper("$BASE_URL/inline/$id")

    private suspend fun fetchHelper(url: String): String? = transport.fetchJson(url)

    private fun notARoute(json: String): String = json

    companion object {
        const val BASE_URL = "https://example.invalid"

        fun builtLink(id: String): String = "$BASE_URL/built/$id"

        fun decoyUrl(id: String): String = "$BASE_URL/decoy/$id"

        val SCHEMA_PIN_TARGETS: List<SchemaTarget> = listOf(
            SchemaTarget(
                provider = "fixture",
                route = "directRoute",
                url = builtLink("1"),
                requiredPaths = listOf("a"),
            ),
        )
    }
}
