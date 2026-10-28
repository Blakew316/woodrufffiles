package com.woodruff.tvexplorer.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MailPicturesTest {
    private fun link(html: String, id: String) = MailClient.linkPicture(html, id, "https://cid.invalid/7")

    @Test fun linksThePicture() = assertEquals("<img src=\"https://cid.invalid/7\">", link("<img src=\"cid:logo\">", "logo"))

    @Test fun leavesIdsItIsTheStartOf() = assertEquals(
        "<img src='https://cid.invalid/7'><img src='cid:image10'>",
        link("<img src='cid:image1'><img src='cid:image10'>", "image1"),
    )

    @Test fun everyLinkAnyCase() = assertEquals(
        "<img src=\"https://cid.invalid/7\"> <img src=https://cid.invalid/7>",
        link("<img src=\"CID:Logo\"> <img src=cid:logo>", "logo"),
    )

    @Test fun backgroundPictures() = assertEquals(
        "url(https://cid.invalid/7) url(&quot;https://cid.invalid/7&quot;)",
        link("url(cid:bg) url(&quot;cid:bg&quot;)", "bg"),
    )

    @Test fun idsAreTakenLiterally() {
        assertEquals("<img src=\"https://cid.invalid/7\">", link("<img src=\"cid:part1.06@example.com\">", "part1.06@example.com"))
        assertNull(link("<img src=\"cid:part1x06@example.com\">", "part1.06@example.com"))
        assertNull(link("<img src=\"cid:aab\">", "a+b"))
    }

    @Test fun notInThePage() = assertNull(link("<img src=\"cid:other\">", "logo"))
}
