package io.theficos.comanda.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class BackupPolicyTest {
    private val source = File("src/main").takeIf { it.isDirectory } ?: File("app/src/main")
    private val domains = setOf("root", "file", "database", "sharedpref", "external",
        "device_root", "device_file", "device_database", "device_sharedpref")
    private fun root(file: File): Element = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(file).documentElement
    private fun elements(parent: Element, tag: String): List<Element> {
        val nodes = parent.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    @Test fun `cloud backup stays empty while only database files can transfer`() {
        val manifest = root(File(source, "AndroidManifest.xml"))
        val app = elements(manifest, "application").single()
        assertThat(app.getAttribute("android:allowBackup")).isEqualTo("true")
        fun rules(attribute: String) = root(File(source, "res/xml/" + app.getAttribute(attribute).substringAfter("@xml/") + ".xml"))
        val legacy = rules("android:fullBackupContent")
        val modern = rules("android:dataExtractionRules")
        val cloud = elements(modern, "cloud-backup").single()
        for (policy in listOf(legacy, cloud)) {
            assertThat(elements(policy, "include")).isEmpty()
            val exclusions = elements(policy, "exclude")
            assertThat(exclusions.map { it.getAttribute("domain") }.toSet()).containsExactlyElementsIn(domains)
            assertThat(exclusions.map { it.getAttribute("path") }.toSet()).containsExactly(".")
        }
        val transfer = elements(modern, "device-transfer").single()
        val included = elements(transfer, "include")
        assertThat(included.map { it.getAttribute("domain") }.toSet()).containsExactly("database")
        assertThat(included.map { it.getAttribute("path") }).containsExactly("comanda.db", "comanda.db-wal", "comanda.db-shm")
        // Includes are an allowlist: no preference, root or external file is eligible.
        assertThat(included).hasSize(3)
        assertThat(elements(transfer, "exclude")).isEmpty()
    }
}
