package beam.tags

import org.scalatest.Tag

object Periodic extends Tag("beam.tags.Periodic")

object Performance extends Tag("beam.tags.Performance")

object Integration extends Tag("beam.tags.Integration")

object Mocked extends Tag("beam.tags.Mocked")

object ExcludeRegular extends Tag("beam.tags.ExcludeRegular")

object ExcludeOnBitbucket extends Tag("beam.tags.ExcludeOnBitbucket")

object FlakyTest extends Tag("beam.tags.FlakyTest")
