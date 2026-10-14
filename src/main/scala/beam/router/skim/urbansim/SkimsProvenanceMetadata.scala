package beam.router.skim.urbansim

import java.nio.{ByteBuffer, ByteOrder}
import java.security.MessageDigest
import java.nio.charset.StandardCharsets

case class SkimsProvenanceMetadata(
  beamSkimsSchemaVersion: String = SkimsProvenanceMetadata.SchemaVersion,
  skimsKind: String,
  routerType: String,
  skimsGeoType: String,
  peakHours: String,
  modesBuilt: String,
  bifurcateTolls: Boolean,
  generateReturnTrips: Boolean,
  tazCount: Int
)

object SkimsProvenanceMetadata {
  val SchemaVersion: String = "1.0"

  private val CanonicalNaNBits: Int = 0x7fc00000

  /**
    * Normalizes a Float to guarantee deterministic IEEE-754 bit patterns:
    * - All NaNs are normalized to the canonical quiet NaN (0x7fc00000).
    * - Negative zero (-0.0f) is normalized to positive zero (+0.0f).
    */
  @inline def canonicalizeFloat(value: Float): Float = {
    if (java.lang.Float.isNaN(value)) {
      java.lang.Float.intBitsToFloat(CanonicalNaNBits)
    } else if (value == 0.0f) {
      0.0f
    } else {
      value
    }
  }

  @inline def canonicalizeFloatBits(value: Float): Int = {
    if (java.lang.Float.isNaN(value)) {
      CanonicalNaNBits
    } else if (value == 0.0f) {
      0
    } else {
      java.lang.Float.floatToRawIntBits(value)
    }
  }

  /**
    * Computes a deterministic `zone_number` lookup array (`int32`, shape `(N,)`) from `geoUnits`.
    * If all `geoUnits` parse as 32-bit integers, returns those parsed IDs; otherwise returns 1-based indices `1..N`.
    */
  def buildZoneNumbers(geoUnits: Seq[String]): Array[Int] = {
    val parsed = new Array[Int](geoUnits.size)
    var i = 0
    var allNumeric = true
    val iter = geoUnits.iterator
    while (iter.hasNext && allNumeric) {
      val raw = iter.next().trim
      try {
        parsed(i) = Integer.parseInt(raw)
      } catch {
        case _: NumberFormatException =>
          allNumeric = false
      }
      i += 1
    }
    if (allNumeric) {
      parsed
    } else {
      Array.tabulate(geoUnits.size)(idx => idx + 1)
    }
  }

  /**
    * Computes a canonical SHA-256 content hash (`sha256:<hex>`) over:
    * 1. `lookup/zone_number` (`int32`, shape `(N,)`, C-order little-endian bytes)
    * 2. Each matrix in lexicographical order of `matrixName`, hashing `(matrixName, dtype="<f4", shape, C-order little-endian float32 bytes)`
    */
  def computeLogicalMatricesSha256(
    zoneNumbers: Array[Int],
    sortedMatrices: Seq[(String, Array[Int], Array[Float])]
  ): String = {
    val digest = MessageDigest.getInstance("SHA-256")

    def updateString(s: String): Unit = {
      val bytes = s.getBytes(StandardCharsets.UTF_8)
      val lenBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.length)
      digest.update(lenBuf.array())
      digest.update(bytes)
    }

    updateString("lookup/zone_number")
    updateString("<i4")
    updateString(s"(${zoneNumbers.length},)")
    val zoneBuf = ByteBuffer.allocate(zoneNumbers.length * 4).order(ByteOrder.LITTLE_ENDIAN)
    var zIdx = 0
    while (zIdx < zoneNumbers.length) {
      zoneBuf.putInt(zoneNumbers(zIdx))
      zIdx += 1
    }
    digest.update(zoneBuf.array())

    sortedMatrices.foreach { case (matrixName, shape, flatRowMajorValues) =>
      updateString(matrixName)
      updateString("<f4")
      updateString(shape.mkString("(", ",", ")"))
      val byteBuf = ByteBuffer.allocate(flatRowMajorValues.length * 4).order(ByteOrder.LITTLE_ENDIAN)
      var idx = 0
      while (idx < flatRowMajorValues.length) {
        byteBuf.putInt(canonicalizeFloatBits(flatRowMajorValues(idx)))
        idx += 1
      }
      digest.update(byteBuf.array())
    }

    val hashBytes = digest.digest()
    val hex = new java.lang.StringBuilder(hashBytes.length * 2 + 7)
    hex.append("sha256:")
    var bIdx = 0
    while (bIdx < hashBytes.length) {
      val v = hashBytes(bIdx) & 0xff
      if (v < 16) hex.append('0')
      hex.append(Integer.toHexString(v))
      bIdx += 1
    }
    hex.toString
  }
}
