package com.woodruff.tvexplorer.player

import org.junit.Assert.assertEquals
import org.junit.Test

class EpisodeLabelTest {
    @Test fun showEpisodeAndName() =
        assertEquals(EpisodeLabel("Backyard Science · S2, E5", "The Volcano"), EpisodeLabel.of("Backyard Science S02E05 The Volcano"))

    @Test fun dottedFileNames() =
        assertEquals(EpisodeLabel("The Office · S2, E3", "The Dundies"), EpisodeLabel.of("The.Office.S02E03.The.Dundies"))

    @Test fun spacedAndDashed() =
        assertEquals(EpisodeLabel("Backyard Science · S2, E4", "Rockets"), EpisodeLabel.of("Backyard Science - S2 E4 - Rockets"))

    @Test fun crossFormat() =
        assertEquals(EpisodeLabel("Bluey · S1, E12", "Takeaway"), EpisodeLabel.of("Bluey 1x12 Takeaway"))

    @Test fun noEpisodeName() =
        assertEquals(EpisodeLabel("Backyard Science · Season 2", "Episode 4"), EpisodeLabel.of("Backyard Science · S2 E4"))

    @Test fun noShowName() =
        assertEquals(EpisodeLabel("S1, E13", "Pilot"), EpisodeLabel.of("S01E13_Pilot"))

    @Test fun filmsKeepTheirTitle() =
        assertEquals(EpisodeLabel(null, "Midnight Harbor (2024)"), EpisodeLabel.of("Midnight Harbor (2024)"))

    @Test fun yearsAreNotEpisodes() =
        assertEquals(EpisodeLabel(null, "Blade Runner 2049"), EpisodeLabel.of("Blade Runner 2049"))
}
