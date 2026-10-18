package ob

/** Sector of each F&O stock, from resources/sectors.txt. Stocks missing there land in "Others". */
object Sectors {
    private val bySymbol: Map<String, String> by lazy {
        val text = Sectors::class.java.getResource("/sectors.txt")!!.readText()
        val out = LinkedHashMap<String, String>()
        for (line in text.lines().map { it.trim() }) {
            if (line.isEmpty() || line.startsWith("#")) continue
            val sector = line.substringBefore(':').trim()
            line.substringAfter(':').trim().split(Regex("\\s+")).forEach { out[it] = sector }
        }
        out
    }

    fun of(symbol: String): String = bySymbol[symbol] ?: "Others"

    /** The simulator trades the same names. */
    fun simStocks(): List<Stock> = bySymbol.map { (sym, sector) -> Stock(sym, sym, "SIM|$sym", sector) }
}
