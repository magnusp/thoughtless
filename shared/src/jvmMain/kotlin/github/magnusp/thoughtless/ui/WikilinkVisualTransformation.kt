package github.magnusp.thoughtless.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import github.magnusp.thoughtless.domain.wikilink.WikilinkResolver

class WikilinkVisualTransformation(
    private val linkColor: Color = Color(0xFF818CF8),
    private val linkBgColor: Color = Color(0xFF312E81).copy(alpha = 0.25f),
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val links = WikilinkResolver.extractWikilinks(raw)

        val builder = AnnotatedString.Builder(text)
        for (link in links) {
            builder.addStyle(
                style = SpanStyle(
                    color = linkColor,
                    fontWeight = FontWeight.SemiBold,
                    background = linkBgColor,
                ),
                start = link.startIndex,
                end = link.endIndex,
            )
        }

        return TransformedText(
            text = builder.toAnnotatedString(),
            offsetMapping = OffsetMapping.Identity,
        )
    }
}
