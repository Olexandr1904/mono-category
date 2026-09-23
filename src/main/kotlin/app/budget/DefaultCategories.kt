package app.budget

/**
 * The starter set the owner asked for after finding the MCC list empty on day one: eleven
 * categories with the MCC codes Monobank actually returns for the merchants underneath
 * them. Names are the owner's own wording, verbatim — never edit them here.
 *
 * Consumed by [app.ingest.IngestService.seedDefaultCategories], which is the only writer
 * allowed to turn this into rows (spec: every mutation goes through the ingest mutex).
 */
data class DefaultCategory(val name: String, val emoji: String, val mccs: List<Int>)

val DEFAULT_CATEGORIES: List<DefaultCategory> = listOf(
    DefaultCategory("Авто", "🚗", listOf(5511, 5521, 5532, 5533, 5541)),
    DefaultCategory("Ресторани та бари", "🍽", listOf(5811, 5812, 5813, 5814)),
    DefaultCategory("Здоров'я та краса", "💅", listOf(5912, 5977, 7230, 7298, 8099, 8021)),
    DefaultCategory("Освіта", "📚", listOf(8211, 8220, 8241, 8244, 8249, 8299)),
    DefaultCategory("Розваги", "🎮", listOf(7832, 7922, 7991, 7996, 7997, 7998, 7999)),
    DefaultCategory(
        "Шопінг", "🛍",
        listOf(
            5311, 5611, 5621, 5631, 5641, 5651, 5655, 5661, 5691, 5699,
            5941, 5942, 5943, 5944, 5945, 5946, 5947, 5948, 5949,
            5964, 5965, 5966, 5967, 5968, 5969, 5995, 5999, 5732, 5399, 5310,
        ),
    ),
    DefaultCategory("Продукти", "🛒", listOf(5411, 5422, 5441, 5451, 5462, 5499)),
    DefaultCategory("Транспорт", "🚌", listOf(4111, 4121, 4131, 4789)),
    DefaultCategory("Різне", "📦", listOf(4829, 5734, 7542)),
    DefaultCategory("Подарунки", "🎁", listOf(5992)),
    DefaultCategory("Поповнення мобільного", "📱", listOf(4814)),
)

/** Threshold applied to a seeded category — the same default the "new category" form uses. */
const val DEFAULT_SEED_THRESHOLD_PCT: Int = 80
