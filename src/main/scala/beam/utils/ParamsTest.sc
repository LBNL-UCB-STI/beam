def getHash(concatParams: Any*): String = {
  val concatString = concatParams.foldLeft("")((a, b) => a + b)
  concatString
}

getHash("a", "b", "c")

505396532 % 5000
