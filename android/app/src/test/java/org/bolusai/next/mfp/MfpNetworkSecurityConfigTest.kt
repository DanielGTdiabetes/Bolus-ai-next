package org.bolusai.next.mfp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * ADR 0032, "Límite de red impuesto por el sistema": the network security configuration packaged with the app trusts
 * certificates for exactly the hosts [MfpHosts.resourceAllowed] lets a page load, and for nothing else.
 *
 * The test reads the resource from the source tree and applies Android's own matching rule (a `domain` matches its
 * exact name, and its subdomains only with `includeSubdomains`), so a host added to one side and not to the other
 * fails here before it reaches a phone.
 */
class MfpNetworkSecurityConfigTest {
    private val root: Element = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(File("src/main/res/xml/network_security_config.xml")).documentElement

    private fun Element.elements(): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()

    private fun Element.attributeMap(): Map<String, String> =
        (0 until attributes.length).associate { attributes.item(it).nodeName to attributes.item(it).nodeValue }

    private val base get() = root.elements().single { it.tagName == "base-config" }
    private val link get() = root.elements().single { it.tagName == "domain-config" }

    /** Android's rule for which hosts a `domain-config` covers. */
    private fun trusted(host: String): Boolean = link.elements().filter { it.tagName == "domain" }.any { domain ->
        val name = domain.textContent
        host == name || (domain.getAttribute("includeSubdomains") == "true" && host.endsWith(".$name"))
    }

    @Test fun byDefaultNoCertificateIsTrustedAndCleartextIsNeverPermitted() {
        assertEquals("network-security-config", root.tagName)
        assertEquals(listOf("base-config", "domain-config"), root.elements().map { it.tagName })
        assertEquals(mapOf("cleartextTrafficPermitted" to "false"), base.attributeMap())
        val anchors = base.elements().single()
        assertEquals("trust-anchors", anchors.tagName)
        assertTrue("the default configuration must trust no certificate source", anchors.elements().isEmpty())
    }

    @Test fun onlyTheLinkHostsTrustTheSystemAuthoritiesWithoutUserCertificatesOrOverrides() {
        assertEquals(mapOf("cleartextTrafficPermitted" to "false"), link.attributeMap())
        assertEquals(listOf("domain", "domain", "trust-anchors"), link.elements().map { it.tagName })
        val certificates = link.elements().single { it.tagName == "trust-anchors" }.elements().single()
        assertEquals("certificates", certificates.tagName)
        assertEquals(mapOf("src" to "system"), certificates.attributeMap())
    }

    @Test fun theDomainsAreThoseOfTheRequestFilter() {
        val domains = link.elements().filter { it.tagName == "domain" }
            .associate { it.textContent to it.getAttribute("includeSubdomains") }

        assertEquals(mapOf(MfpHosts.DOMAIN to "true", MfpHosts.CHALLENGE_HOST to "false"), domains)
    }

    @Test fun aHostIsTrustedExactlyWhenTheRequestFilterAllowsIt() {
        listOf(
            MfpHosts.DIARY_HOST,
            "myfitnesspal.com",
            "api.myfitnesspal.com",
            "a.b.myfitnesspal.com",
            "challenges.cloudflare.com",
            "cloudflare.com",
            "www.cloudflare.com",
            "sub.challenges.cloudflare.com",
            "notmyfitnesspal.com",
            "myfitnesspal.com.example.org",
            "myfitnesspal.org",
            "example.org",
            "localhost",
        ).forEach { host -> assertEquals(host, MfpHosts.resourceAllowed("https://$host/"), trusted(host)) }
        assertTrue(trusted(MfpHosts.DIARY_HOST))
    }
}
