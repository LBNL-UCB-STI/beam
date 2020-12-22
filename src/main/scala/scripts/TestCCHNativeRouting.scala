package scripts

class TestCCHNativeRouting {
  @native def init(filePath: String): Unit
  @native def calcRoute(from: Long, to: Long): List[Long]
}
