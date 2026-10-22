package ob

import ob.upstox.Upstox
import java.io.File
import java.time.Instant
import java.time.LocalTime
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpstoxTokenTest {
    @Test
    fun `a login lasts until the next 3_30 AM IST`() {
        val dir = createTempDirectory("token").toFile()
        val issued = System.currentTimeMillis() - 60_000
        File(dir, "upstox-token.json").writeText("""{"token":"t","issuedMs":$issued}""")
        val api = Upstox(Config(8080, "upstox", "k", "s", "http://localhost/cb", dir, 0, 1.0, null))
        assertTrue(api.loadToken())
        assertTrue(api.loggedIn(issued + 1))

        val at = Instant.ofEpochMilli(issued).atZone(IST)
        val day = if (at.toLocalTime() < LocalTime.of(3, 30)) at.toLocalDate() else at.toLocalDate().plusDays(1)
        val cutoff = day.atTime(3, 30).atZone(IST).toInstant().toEpochMilli()
        assertTrue(api.loggedIn(cutoff - 1))
        assertFalse(api.loggedIn(cutoff))
    }
}
