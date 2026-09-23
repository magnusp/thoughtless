package github.magnusp.thoughtless.domain.wikilink

import kotlin.test.*

class WikilinkResolverTest {

    @Test
    fun testExtractStandardWikilink() {
        val text = "Refer to [[doc-spec-auth]] for details."
        val links = WikilinkResolver.extractWikilinks(text)
        assertEquals(1, links.size)
        val link = links.first()
        assertEquals("[[doc-spec-auth]]", link.rawMatch)
        assertEquals("doc-spec-auth", link.targetNodeId)
        assertEquals("doc-spec-auth", link.documentId)
        assertNull(link.sectionAnchor)
        assertEquals("REFERENCES", link.relation)
        assertNull(link.alias)
    }

    @Test
    fun testExtractRelationAndSectionWikilink() {
        val text = "Architecture [[implements:doc-engine#storage-subsystem|Storage Layer]] is complete."
        val links = WikilinkResolver.extractWikilinks(text)
        assertEquals(1, links.size)
        val link = links.first()
        assertEquals("doc-engine#storage-subsystem", link.targetNodeId)
        assertEquals("doc-engine", link.documentId)
        assertEquals("storage-subsystem", link.sectionAnchor)
        assertEquals("IMPLEMENTS", link.relation)
        assertEquals("Storage Layer", link.alias)
    }

    @Test
    fun testExtractLocalAnchorWikilink() {
        val text = "Jump to [[#performance-goals]] below."
        val links = WikilinkResolver.extractWikilinks(text, currentDocumentId = "doc-current")
        assertEquals(1, links.size)
        val link = links.first()
        assertEquals("doc-current#performance-goals", link.targetNodeId)
        assertEquals("doc-current", link.documentId)
        assertEquals("performance-goals", link.sectionAnchor)
    }

    @Test
    fun testFindWikilinkAtOffset() {
        val text = "See [[doc-one]] and [[doc-two]] here."
        // Indices:
        // "See [[doc-one]]" -> starts at 4, ends at 15
        // " and [[doc-two]]" -> starts at 20, ends at 31

        // Before first link
        assertNull(WikilinkResolver.findWikilinkAtOffset(text, 2))

        // Inside first link
        val link1 = WikilinkResolver.findWikilinkAtOffset(text, 7)
        assertNotNull(link1)
        assertEquals("doc-one", link1.documentId)

        // Between links
        assertNull(WikilinkResolver.findWikilinkAtOffset(text, 17))

        // Inside second link
        val link2 = WikilinkResolver.findWikilinkAtOffset(text, 25)
        assertNotNull(link2)
        assertEquals("doc-two", link2.documentId)
    }
}
