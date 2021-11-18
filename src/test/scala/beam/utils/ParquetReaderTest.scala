package beam.utils

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

class ParquetReaderTest extends AnyWordSpec with Matchers {
  "ParquetReader" should {
    "be able to read parquet file" in {
      val (iter, toClose) = ParquetReader.read("test/test-resources/test.parquet")
      try {
        val l = iter.take(2).toArray

        // Test first row
        {
          val value = l(0)
          value.get("household_id").asInstanceOf[Long] should be(0L)
          value.get("serialno").asInstanceOf[Long] should be(2010000487191L)
          value.get("persons").asInstanceOf[Long] should be(1L)
          value.get("building_type").asInstanceOf[Double] should be(6.0)
          value.get("cars").asInstanceOf[Double] should be(1.0)
          value.get("income").asInstanceOf[Double] should be(85000.0)
          value.get("race_of_head").asInstanceOf[Long] should be(1L)
          value.get("hispanic_head").asInstanceOf[org.apache.avro.util.Utf8].toString should be("no")
          value.get("age_of_head").asInstanceOf[Long] should be(47L)
          value.get("workers").asInstanceOf[Double] should be(1.0)
          value.get("state").asInstanceOf[Long] should be(6L)
          value.get("county").asInstanceOf[Long] should be(85L)
          value.get("tract").asInstanceOf[Long] should be(500901L)
          value.get("block_group").asInstanceOf[Long] should be(1)
          value.get("children").asInstanceOf[Double] should be(0.0)
          value.get("tenure").asInstanceOf[Long] should be(2)
          value.get("recent_mover").asInstanceOf[Long] should be(0)
          value.get("block_group_id").asInstanceOf[Long] should be(60855009011L)
          value.get("single_family").asInstanceOf[Boolean] should be(false)
          value.get("unit_id").asInstanceOf[Long] should be(1711366)
          value.get("building_id").asInstanceOf[Double] should be(409174.0)
        }

        // Test second row
        {
          val value = l(1)
          value.get("household_id").asInstanceOf[Long] should be(1L)
          value.get("serialno").asInstanceOf[Long] should be(2013000554587L)
          value.get("persons").asInstanceOf[Long] should be(1L)
          value.get("building_type").asInstanceOf[Double] should be(9.0)
          value.get("cars").asInstanceOf[Double] should be(1.0)
          value.get("income").asInstanceOf[Double] should be(27000.0)
          value.get("race_of_head").asInstanceOf[Long] should be(6L)
          value.get("hispanic_head").asInstanceOf[org.apache.avro.util.Utf8].toString should be("no")
          value.get("age_of_head").asInstanceOf[Long] should be(52L)
          value.get("workers").asInstanceOf[Double] should be(1.0)
          value.get("state").asInstanceOf[Long] should be(6L)
          value.get("county").asInstanceOf[Long] should be(85L)
          value.get("tract").asInstanceOf[Long] should be(500901L)
          value.get("block_group").asInstanceOf[Long] should be(1)
          value.get("children").asInstanceOf[Double] should be(0.0)
          value.get("tenure").asInstanceOf[Long] should be(2)
          value.get("recent_mover").asInstanceOf[Long] should be(0)
          value.get("block_group_id").asInstanceOf[Long] should be(60855009011L)
          value.get("single_family").asInstanceOf[Boolean] should be(false)
          value.get("unit_id").asInstanceOf[Long] should be(1711818)
          value.get("building_id").asInstanceOf[Double] should be(1579443.0)
        }
        println(l.toList)
      } finally {
        toClose.close()
      }
    }
  }
}
