package beam.agentsim.infrastructure.charging

import beam.agentsim.infrastructure.charging.ChargingPointType.CustomChargingPoint
import org.scalactic.source
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ChargingPointTypeSpec extends AnyWordSpec with Matchers {
  "ChargingPointType" when {
    "formal charging point types" when {
      "given string names of built-in charging point types" should {
        "construct that charging point type" in {
          for {
            expectedChargingPointType <- ChargingPointType.AllFormalChargingPointTypes
            chargingPointTypeString = expectedChargingPointType.toString
          } {
            ChargingPointType(chargingPointTypeString) match {
              case None =>
                fail(s"name $chargingPointTypeString did not map to a ChargingPointType object")
              case Some(chargingPointType) =>
                chargingPointType should equal(expectedChargingPointType)
            }
          }
        }
      }
    }
    "custom charging point types" when {
      "given a string describing a valid custom ChargingPointType with correct formatting" should {
        "construct that charging point type" in {
          val input = "beam.sim.test(250|DC)"
          ChargingPointTypeSpec.expectsACustomChargingPoint(input) match {
            case Left(e) => fail(e)
            case Right(result) =>
              result.id should equal("test")
              result.installedCapacity should equal(250.0)
              result.electricCurrentType should equal(ElectricCurrentType.DC)
          }
        }
      }
      "given a string with valid CustomChargingPoint data but extra spaces" should {
        "still correctly parse and construct that ChargingPointType" in {
          val input = "beam.sim.test ( 250 | DC )"
          ChargingPointTypeSpec.expectsACustomChargingPoint(input) match {
            case Left(e) => fail(e)
            case Right(result) =>
              result.id should equal("test")
              result.installedCapacity should equal(250.0)
              result.electricCurrentType should equal(ElectricCurrentType.DC)
          }
        }
      }
      "given strings with invalid installed capacity values" should {
        "throw an exception" in {
          val twoPeriods = "beam.sim.test ( 250.. | DC )"
          an[IllegalArgumentException] should be thrownBy ChargingPointTypeSpec.expectsACustomChargingPoint(twoPeriods)
          val hasLetters = "beam.sim.test ( 25a0 | DC )"
          an[IllegalArgumentException] should be thrownBy ChargingPointTypeSpec.expectsACustomChargingPoint(hasLetters)
          val comma = "beam.sim.test ( 250,0 | DC )"
          an[IllegalArgumentException] should be thrownBy ChargingPointTypeSpec.expectsACustomChargingPoint(comma)
          val negativeValue = "beam.sim.test ( -250 | DC )"
          an[IllegalArgumentException] should be thrownBy ChargingPointTypeSpec.expectsACustomChargingPoint(
            negativeValue
          )

        }
      }
      "given strings with varying character case for electricCurrentType" should {
        "still correctly parse and construct that ChargingPointType" in {
          val bothUpper = "beam.sim.test ( 250 | AC )"
          val lowerFirst = "beam.sim.test ( 250 | dC )"
          val lowerSecond = "beam.sim.test ( 250 | Ac )"
          val bothLower = "beam.sim.test ( 250 | dc )"
          ChargingPointTypeSpec.expectsACustomChargingPoint(bothUpper) match {
            case Left(e) => fail(e)
            case Right(result) =>
              result.electricCurrentType should equal(ElectricCurrentType.AC)
          }
          ChargingPointTypeSpec.expectsACustomChargingPoint(lowerFirst) match {
            case Left(e) => fail(e)
            case Right(result) =>
              result.electricCurrentType should equal(ElectricCurrentType.DC)
          }
          ChargingPointTypeSpec.expectsACustomChargingPoint(lowerSecond) match {
            case Left(e) => fail(e)
            case Right(result) =>
              result.electricCurrentType should equal(ElectricCurrentType.AC)
          }
          ChargingPointTypeSpec.expectsACustomChargingPoint(bothLower) match {
            case Left(e) => fail(e)
            case Right(result) =>
              result.electricCurrentType should equal(ElectricCurrentType.DC)
          }
        }
      }
      "given strings with invalid electricCurrentType values" should {
        "throw an exception" in {
          val badLetters = "beam.sim.test ( 250 | AB )"
          an[IllegalArgumentException] should be thrownBy ChargingPointTypeSpec.expectsACustomChargingPoint(badLetters)
          val spaceBetweenLetters = "beam.sim.test ( 250 | A C )"
          an[IllegalArgumentException] should be thrownBy ChargingPointTypeSpec.expectsACustomChargingPoint(
            spaceBetweenLetters
          )
          val containsValidButTooLong = "beam.sim.test ( 25a0 | aaaDCaaa )"
          an[IllegalArgumentException] should be thrownBy ChargingPointTypeSpec.expectsACustomChargingPoint(
            containsValidButTooLong
          )
          val flipped = "beam.sim.test ( DC | 1234 )"
          an[IllegalArgumentException] should be thrownBy ChargingPointTypeSpec.expectsACustomChargingPoint(flipped)
        }
      }
    }
  }
}

object ChargingPointTypeSpec extends org.scalatest.Assertions {

  /**
    * helper beam.sim.test function which handles the common parsing failures
    * @param input a string representation of a CustomChargingPoint
    * @param pos required for calling "fail" here, an implicit that tracks the call point of an error
    * @return either an error string, or a CustomChargingPoint
    */
  def expectsACustomChargingPoint(input: String): Either[String, CustomChargingPoint] = {
    ChargingPointType(input) match {
      case None =>
        Left { s"input '$input' failed to parse" }
      case Some(chargingPointType) =>
        chargingPointType match {
          case result: CustomChargingPoint =>
            Right { result }
          case _ =>
            Left { s"a CustomChargingPoint was not parsed from provided string description '$input'" }
        }
    }
  }
}
