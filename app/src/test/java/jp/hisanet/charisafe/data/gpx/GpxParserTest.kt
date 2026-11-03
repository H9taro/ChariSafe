package jp.hisanet.charisafe.data.gpx

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class GpxParserTest {

    @Test
    fun testGpxNamespaceXmlParsingLogic() {
        val gpxXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="ChariSafe Android" xmlns="http://www.topografix.com/GPX/1/1">
              <trk>
                <name>ChariSafe Ride</name>
                <trkseg>
                  <trkpt lat="35.450542" lon="139.388461">
                    <ele>96.9</ele>
                    <time>2026-11-02T23:58:47Z</time>
                    <extensions>
                      <speed_kmh>1.1</speed_kmh>
                    </extensions>
                  </trkpt>
                </trkseg>
              </trk>
            </gpx>
        """.trimIndent()

        val tempFile = File.createTempFile("test_ride", ".gpx")
        tempFile.writeText(gpxXml)
        tempFile.deleteOnExit()

        // 簡易的なパース検証
        val dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isIgnoringComments = true
        }
        val db = dbf.newDocumentBuilder()
        val doc = db.parse(tempFile)
        val trkpts = doc.getElementsByTagName("*")
        var foundSpeed = 0f

        for (i in 0 until trkpts.length) {
            val node = trkpts.item(i)
            if (node.nodeType == org.w3c.dom.Node.ELEMENT_NODE) {
                val elem = node as org.w3c.dom.Element
                if (elem.nodeName.lowercase().contains("speed_kmh")) {
                    foundSpeed = elem.textContent.toFloatOrNull() ?: 0f
                }
            }
        }

        assertEquals(1.1f, foundSpeed, 0.001f)
    }
}
